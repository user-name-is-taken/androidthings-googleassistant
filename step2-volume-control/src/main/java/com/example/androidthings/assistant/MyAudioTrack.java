package com.example.androidthings.assistant;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

import com.google.android.things.contrib.driver.voicehat.Max98357A;
import com.google.android.things.contrib.driver.voicehat.VoiceHat;
import com.google.common.primitives.Ints;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static android.content.ContentValues.TAG;

/**
 * This class overrides AudioTrack so it stays playing until all audio is done playing,
 * then closes itself when the audio is done playing.
 *
 * The class also
 */
public class MyAudioTrack extends AudioTrack {
    private static Max98357A mDac;
    private boolean safeToStop = true;
    private boolean stopWhenDone = false;
    //todo add a static default volume variable here

    private AudioTrack.OnPlaybackPositionUpdateListener listener = new OnPlaybackPositionUpdateListener (){
        @Override
        public void onMarkerReached(AudioTrack audioTrack) {
            Log.i(TAG, "MyAudioTrack has reached the marker");
            safeToStop = true;
            if(stopWhenDone) {
                release();//release calls stop, so you don't need to call stop here.
                Log.i(TAG, "Text to speech synthesis done");
            }

        }
        @Override
        public void onPeriodicNotification(AudioTrack audioTrack) {
            Log.d(TAG, "Playback head position: " + getPlaybackHeadPosition());
            //you're still trying to send data to this after the release

        }
    };



