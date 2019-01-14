package com.example.androidthings.assistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Locale;

import static android.content.ContentValues.TAG;

/**
 * This class adds a simpler speak method, and implements the TextToSpeech callbacks for its
 * tts member. Using this class should be as simple as initializing it, then calling its stop
 * method in onStop and its shutdown method in onDestroy. Currently the callbacks just log information,
 * but they can be made to do more...
 *
 * @see AssistantActivity#onStop()
 * @see AssistantActivity#onDestroy()
 * @see CustomTTS#stop()
 * @see CustomTTS#shutdown()
 * @see CustomTTS#speak(String)
 *
 * todo: make this a subclass of AssistantActivity.
 */

public class CustomTTS extends UtteranceProgressListener implements TextToSpeech.OnInitListener {

    private static final String UTTERANCE_ID =
            "com.example.androidthings.bluetooth.audio.UTTERANCE_ID";

    private boolean available = false;
    private boolean ttsRunning = false;

    private LinkedList<String> textToSpeehQueue;
    private static File myFile;

    private Runnable runSynthesizedFile = new Runnable() {
        @Override
        public void run() {
            //todo run the file
            //https://developer.android.com/reference/android/media/AudioTrack

            //creating a byte buffer from a file?
            // http://www.java2s.com/Code/Android/File/LoadsafiletoaByteBuffer.htm

            //mAudioTrack is defined in AssistantActivity, which this will be a sub class of.

            //AudioTrack.write: https://developer.android.com/reference/android/media/AudioTrack.html#write(java.nio.ByteBuffer,%20int,%20int)
            //AudioTrack.play: https://developer.android.com/reference/android/media/AudioTrack.html#play()
                //note, play throws an IllegalStateException



            textToSpeehQueue.remove();//do this last!
            ttsRunning = false;
            synthesizeNextFile();

        }
    };

    private TextToSpeech tts;//all the callbacks are linked to this

    private Handler mySpeakerHandler;

    /**
     * initializes the class so it can use text to speech
     * @param context
     */
    public CustomTTS(Context context, Handler speakerHandler){
        mySpeakerHandler = speakerHandler;
        this.tts = new TextToSpeech(context, this);
        if(myFile == null){
            try {
                File.createTempFile("tempSoundFile", ".wav");
                myFile.deleteOnExit();
                myFile.canRead();
                myFile.canWrite();
            }catch (IOException e){
                Log.e(TAG, "Error creating temp file", e);
            }
        }

    }


    /**
     * Checks if onInit has been called yet, to check if speak can be used yet.
     * @return
     * @see this#onInit(int)
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * @see AssistantActivity#onStop()
     */
    public void stop(){
        this.tts.stop();
    }

    /**
     * @see AssistantActivity#onDestroy()
     */
    public void shutdown(){
        this.tts.shutdown();
    }


    /**
     * This makes speaking much easier.
     * @param textToSpeak
     */
    public void speak(String textToSpeak){
        if(isAvailable()) {
            this.textToSpeehQueue.add(textToSpeak);
            synthesizeNextFile();
        }else{
            Log.e(TAG, "MainActivity.ttsEngine.speak(String) received text, but it's not done initializing yet.",
                    new IllegalStateException("eager beaver!"));

        }
    }

    /**
     * synthesizes the next text in the queue into the temp file.
     *
     * @see this#textToSpeehQueue
     */
    private void synthesizeNextFile(){
        if(!ttsRunning && !textToSpeehQueue.isEmpty()){
            Bundle params = new Bundle();
            //pre lolipop devices: https://stackoverflow.com/questions/34562771/how-to-save-audio-file-from-speech-synthesizer-in-android-android-speech-tts

            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID);
            tts.synthesizeToFile(textToSpeehQueue.pop(),null , myFile,UTTERANCE_ID);
            ttsRunning = true;
        }
    }

