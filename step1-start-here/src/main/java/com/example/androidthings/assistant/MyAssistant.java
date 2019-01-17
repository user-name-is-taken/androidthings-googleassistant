package com.example.androidthings.assistant;

import android.app.Activity;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.asha.libresample2.Resample;
import com.example.androidthings.assistant.shared.BoardDefaults;
import com.example.androidthings.assistant.shared.Credentials;
import com.example.androidthings.assistant.shared.MyDevice;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.voicehat.Max98357A;
import com.google.android.things.contrib.driver.voicehat.VoiceHat;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManager;
import com.google.assistant.embedded.v1alpha2.AssistConfig;
import com.google.assistant.embedded.v1alpha2.AssistRequest;
import com.google.assistant.embedded.v1alpha2.AssistResponse;
import com.google.assistant.embedded.v1alpha2.AudioInConfig;
import com.google.assistant.embedded.v1alpha2.AudioOutConfig;
import com.google.assistant.embedded.v1alpha2.DeviceConfig;
import com.google.assistant.embedded.v1alpha2.DialogStateIn;
import com.google.assistant.embedded.v1alpha2.EmbeddedAssistantGrpc;
import com.google.assistant.embedded.v1alpha2.SpeechRecognitionResult;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Locale;
import android.media.AudioAttributes;
import android.os.Bundle;

public class MyAssistant implements Button.OnButtonEventListener {
    private Context context;
    public static ListView assistantRequestsListView;

    private static final String TAG = AssistantActivity.class.getSimpleName();

    // Peripheral and drivers constants.
    private static final boolean USE_VOICEHAT_DAC = true;
    private static final int BUTTON_DEBOUNCE_DELAY_MS = 20;