    /**
     * This initalizes the Dac if it's necessary, then calls the super constructor.
     * Also sets the OnPlaybackPositionUpdateListener to this.listener.
     *
     * @param streamType see super
     * @param sampleRateInHz see super
     * @param channelConfig see super
     * @param audioFormat see super
     * @param bufferSizeInBytes see super
     * @param mode see super
     * @throws IllegalArgumentException this comes from AudioTrack's constructor
     * @see AudioTrack#AudioTrack(int, int, int, int, int, int)
     * @see this#listener
     * @see AudioTrack#setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener)
     */
    private MyAudioTrack(
            int streamType, int sampleRateInHz, int channelConfig, int audioFormat,
            int bufferSizeInBytes, int mode) throws IllegalArgumentException {
        super(streamType, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes, mode);
        Log.i(TAG, "MyAudioTrack made!");
        if(MyAssistant.USE_VOICEHAT_DAC) {
            Log.i(TAG, "initializing DAC trigger");
            try {
                setmDac(VoiceHat.openDac());
                getmDac().setSdMode(Max98357A.SD_MODE_SHUTDOWN);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }else{
            Log.i(TAG, "Not using the Dac");
        }
        setPositionNotificationPeriod(sampleRateInHz);//gets notified once per second
        setPlaybackPositionUpdateListener(this.listener);
    }

    /**
     * Sets the period for the periodic notification event and logs the output
     * @param periodInFrames see super
     * @return see super
     * @see <a href="file:///home/michael/Android/Sdk/docs/reference/android/media/AudioTrack.html#setPositionNotificationPeriod(int)">
     *     the reference for the super method</a>
     */
    @Override
    public int setPositionNotificationPeriod(int periodInFrames) {
         switch (super.setPositionNotificationPeriod(periodInFrames)){
             case SUCCESS:
                 Log.i(TAG, "Period set success");
                 return SUCCESS;
             case ERROR_INVALID_OPERATION:
                 Log.i(TAG, "Period set invalid Operation");
                 return ERROR_INVALID_OPERATION;
             default:
                 throw new RuntimeException(
                         "setPositionNotificationPeriod's super method is behaving unexpectedly.");
         }
    }

    /**
     * just implements the build method, so I can build MyAudioTrack the same way AudioTrack is built.
     */
    public static class Builder extends AudioTrack.Builder{
        /**
         * Builds an AudioTrack then copies it into a MyAudioTrack.
         *
         * @return a new initialized MyAudioTrack.
         * @see super#Builder()#build()
         * @see <a href="https://developer.android.com/reference/android/media/AudioTrack.Builder.html#build()">
         *     The AudioTrack.Builder.build() docs</a>
         * @see MyAudioTrack#MyAudioTrack(AudioTrack)
         */
        @Override
        public MyAudioTrack build(){
            return new MyAudioTrack(super.build());
        }
    }

    /**
     * This is my best attempt at subclassing AudioTrack so I can still use the builder.
     *
     * @param audioTrack an AudioTrack to be converted to MyAudioTrack
     * @throws ArithmeticException if your understanding of bit depth is wrong.
     */
    public MyAudioTrack(AudioTrack audioTrack){
        this(
            audioTrack.getStreamType(), audioTrack.getSampleRate(),
            audioTrack.getChannelConfiguration(), audioTrack.getAudioFormat(),
            audioTrack.getBufferSizeInFrames() * bytesPerFrame(audioTrack.getAudioFormat()),
            MODE_STREAM
        );
        if( Math.abs(audioTrack.getBufferSizeInFrames() - getBufferSizeInFrames()) > 10){
            ArithmeticException badBitDepth =
                    new ArithmeticException("The code is wrong. You're misunderstanding bit depth.");
            Log.wtf(TAG, "Your code is just wrong!!! The audio track's " +
                    "buffer size in frames is: " + audioTrack.getBufferSizeInFrames() +
                    " my class's buffer size in frames is: " + getBufferSizeInFrames(), badBitDepth);
        }
    }

    /**
     * Tells you how many bytes there are per frame.
     *
     * frames and samples are the same thing. This is useful for setting periodic notifications,
     * and for the builder.
     *
     * @param format the encoding that you want to know the number of bytes of
     * @return the number of bytes per frame for the given encoding
     * @see <a href="https://developer.android.com/reference/android/media/AudioFormat#encoding">
     *     AudioFormat docs</a>
     * @see this#write(byte[], int, int, int)
     * @see this#write(ByteBuffer, int, int, long)
     * @see this#write(ByteBuffer, int, int)
     * @see this#write(byte[], int, int)
     * @see this#MyAudioTrack(AudioTrack)
     */
    public static int bytesPerFrame( int format ){
        switch(format){
            case AudioFormat.ENCODING_AC3:
                return 1;
            case AudioFormat.ENCODING_PCM_8BIT:
                return 1;
            case AudioFormat.ENCODING_PCM_16BIT:
                return 2;
            case AudioFormat.ENCODING_PCM_FLOAT:
                return 4;
            default:
                IllegalArgumentException badFormat = new IllegalArgumentException("Unknown bit depth");
                Log.e(TAG, "See MyAudioTrack.bytesPerFrame", badFormat);
                throw badFormat;
        }

    }



    /**
     * Gets the number of bytes in this
     *
     * @return the number of bytes per frame for the encoding of this object
     * @see <a href="https://developer.android.com/reference/android/media/AudioFormat#encoding">
     *     AudioFormat docs</a>
     * @see this#write(byte[], int, int, int)
     * @see this#write(ByteBuffer, int, int, long)
     * @see this#write(ByteBuffer, int, int)
     * @see this#write(byte[], int, int)
     * @see this#MyAudioTrack(AudioTrack)
     */
    public int bytesPerFrame(){
        return MyAudioTrack.bytesPerFrame( this.getAudioFormat() );
    }

    /**
     * There are 2 bytes in a short
     * @return
     * @see this#write(short[], int, int)
     * @see this#write(short[], int, int, int)
     */
    public float shortsPerFrame(){
        return bytesPerFrame()/2f;
    }

    /**
     * There are 4 bytes in a float
     * @return
     * @see this#write(float[], int, int, int)
     *
     */
    public float floatsPerFrame(){
        return bytesPerFrame()/4f;
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
        safeToStop = false;
        if (mDac != null) {
            Log.i(TAG, "enabling the dac");
            try {
                getmDac().setSdMode(Max98357A.SD_MODE_LEFT);
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
     * @see super#setNotificationMarkerPosition(int)
     * @see super#getNotificationMarkerPosition()
     */
    public void advanceNotificationMarker(int advancementFrames){
        int nextPos = advancementFrames + super.getNotificationMarkerPosition();
        Log.d(TAG, "Writing " + advancementFrames + " frames to the buffer");
        Log.d(TAG, "notification position now set to " + nextPos);
        Log.d(TAG, "Playback head position " + getPlaybackHeadPosition());
        super.setNotificationMarkerPosition( nextPos );
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
        Log.i(TAG, "MyAudioTrack trying to forceStop()");
        super.flush();
        //super.reloadStaticData();
        super.stop();
        if (mDac != null) {
            try {
                Log.i(TAG, "MyAudioTrack trying to kill dac");
                getmDac().setSdMode(Max98357A.SD_MODE_SHUTDOWN);
                Log.i(TAG, "MyAudioTrack successfully shut the Dac down");
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
        advanceNotificationMarker(sizeInBytes/bytesPerFrame());
        safeToStop = false;
        return super.write(audioData, offsetInBytes, sizeInBytes);
    }

    @Override
    public int write(ByteBuffer audioData, int sizeInBytes, int writeMode) {
        Log.d(TAG, "Writing " + sizeInBytes + " bytes of audio data");
        advanceNotificationMarker(sizeInBytes/bytesPerFrame());
        safeToStop = false;
        return super.write(audioData, sizeInBytes, writeMode);
    }

    @Override
    public int write(short[] audioData, int offsetInShorts, int sizeInShorts) {
        Log.d(TAG, "Writing " + sizeInShorts + " shorts of audio data");
        advanceNotificationMarker((int) (sizeInShorts / shortsPerFrame()));
        safeToStop = false;
        return super.write(audioData, offsetInShorts, sizeInShorts);
    }

    @Override
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
        Log.d(TAG, "Writing " + sizeInBytes + " of audio data.");
        advanceNotificationMarker( ( sizeInBytes / bytesPerFrame() ) );
        safeToStop = false;
        return super.write(audioData, offsetInBytes, sizeInBytes, writeMode);
    }

    @Override
    public int write(ByteBuffer audioData, int sizeInBytes, int writeMode, long timestamp) {
        Log.d(TAG, "Writing " + sizeInBytes + " bytes of audio data");
        advanceNotificationMarker(sizeInBytes/ bytesPerFrame() );
        safeToStop = false;
        return super.write(audioData, sizeInBytes, writeMode, timestamp);
    }

    @Override
    public int write(float[] audioData, int offsetInFloats, int sizeInFloats, int writeMode) {
        Log.d(TAG, "Writing " + sizeInFloats + " bytes of audio data");
        advanceNotificationMarker( (int) (sizeInFloats / floatsPerFrame()) );
        safeToStop = false;
        return super.write(audioData, offsetInFloats, sizeInFloats, writeMode);
    }
}
