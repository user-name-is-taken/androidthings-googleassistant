package com.example.androidthings.assistant;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.provider.MediaStore;
import android.util.Log;

import com.google.android.things.contrib.driver.voicehat.Max98357A;
import com.google.android.things.contrib.driver.voicehat.VoiceHat;

import java.io.IOException;
import java.nio.ByteBuffer;

import static android.content.ContentValues.TAG;

public class MyAudioTrack extends AudioTrack {
    private static Max98357A mDac;
    private boolean safeToStop = false;

    private AudioTrack.OnPlaybackPositionUpdateListener listener = new OnPlaybackPositionUpdateListener (){
        @Override
        public void onMarkerReached(AudioTrack audioTrack) {
            safeToStop = true;
            stop();
            Log.i(TAG, "Text to speech synthesis done");
            flush();
            reloadStaticData();

        }
        @Override
        public void onPeriodicNotification(AudioTrack audioTrack) {
            //pass
        }
    };

    public MyAudioTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes, int mode, int sessionId) throws IllegalArgumentException {
        super(attributes, format, bufferSizeInBytes, mode, sessionId);
        if(MyAssistant.USE_VOICEHAT_DAC) {
            Log.i(TAG, "initializing DAC trigger");
            try {
                this.mDac = VoiceHat.openDac();
                this.mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }else{
            Log.i(TAG, "Not using the Dac");
        }
    }





    public static Max98357A getmDac() {
        return mDac;
    }
    public static void setmDac(Max98357A mDac) {
        MyAudioTrack.mDac = mDac;
    }

    @Override
    public void play() throws IllegalStateException {
        super.play();
        if (mDac != null) {
            Log.i(TAG, "enabling the dac");
            try {
                mDac.setSdMode(Max98357A.SD_MODE_LEFT);
            } catch (IOException e) {
                Log.e(TAG, "unable to modify dac trigger", e);
            }
        }else{
            Log.i(TAG, "mDac is null, and it should " +
                    (MyAssistant.USE_VOICEHAT_DAC? "not ":"") + "be");
        }
    }

    /**
     * Advances the notification marker by advancementFrames frames
     * @param advancementFrames
     * @see this#setNotificationMarkerPosition(int)
     * @see this#getNotificationMarkerPosition()
     */
    public void advanceNotificationMarker(int advancementFrames){
        this.setNotificationMarkerPosition( advancementFrames +
                this.getNotificationMarkerPosition() );
    }


    /**
     * stops the AudioTrack if and kills the mDac if it's safe
     * @throws IllegalStateException
     */
    @Override
    public void stop() throws IllegalStateException {
        if(safeToStop){
            forceStop();
        }else{
            Log.i(TAG, "Tried to stop when it's not safe.");
        }
    }



    /**
     * forces the AudioTrack to stop without playing the remaining data in its buffer
     * @throws IllegalStateException
     */
    private void forceStop() throws IllegalStateException{
        super.stop();
        if (mDac != null) {
            try {
                mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);
            } catch (IOException e) {
                Log.e(TAG, "unable to modify dac trigger", e);
            }
        }else{
            Log.i(TAG, "mDac is null, and it should " +
                    (MyAssistant.USE_VOICEHAT_DAC? "not ":"") + "be");
        }
    }

    public static class Builder extends AudioTrack.Builder{
        /**
         * Builds an {@link AudioTrack} instance initialized with all the parameters set
         * on this <code>Builder</code>.
         * @return a new successfully initialized {@link AudioTrack} instance.
         * @throws UnsupportedOperationException if the parameters set on the <code>Builder</code>
         *     were incompatible, or if they are not supported by the device,
         *     or if the device was not available.
         */
        public @NonNull AudioTrack build() throws UnsupportedOperationException {
            if (mAttributes == null) {
                mAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build();
            }
            switch (mPerformanceMode) {
                case PERFORMANCE_MODE_LOW_LATENCY:
                    mAttributes = new AudioAttributes.Builder(mAttributes)
                            .replaceFlags((mAttributes.getAllFlags()
                                    | AudioAttributes.FLAG_LOW_LATENCY)
                                    & ~AudioAttributes.FLAG_DEEP_BUFFER)
                            .build();
                    break;
                case PERFORMANCE_MODE_NONE:
                    if (!shouldEnablePowerSaving(mAttributes, mFormat, mBufferSizeInBytes, mMode)) {
                        break; // do not enable deep buffer mode.
                    }
                    // permitted to fall through to enable deep buffer
                case PERFORMANCE_MODE_POWER_SAVING:
                    mAttributes = new AudioAttributes.Builder(mAttributes)
                            .replaceFlags((mAttributes.getAllFlags()
                                    | AudioAttributes.FLAG_DEEP_BUFFER)
                                    & ~AudioAttributes.FLAG_LOW_LATENCY)
                            .build();
                    break;
            }

            if (mFormat == null) {
                mFormat = new AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        //.setSampleRate(AudioFormat.SAMPLE_RATE_UNSPECIFIED)
                        .setEncoding(AudioFormat.ENCODING_DEFAULT)
                        .build();
            }
            try {
                // If the buffer size is not specified in streaming mode,
                // use a single frame for the buffer size and let the
                // native code figure out the minimum buffer size.
                if (mMode == MODE_STREAM && mBufferSizeInBytes == 0) {
                    mBufferSizeInBytes = mFormat.getChannelCount()
                            * mFormat.getBytesPerSample(mFormat.getEncoding());
                }
                final AudioTrack track = new AudioTrack(
                        mAttributes, mFormat, mBufferSizeInBytes, mMode, mSessionId);
                if (track.getState() == STATE_UNINITIALIZED) {
                    // release is not necessary
                    throw new UnsupportedOperationException("Cannot create AudioTrack");
                }
                return track;
            } catch (IllegalArgumentException e) {
                throw new UnsupportedOperationException(e.getMessage());
            }
        }
    }

    /**********************************
     * THESE WRITE METHODS CALL advanceNotificationMaker SO stop can ensure safety
     */

    /**
     *
     * @param audioData
     * @param offsetInBytes
     * @param sizeInBytes
     * @return
     */
    @Override
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        advanceNotificationMarker(sizeInBytes - offsetInBytes);
        safeToStop = false;
        return super.write(audioData, offsetInBytes, sizeInBytes);
    }

    @Override
    public int write(ByteBuffer audioData, int sizeInBytes, int writeMode) {
        Log.d(TAG, "Writing audio data");
        advanceNotificationMarker(sizeInBytes);
        safeToStop = false;
        return super.write(audioData, sizeInBytes, writeMode);
    }

    @Override
    public int write(short[] audioData, int offsetInShorts, int sizeInShorts) {
        advanceNotificationMarker(sizeInShorts - offsetInShorts);
        safeToStop = false;
        return super.write(audioData, offsetInShorts, sizeInShorts);
    }

    @Override
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
        Log.d(TAG, "Writing audio data");
        advanceNotificationMarker(sizeInBytes - offsetInBytes);
        safeToStop = false;
        return super.write(audioData, offsetInBytes, sizeInBytes, writeMode);
    }

    @Override
    public int write(ByteBuffer audioData, int sizeInBytes, int writeMode, long timestamp) {
        advanceNotificationMarker(sizeInBytes);
        safeToStop = false;
        return super.write(audioData, sizeInBytes, writeMode, timestamp);
    }

    @Override
    public int write(float[] audioData, int offsetInFloats, int sizeInFloats, int writeMode) {
        advanceNotificationMarker(sizeInFloats - offsetInFloats);
        safeToStop = false;
        return super.write(audioData, offsetInFloats, sizeInFloats, writeMode);
    }
}
