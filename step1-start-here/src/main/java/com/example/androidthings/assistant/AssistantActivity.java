/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;


import static android.content.ContentValues.TAG;

public class AssistantActivity extends Activity {
    private static CustomTTS ttsEngine;
    private static final int TTS_DATA_CHECKING = 0;
    public static MyAssistant myAssistant;//make sure this is initialized before initializing CustomTTS

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(ttsEngine == null) {
            //create an Intent
            Intent checkData = new Intent();
            //set it up to check for tts data
            checkData.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
            //start it so that it returns the result
            startActivityForResult(checkData, TTS_DATA_CHECKING);
        }
        Log.i(TAG, "starting assistant demo");

        setContentView(R.layout.activity_main);
        myAssistant = new MyAssistant(this);
    }

    /**
     * When CustomTTS is created, this handles the result
     *
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        //do they have the data
        switch (requestCode){
            case TTS_DATA_CHECKING:
                /*
                 * This checks if text to speach is installed. It was copied from:
                 * http://androidthings.blogspot.com/2012/01/android-text-to-speech-tts-basics.html
                 */
                if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                    //installed - go ahead and instantiate
                    Log.v(TAG, "TTS is installed...");
                    ttsEngine = new CustomTTS( this);
                }
                else {
                    //no data, prompt to install it
                    Intent promptInstall = new Intent();
                    promptInstall.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    startActivityForResult(promptInstall, TTS_DATA_CHECKING);
                    //you currently don't handle this because TextToSpeech.Engine doesn't have a
                    Log.w(TAG, "TTS wasn't installed, so I'm prompting for it.");
                }
                break;
            default:
                Log.i(TAG, "No matching activity callback for MainActivity." +
                        "onActivityResult");
                break;
        }
    }


    @Override
    public void onStop(){
        ttsEngine.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        myAssistant.kill();
        ttsEngine.shutdown();
        super.onDestroy();

    }
}