//********************************CALLBACKS*************************************

    /**
     *
     * @param utteranceId
     * @see UtteranceProgressListener#onStart(String)
     */

    @Override
    public void onStart(String utteranceId) {
        Log.i(TAG, "Text to speech engine started");
    }

    /**
     * Is called when synthesizeToFile finishes. Adds a runnable to the handler's queue
     * so the file can be spoken
     *
     * @param utteranceId
     * @see UtteranceProgressListener#onDone(String)
     * @see this#textToSpeehQueue
     * @see this#synthesizeNextFile()
     * @see this#runSynthesizedFile
     */
    @Override
    public void onDone(String utteranceId) {
        Log.i(TAG, "Text to speech synthesis done");
        mySpeakerHandler.post(this.runSynthesizedFile);

    }

    /**
     * According to this you need network connection.
     *
     * @param utteranceId
     * @see UtteranceProgressListener#onError(String, int)
     */
    @Override
    public void onError(String utteranceId, int errorCode) {
        switch(errorCode){
            case TextToSpeech.ERROR_INVALID_REQUEST:
                Log.e(TAG, "Text to speech: invalid request see https://developer.android.com/reference/android/speech/tts/TextToSpeech.html#ERROR_INVALID_REQUEST");
                break;
            case TextToSpeech.ERROR_NETWORK_TIMEOUT:
                Log.e(TAG, "Text to speech: network timeout see https://developer.android.com/reference/android/speech/tts/TextToSpeech.html#ERROR_NETWORK_TIMEOUT");
                break;
            case TextToSpeech.ERROR_OUTPUT:
                Log.e(TAG, "Text to speech: error output SEE https://developer.android.com/reference/android/speech/tts/TextToSpeech.html#ERROR_OUTPUT");
                break;
            case TextToSpeech.ERROR_NOT_INSTALLED_YET:
                Log.e(TAG, "Text to speech: not installed yet SEE https://developer.android.com/reference/android/speech/tts/TextToSpeech.html#ERROR_NOT_INSTALLED_YET");
                break;
            case TextToSpeech.ERROR_SYNTHESIS:
                Log.e(TAG, "Text to speech: synthesis error see https://developer.android.com/reference/android/speech/tts/TextToSpeech.html#ERROR_SYNTHESIS");
                break;
            case TextToSpeech.ERROR_SERVICE:
                Log.e(TAG, "Text to speech: service error see https://developer.android.com/reference/android/speech/tts/TextToSpeech.html#ERROR_SERVICE");
                break;
            case TextToSpeech.ERROR:
                Log.e(TAG, "Text to speech: general error see https://developer.android.com/reference/android/speech/tts/TextToSpeech.html#ERROR");
                break;
            case TextToSpeech.ERROR_NETWORK:
                Log.e(TAG, "Text to speech: network error see https://developer.android.com/reference/android/speech/tts/TextToSpeech.html#ERROR_NETWORK");
                break;
            default:
                Log.e(TAG, "Text to speech: unknown error");
        }
    }

    /**
     * according to the docs, this method is deprecated, but the compiler still requires it?
     *
     * https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener.html#onError(java.lang.String)
     * @param utteranceId
     */
    @Override
    public void onError(String utteranceId) {
        Log.e(TAG, "TextToSpeech: utterance error.");
    }

    /**
     *
     * @param utteranceId
     * @param audio
     * @see UtteranceProgressListener#onAudioAvailable(String, byte[])
     */
    @Override
    public void onAudioAvailable(String utteranceId, byte[] audio){
        super.onAudioAvailable(utteranceId, audio);
        Log.d(TAG, "Text to speech engine audio available");
    }



    /**
     * This method is Overriden from the TextToSpeech.OnInitListener interface.
     * It is called when the ttsEngine is initialized.
     * @param status
     * @see TextToSpeech.OnInitListener#onInit(int)
     */
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "Created text to speech engine");

            try {
                AudioAttributes.Builder audioAttributes = new AudioAttributes.Builder().
                        setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING).
                        setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).
                        setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
                this.tts.setAudioAttributes(audioAttributes.build());

                //Locale.ENGLISH
                Locale myLoc = new Locale("en", "US");
                //Locale myLoc = Locale.US;
                Locale.setDefault(myLoc);
                //I'm having an error where the local isn't set.
                // this sets the local: https://proandroiddev.com/change-language-programmatically-at-runtime-on-android-5e6bc15c758
                //I don't think this is why the speech isn't working.
                this.tts.setLanguage(myLoc);
                this.tts.setPitch(1f);
                this.tts.setSpeechRate(1f);

                available = true;

                //AudioPlaybackConfiguration
                speak("Hello world");
            } catch (Exception e) {
                Log.e(TAG, "Error creating CustomTTS", e);
            }
        } else {
            Log.w(TAG, "Could not open TTS Engine (onInit status=" + status + ")");
            //ttsEngine = null;
        }
    }
}
