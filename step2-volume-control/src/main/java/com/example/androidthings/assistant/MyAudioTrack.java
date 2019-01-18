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
    private boolean stopWhenDone = false;

    private AudioTrack.OnPlaybackPositionUpdateListener listener = new OnPlaybackPositionUpdateListener (){
        @Override
        public void onMarkerReached(AudioTrack audioTrack) {
            safeToStop = true;
            if(stopWhenDone) {
                stop();
                Log.i(TAG, "Text to speech synthesis done");
            }

        }
        @Override
        public void onPeriodicNotification(AudioTrack audioTrack) {
            //pass
        }
    };

    /**
     *
     * @param streamType
     * @param sampleRateInHz
     * @param channelConfig
     * @param audioFormat
     * @param bufferSizeInBytes
     * @param mode
     * @throws IllegalArgumentException
     */
    public MyAudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat,
                        int bufferSizeInBytes, int mode) throws IllegalArgumentException {
        super(streamType, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes, mode);
        Log.i(TAG, "MyAudioTrack made!");
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

    public static class Builder extends AudioTrack.Builder{
        public MyAudioTrack build(){
            return new MyAudioTrack(super.build());
        }
    }

    /**
     * This is my best attempt at subclassing AudioTrack so I can still use the builder.
     * I need to figure
     * @param audioTrack
     */
    public MyAudioTrack(AudioTrack audioTrack){
        this(audioTrack.getStreamType(), audioTrack.getSampleRate(),
                audioTrack.getChannelConfiguration(), audioTrack.getAudioFormat(),
                audioTrack.getBufferSizeInFrames(), MODE_STREAM);
        setBufferSizeInFrames(audioTrack.getBufferSizeInFrames());
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
        stopWhenDone = false;
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
            stopWhenDone = true;
            Log.i(TAG, "Tried to stop when it's not safe.");
        }
    }



    /**
     * forces the AudioTrack to stop without playing the remaining data in its buffer
     * @throws IllegalStateException
     */
    private void forceStop() throws IllegalStateException{
        super.flush();
        super.reloadStaticData();
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