    // Audio constants.
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static AudioInConfig.Encoding ENCODING_INPUT = AudioInConfig.Encoding.LINEAR16;
    private static AudioOutConfig.Encoding ENCODING_OUTPUT = AudioOutConfig.Encoding.LINEAR16;
    private static final AudioInConfig ASSISTANT_AUDIO_REQUEST_CONFIG =
            AudioInConfig.newBuilder()
                    .setEncoding(ENCODING_INPUT)
                    .setSampleRateHertz(SAMPLE_RATE)
                    .build();
    private static final AudioOutConfig ASSISTANT_AUDIO_RESPONSE_CONFIG =
            AudioOutConfig.newBuilder()
                    .setEncoding(ENCODING_OUTPUT)
                    .setSampleRateHertz(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_OUT_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final int SAMPLE_BLOCK_SIZE = 1024;
    private int mOutputBufferSize;

    // Google Assistant API constants.
    private static final String ASSISTANT_ENDPOINT = "embeddedassistant.googleapis.com";

    // gRPC client and stream observers.
    private EmbeddedAssistantGrpc.EmbeddedAssistantStub mAssistantService;
    private StreamObserver<AssistRequest> mAssistantRequestObserver;

    private StreamObserver<AssistResponse> mAssistantResponseObserver =
            new StreamObserver<AssistResponse>() {
                @Override
                public void onNext(AssistResponse value) {
                    if (value.getEventType() != null) {
                        Log.d(TAG, "converse response event: " + value.getEventType());
                    }
                    if (value.getSpeechResultsList() != null && value.getSpeechResultsList().size() > 0) {
                        for (SpeechRecognitionResult result : value.getSpeechResultsList()) {
                            final String spokenRequestText = result.getTranscript();
                            if (!spokenRequestText.isEmpty()) {
                                Log.i(TAG, "assistant request text: " + spokenRequestText);
                                mMainHandler.post(() -> mAssistantRequestsAdapter.add(spokenRequestText));
                            }
                        }
                    }
                    if (value.getDialogStateOut() != null) {
                        int volume = value.getDialogStateOut().getVolumePercentage();
                        if(volume > 0){
                            mVolumePercentage = volume;
                            Log.i(TAG, "assistant volume changed: " + mVolumePercentage);
                            float vol = AudioTrack.getMaxVolume() * mVolumePercentage / 100.0f;
                            myTTS.speak("Volume set");
                            mAudioTrack.setVolume(vol);
                            myTTS.setVolume(vol/100.0f);

                        }
                        mConversationState = value.getDialogStateOut().getConversationState();
                    }
                    if (value.getAudioOut() != null) {
                        final ByteBuffer audioData =
                                ByteBuffer.wrap(value.getAudioOut().getAudioData().toByteArray());
                        Log.d(TAG, "converse audio size: " + audioData.remaining());
                        mAssistantResponses.add(audioData);
                    }
                    if (value.getDeviceAction() != null &&
                            !value.getDeviceAction().getDeviceRequestJson().isEmpty()) {
                        // Iterate through JSON object
                        try {
                            JSONObject deviceAction =
                                    new JSONObject(value.getDeviceAction().getDeviceRequestJson());
                            JSONArray inputs = deviceAction.getJSONArray("inputs");
                            for (int i = 0; i < inputs.length(); i++) {
                                if (inputs.getJSONObject(i).getString("intent")
                                        .equals("action.devices.EXECUTE")) {
                                    JSONArray commands = inputs.getJSONObject(i)
                                            .getJSONObject("payload")
                                            .getJSONArray("commands");
                                    for (int j = 0; j < commands.length(); j++) {
                                        JSONArray execution = commands.getJSONObject(j)
                                                .getJSONArray("execution");
                                        for (int k = 0; k < execution.length(); k++) {
                                            String command = execution.getJSONObject(k)
                                                    .getString("command");
                                            JSONObject params = execution.getJSONObject(k)
                                                    .optJSONObject("params");
                                            handleDeviceAction(command, params);
                                        }
                                    }
                                }
                            }
                        } catch (JSONException | IOException e) {
                            e.printStackTrace();
                        }
                    }

                }

                @Override
                public void onError(Throwable t) {
                    Log.e(TAG, "converse error:", t);
                }

                @Override
                public void onCompleted() {
                    mAudioTrack = new AudioTrack.Builder()
                            .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                            .setBufferSizeInBytes(mOutputBufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    //todo check if you need to set volume like this
                    float vol = AudioTrack.getMaxVolume() * mVolumePercentage / 100.0f;
                    mAudioTrack.setVolume(vol);
                    //need to maintain volume across these objects?
                    if (mAudioOutputDevice != null) {
                        mAudioTrack.setPreferredDevice(mAudioOutputDevice);
                    }
                    mAudioTrack.play();
                    if (mDac != null) {
                        try {
                            mDac.setSdMode(Max98357A.SD_MODE_LEFT);
                        } catch (IOException e) {
                            Log.e(TAG, "unable to modify dac trigger", e);
                        }
                    }
                    for (ByteBuffer audioData : mAssistantResponses) {
                        final ByteBuffer buf = audioData;
                        Log.d(TAG, "Playing a bit of audio");
                        mAudioTrack.write(buf, buf.remaining(),
                                AudioTrack.WRITE_BLOCKING);

                        //todo: according to this https://developer.android.com/reference/android/media/AudioTrack#play()
                        //write is where audio to be played is determined.
                    }
                    mAssistantResponses.clear();
                    mAudioTrack.stop();
                    if (mDac != null) {
                        try {
                            mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);
                        } catch (IOException e) {
                            Log.e(TAG, "unable to modify dac trigger", e);
                        }
                    }

                    Log.i(TAG, "assistant response finished");
                    if (mLed != null) {
                        try {
                            mLed.setValue(false);
                        } catch (IOException e) {
                            Log.e(TAG, "error turning off LED:", e);
                        }
                    }
                }
            };

    public void handleDeviceAction(String command, JSONObject params)
            throws JSONException, IOException {
        if (command.equals("action.devices.commands.OnOff")) {
            //mLed.setValue(params.getBoolean("on"));
            Log.i(TAG, "Turning device on!!!!");
        }
    }

    // Audio playback and recording objects.
    private AudioTrack mAudioTrack;
    private AudioRecord mAudioRecord;

    // Audio routing configuration: use default routing.
    private AudioDeviceInfo mAudioInputDevice;
    private AudioDeviceInfo mAudioOutputDevice;

    public AudioDeviceInfo getOutputDevInfo(){
        return this.mAudioOutputDevice;
    }

    // Hardware peripherals.
    private Button mButton;
    private Gpio mLed;
    private Max98357A mDac;

    // Assistant Thread and Runnables implementing the push-to-talk functionality.
    private ByteString mConversationState = null;
    private HandlerThread mAssistantThread;
    private Handler mAssistantHandler;
    private ArrayList<ByteBuffer> mAssistantResponses = new ArrayList<>();


    private Runnable mStartAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "starting assistant request");
            mAudioRecord.startRecording();
            mAssistantRequestObserver = mAssistantService.assist(mAssistantResponseObserver);

            AssistConfig.Builder converseConfigBuilder = AssistConfig.newBuilder()
                    .setAudioInConfig(ASSISTANT_AUDIO_REQUEST_CONFIG)
                    .setAudioOutConfig(AudioOutConfig.newBuilder()
                            .setEncoding(ENCODING_OUTPUT)
                            .setSampleRateHertz(SAMPLE_RATE)
                            .setVolumePercentage(mVolumePercentage)
                            .build())
                    .setDeviceConfig(DeviceConfig.newBuilder()
                            .setDeviceModelId(MyDevice.MODEL_ID)
                            .setDeviceId(MyDevice.INSTANCE_ID)
                            .build());

            DialogStateIn.Builder dialogStateInBuilder = DialogStateIn.newBuilder()
                    .setLanguageCode(MyDevice.LANGUAGE_CODE);
            if (mConversationState != null) {
                dialogStateInBuilder.setConversationState(mConversationState);
            }
            converseConfigBuilder.setDialogStateIn(dialogStateInBuilder.build());

            mAssistantRequestObserver.onNext(
                    AssistRequest.newBuilder()
                            .setConfig(converseConfigBuilder.build())
                            .build());

            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private Runnable mStreamAssistantRequest = new Runnable() {
        @Override
        public void run() {
            ByteBuffer audioData = ByteBuffer.allocateDirect(SAMPLE_BLOCK_SIZE);
            if (mAudioInputDevice != null) {
                mAudioRecord.setPreferredDevice(mAudioInputDevice);
            }
            int result =
                    mAudioRecord.read(audioData, audioData.capacity(), AudioRecord.READ_BLOCKING);
            if (result < 0) {
                Log.e(TAG, "error reading from audio stream:" + result);
                return;
            }
            Log.d(TAG, "streaming ConverseRequest: " + result);
            mAssistantRequestObserver.onNext(AssistRequest.newBuilder()
                    .setAudioIn(ByteString.copyFrom(audioData))
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private Runnable mStopAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "ending assistant request");
            mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            if (mAssistantRequestObserver != null) {
                mAssistantRequestObserver.onCompleted();
                mAssistantRequestObserver = null;
            }
            mAudioRecord.stop();
            mAudioTrack.play();//todo: is this a better text to speech?
        }
    };

    // List & adapter to store and display the history of Assistant Requests.
    private ArrayList<String> mAssistantRequests = new ArrayList<>();
    private ArrayAdapter<String> mAssistantRequestsAdapter;
    private static int mVolumePercentage = 100;

    private Handler mMainHandler;
    public CustomTTS myTTS;

    public MyAssistant(Activity context){
        this.context = context;
        mAssistantRequestsAdapter =
                new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1,
                        mAssistantRequests);

        mMainHandler = new Handler(context.getMainLooper());

        mAssistantThread = new HandlerThread("assistantThread");
        mAssistantThread.start();
        mAssistantHandler = new Handler(mAssistantThread.getLooper());
        assistantRequestsListView = ((Activity)context).findViewById(R.id.assistantRequestsListView);

        assistantRequestsListView.setAdapter(mAssistantRequestsAdapter);

        // Use I2S with the Voice HAT.
        if (USE_VOICEHAT_DAC) {
            Log.d(TAG, "enumerating devices");
            //TODO change this back to TYPE_BUS for I2S
            //https://github.com/androidthings/sample-googleassistant
            mAudioInputDevice = findAudioDevice(AudioManager.GET_DEVICES_INPUTS,
                    AudioDeviceInfo.TYPE_BUS);
            if (mAudioInputDevice == null) {
                Log.e(TAG, "failed to found preferred audio input device, using default");
            }
            mAudioOutputDevice = findAudioDevice(AudioManager.GET_DEVICES_OUTPUTS,
                    AudioDeviceInfo.TYPE_BUS);
            if (mAudioOutputDevice == null) {
                Log.e(TAG, "failed to found preferred audio output device, using default");
            }
        }else{
            mAudioOutputDevice = findAudioDevice(AudioManager.GET_DEVICES_OUTPUTS,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            if (mAudioOutputDevice == null) {
                Log.e(TAG, "failed to found preferred audio output device, using default");
            }
        }


        try {
            if (USE_VOICEHAT_DAC) {
                Log.i(TAG, "initializing DAC trigger");
                mDac = VoiceHat.openDac();
                mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);

                mButton = VoiceHat.openButton();
                mLed = VoiceHat.openLed();
            } else {
                mButton = new Button(BoardDefaults.getGPIOForButton(),
                        Button.LogicState.PRESSED_WHEN_LOW);
                mLed = PeripheralManager.getInstance().openGpio(BoardDefaults.getGPIOForLED());
            }

            mButton.setDebounceDelay(BUTTON_DEBOUNCE_DELAY_MS);
            mButton.setOnButtonEventListener(this);

            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLed.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            Log.e(TAG, "error configuring peripherals:", e);
            return;
        }

        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        int maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Log.i(TAG, "setting volume to: " + maxVolume);
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
        mOutputBufferSize = AudioTrack.getMinBufferSize(AUDIO_FORMAT_OUT_MONO.getSampleRate(),
                AUDIO_FORMAT_OUT_MONO.getChannelMask(),
                AUDIO_FORMAT_OUT_MONO.getEncoding());
        mAudioTrack = new AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(mOutputBufferSize)
                .build();
        mAudioTrack.play();
        int inputBufferSize = AudioRecord.getMinBufferSize(AUDIO_FORMAT_STEREO.getSampleRate(),
                AUDIO_FORMAT_STEREO.getChannelMask(),
                AUDIO_FORMAT_STEREO.getEncoding());
        mAudioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AUDIO_FORMAT_IN_MONO)
                .setBufferSizeInBytes(inputBufferSize)
                .build();

        ManagedChannel channel = ManagedChannelBuilder.forTarget(ASSISTANT_ENDPOINT).build();
        try {
            mAssistantService = EmbeddedAssistantGrpc.newStub(channel)
                    .withCallCredentials(MoreCallCredentials.from(
                            Credentials.fromResource(context, R.raw.credentials)
                    ));
        } catch (IOException|JSONException e) {
            Log.e(TAG, "error creating assistant service:", e);
        }

        myTTS = new CustomTTS();
    }

    private AudioDeviceInfo findAudioDevice(int deviceFlag, int deviceType) {
        AudioManager manager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] adis = manager.getDevices(deviceFlag);
        for (AudioDeviceInfo adi : adis) {
            Log.i(TAG, "product name: " + adi.getProductName());
            Log.i(TAG, "type: " + adi.getType());

            if (adi.getType() == deviceType) {
                return adi;
            }
        }
        return null;
    }

    @Override
    public void onButtonEvent(Button button, boolean pressed) {
        try {
            if (mLed != null) {
                mLed.setValue(pressed);
            }
        } catch (IOException e) {
            Log.d(TAG, "error toggling LED:", e);
        }
        if (pressed) {
            mAssistantHandler.post(mStartAssistantRequest);
        } else {
            mAssistantHandler.post(mStopAssistantRequest);
        }
    }

    public void stop(){
        this.myTTS.stop();
    }

    public void kill(){
        Log.i(TAG, "destroying assistant demo");
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord = null;
        }
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
        if (mLed != null) {
            try {
                mLed.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing LED", e);
            }
            mLed = null;
        }
        if (mButton != null) {
            try {
                mButton.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing button", e);
            }
            mButton = null;
        }
        if (mDac != null) {
            try {
                mDac.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing voice hat trigger", e);
            }
            mDac = null;
        }
        mAssistantHandler.post(() -> mAssistantHandler.removeCallbacks(mStreamAssistantRequest));
        myTTS.shutdown();
        mAssistantThread.quitSafely();
    }


    /********************text to speech class!!!!!************************
     */

    /**
     *
     * @see <a href="https://stackoverflow.com/questions/54207935/what-paramaters-does-androids-audiotrack-need-to-play-the-output-of-texttospeec?noredirect=1#comment95270962_54207935">
     *     my stack overflow post about this</a>
     */
    public class CustomTTS extends UtteranceProgressListener implements TextToSpeech.OnInitListener {

        private static final int TTS_SAMPLE_RATE = 22050;
        private static final String TTS_ENGINE = "com.svox.pico";

        private static final String UTTERANCE_ID =
                "com.example.androidthings.bluetooth.audio.UTTERANCE_ID";

        private boolean available = false;
        private boolean ttsRunning = false;
        private Resample resample;


        private LinkedList<String> textToSpeehQueue;
        private File myFile;
        private FileInputStream fin;
        private DataInputStream dis;
        private static final int BUFFER_SIZE = 512;

        private AudioAttributes attributes;
        private AudioTrack at;
        private AudioTrack.Builder atBuilder;

        /**
         * @see //https://developer.android.com/reference/android/media/AudioTrack
         */
        private Runnable runSynthesizedFile = new Runnable() {
            @Override
            public void run() {

                playWav();
                textToSpeehQueue.remove();//do this last!
                ttsRunning = false;
                synthesizeNextFile();

            }
        };



        /**
         * Gets the size of a wav file from the header (the 40 - 44th bytes)
         *
         * @return an int size of the file
         */
        private int getFileSize(){
            byte[] fileSize = new byte[4];
            try {
                dis.read(fileSize, 40, 4);
            } catch (IOException e) {
                e.printStackTrace();
            }
            int fileLen = Ints.fromByteArray(fileSize);
            Log.i(TAG, "Length of the file is: " + fileLen);
            return fileLen;
        }

        /**
         * Changes the file from 22050 to 16000
         * @return a ByteBuffer for the resampled file
         */
        private ByteBuffer resampleFile(){
            Log.d(TAG, "Resampling");

            byte[] mikeByte = new byte[getFileSize()];
            ByteBuffer in = ByteBuffer.wrap(mikeByte, 44, mikeByte.length);

            ByteBuffer out = ByteBuffer.allocate(mikeByte.length);
            resample.resample(in, out, mikeByte.length);//returns an int?
            return out;
        }
        /**
         * Code taken from here:
         * https://stackoverflow.com/questions/7372813/android-audiotrack-playing-wav-file-getting-only-white-noise
         */
        private void playWav(){
            Log.d(TAG, "Playing speech to text wav file");

            int i = 0;
            byte[] s = new byte[BUFFER_SIZE];
            try {
                this.fin = new FileInputStream(this.myFile);
                this.dis = new DataInputStream(this.fin);
                Log.i(TAG, "file path is: " + this.myFile.getAbsolutePath());
                Log.i(TAG, "Number of bytes, fin: " + fin.available());
                Log.i(TAG, "Number of bytes, dis:" + dis.available());
                //ByteBuffer audio = resampleFile();
                this.at =
                        .build();
                at.play();
                if (mDac != null) {
                    try {
                        mDac.setSdMode(Max98357A.SD_MODE_LEFT);
                    } catch (IOException e) {
                        Log.e(TAG, "unable to modify dac trigger", e);
                    }
                }

                while((i = dis.read(s, 0, BUFFER_SIZE)) > -1){
                    int status = at.write(s, 0,  i, AudioTrack.WRITE_BLOCKING);
                    Log.v(TAG, "status: " + status + " data: "+ Arrays.toString(s));
                }
                Log.i(TAG, "done playing file!");
                at.stop();
                //at.release();
                dis.close();
                fin.close();
                //https://stackoverflow.com/questions/12347823/how-to-reopen-a-file-from-a-input-stream
                if (mDac != null) {
                    try {
                        mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);
                    } catch (IOException e) {
                        Log.e(TAG, "unable to modify dac trigger", e);
                    }
                }
                //AudioTrack.write: https://developer.android.com/reference/android/media/AudioTrack.html#write(java.nio.ByteBuffer,%20int,%20int)
                //AudioTrack.play: https://developer.android.com/reference/android/media/AudioTrack.html#play()

            } catch (FileNotFoundException e) {
                // TODO
                e.printStackTrace();
            } catch (IOException e) {
                // TODO
                e.printStackTrace();
            }
        }

        private TextToSpeech tts;//all the callbacks are linked to this


        /**
         * initializes the class so it can use text to speech
         */
        public CustomTTS(){
            AudioAttributes.Builder audioAttributesBuilder = new AudioAttributes.Builder().
                    setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING).
                    setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).
                    setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
            attributes = audioAttributesBuilder.build();
            this.textToSpeehQueue = new LinkedList<>();

            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            resample = new Resample();
            resample.create(TTS_SAMPLE_RATE, SAMPLE_RATE, minBufferSize, 1);

            atBuilder = new AudioTrack.Builder();

            AudioFormat.Builder afBuilder = new AudioFormat.Builder();

            afBuilder.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(TTS_SAMPLE_RATE);


            atBuilder
                    //.setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                    .setAudioFormat(afBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBufferSize)
                    .setAudioAttributes(attributes);

            //at = this.atBuilder.build();
            at.setPreferredDevice(MyAssistant.this.mAudioOutputDevice);
            this.setVolume(1.0f);

            //todo: you might need to specify the TTS engine so you can pass the encoding when you synthesize the file
            //https://developer.android.com/reference/android/speech/tts/TextToSpeech#TextToSpeech(android.content.Context,%20android.speech.tts.TextToSpeech.OnInitListener,%20java.lang.String)

            this.tts = new TextToSpeech(MyAssistant.this.context, this, TTS_ENGINE);
            if(myFile == null){
                try {
                    myFile = File.createTempFile("tempSoundFile", ".wav");
                    myFile.deleteOnExit();
                    myFile.setWritable(true);
                    myFile.setReadable(true);
                }catch (FileNotFoundException e){
                    Log.e(TAG, "File not found in constructor!!!", e);
                }catch (IOException e){
                    Log.e(TAG, "Error creating temp file", e);
                }
            }
        }//end constructor


        /**
         * Checks if onInit has been called yet, to check if the tts has been initialized
         * and speak can be used.
         *
         * @return true if the tts has been initialized and speak can be used.
         * @see this#onInit(int)
         * @see this#speak(String)
         */
        public boolean isAvailable() {
            return this.available;
        }

        /**
         * setVolume really needs to be between 0 and 1
         *
         * @param vol a float between 0 and 1 that sets the volume
         */
        public void setVolume(float vol){
            at.setVolume(vol);
        }

        /**
         * todo: check if you have to restart this in the next onCreate
         * @see AssistantActivity#onStop()
         */
        public void stop(){
            this.tts.stop();
        }

        /**
         * todo: check if you have to restart this in the next onCreate
         * @see AssistantActivity#onDestroy()
         */
        public void shutdown(){
            this.myFile.delete();//todo: should this be here???
            this.tts.stop();
            this.tts.shutdown();
        }


        /**
         * This makes speaking much easier.
         * @param textToSpeak
         */
        public void speak(String textToSpeak){
            if(this.isAvailable()) {
                Log.d(TAG, "Text to speech: inside speak");
                this.textToSpeehQueue.add(textToSpeak);
                synthesizeNextFile();
            }else{
                Log.e(TAG, "MainActivity.ttsEngine.speak(String) received text, but it's not done initializing yet.",
                        new IllegalStateException("eager beaver!"));
            }
        }

        /**
         * synthesizes the next text in the queue into the temp file if a file isn't currently being spoken.
         *
         * When this synthesis is done, onDone is called.
         *
         * @see this#textToSpeehQueue
         * @see this#onDone(String)
         */
        private void synthesizeNextFile(){
            if(!ttsRunning && !textToSpeehQueue.isEmpty()){
                ttsRunning = true;
                Bundle params = new Bundle();
                //pre lolipop devices: https://stackoverflow.com/questions/34562771/how-to-save-audio-file-from-speech-synthesizer-in-android-android-speech-tts
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID);
                //You were explicitly setting the engine here. You should add that in.
                tts.synthesizeToFile(textToSpeehQueue.peekFirst(), params, myFile, UTTERANCE_ID);
            }else{
                if(!ttsRunning){
                    Log.e(TAG, "Error in synthesizeNextFile", new IllegalStateException("ttsRunning is true." +
                            " Can't synthesize to a file before the last text is spoken."));
                }else if(!textToSpeehQueue.isEmpty()){
                    Log.e(TAG, "Error in synthesizeNextFile", new IllegalStateException("The " +
                            "textToSpeechQueue is empty so there's no text to speak."));
                }
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
         * Is called when synthesizeToFile finishes. Adds a runSynthesizedFile
         * runnable to the handler's queue so the file can be spoken.
         *
         * @param utteranceId
         * @see this#onDone(String)
         * @see UtteranceProgressListener
         * @see this#textToSpeehQueue
         * @see this#synthesizeNextFile()
         * @see this#runSynthesizedFile
         */
        @Override
        public void onDone(String utteranceId) {
            Log.i(TAG, "Text to speech synthesis done");
            MyAssistant.this.mAssistantHandler.post(this.runSynthesizedFile);
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
        }//end onError

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
         * Simply logs the fact that audio is available to be spoken
         *
         * @param utteranceId the ID of the audio to be spoken
         * @param audio the byte array of the audio.
         *        todo: you might be able to play this audio directly with AudioTrack
         * @see UtteranceProgressListener#onAudioAvailable(String, byte[])
         * @see this#speak(String)
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
                    this.tts.setAudioAttributes(attributes);

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
                    this.tts.setOnUtteranceProgressListener(this);
                    available = true;
                } catch (Exception e) {
                    Log.e(TAG, "Error creating Custom TTS", e);
                }
                speak("Hello world");
                //AudioPlaybackConfiguration
            } else {
                Log.w(TAG, "Could not open TTS Engine (onInit status=" + status + ")");
                //ttsEngine = null;
            }
        }//end onInit
    }



}
