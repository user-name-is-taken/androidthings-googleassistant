package com.example.androidthings.assistant;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;


import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidParameterException;
import java.util.Arrays;

import static android.content.ContentValues.TAG;

public class FilePlayer {

    private File atFile;
    private FileInputStream fin;
    private DataInputStream dis;
    private MyAudioTrack at;
    private AudioAttributes attributes;
    private int bufferSize;
    private AudioFormat af;
    private AudioDeviceInfo device;
    private byte[] header = new byte[44];


    /**
     * Don't forget to create at!
     * @param fileName
     */
    public FilePlayer(String fileName, AudioAttributes attributes, AudioDeviceInfo device){
        setFile(fileName);
        this.attributes = attributes;
        this.device = device;
    }


    /**
     * converts a byte array to an int in litte endian form
     * @param b
     * @return
     * @see <a href="https://stackoverflow.com/questions/5399798/byte-array-and-int-conversion-in-java">
     *     this stack overflow post</a>
     */
    public static int byteArrayToLeInt(byte[] b) {
        final ByteBuffer bb = ByteBuffer.wrap(b);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        return bb.getInt();
    }

    /**
     * @see //https://developer.android.com/reference/android/media/AudioTrack
     */
    private Runnable runSynthesizedFile = new Runnable() {
        @Override
        public void run() {
            playWav();
        }
    };

    public void playWavToHandler(Handler mHandler){
        mHandler.post(this.runSynthesizedFile);
    }


    /**
     * sets the file to draw sounds from
     *
     * you could probably set the size here too.
     * @param fileName creates a file from the file name
     */
    private void setFile( String fileName){
        this.atFile = new File(fileName);
    }

    /**
     * Code taken from here:
     * https://stackoverflow.com/questions/7372813/android-audiotrack-playing-wav-file-getting-only-white-noise
     */
    private void playWav(){
        Log.d(TAG, "Playing speech to text wav file");

        int i = 0;
        try {
            this.fin = new FileInputStream(this.atFile);
            this.dis = new DataInputStream(this.fin);
            Log.i(TAG, "file path is: " + this.atFile.getAbsolutePath());
            Log.i(TAG, "Number of bytes, fin: " + fin.available());
            Log.i(TAG, "Number of bytes, dis:" + dis.available());
            int readBytes = dis.read(header, 0, 44);
            if (readBytes != 44){
                Log.e(TAG, "Didn't read 44 bytes into the header");
            }
            Log.i(TAG, "For now, this is a note to self. The number of bytes in the file is: " +
                    getFileSize());
            //todo: make the number of bytes in this file the position marker
            setupBuffer();
            setupAudioTrack();
            actuallyPlay();
            dis.close();
            fin.close();
            this.atFile.delete();
        } catch (FileNotFoundException e) {
            // TODO
            e.printStackTrace();
        } catch (IOException e) {
            // TODO
            e.printStackTrace();
        }
    }


    private void actuallyPlay() throws IOException{
        this.at.play();
        int i = 0;
        byte[] s = new byte[this.bufferSize];
        while((i = dis.read(s, 0, this.bufferSize)) > -1){
            int status = this.at.write(s, 0,  i, AudioTrack.WRITE_BLOCKING);
            Log.v(TAG, "status: " + status + " data: "+ Arrays.toString(s));
        }
        Log.i(TAG, "done playing file!");
        this.at.stop();
        //at.release();
    }

    /**
     *
     * sets up this.af
     */
    private void setupAudioFormat(){
        AudioFormat.Builder afb = new AudioFormat.Builder()
                .setChannelMask(this.getChannel())
                .setEncoding(this.getEncoding())
                .setSampleRate(this.getSampleRate());
        this.af = afb.build();
    }

    private void setupBuffer(){
        this.bufferSize =
                AudioTrack.getMinBufferSize(getSampleRate(),
                        getChannel(),
                        getEncoding());
    }

    /**
     * Gets the size of a wav file after the header
     * from the header 40 - 44th header bytes bytes
     *
     * @return an int size of the file
     */
    private int getFileSize(){
        byte[] mBytes = {header[40], header[41], header[42], header[43]};
        int fileLen = byteArrayToLeInt(mBytes);
        Log.i(TAG, "Length of the file is: " + fileLen);
        return fileLen;
    }

    /**
     * calculates the channel mask from the number of channels given in the header
     *
     * @return the sample rate
     * @see <a href="https://developer.android.com/reference/android/media/AudioFormat">
     *     the AudioFormat docs for calculating this int</a>
     */
    private int getChannel(){
        byte[] mBytes = {this.header[22], this.header[23], 0, 0};
        int numChannels = byteArrayToLeInt(mBytes);
        return (1 << numChannels) -1;
    }

    /**
     * get the sample rate from the header
     *
     * @return the sample rate
     */
    private int getSampleRate(){
        byte [] mBytes = {header[24], header[25], 0, 0};
        int sampleRate = byteArrayToLeInt(mBytes);
        Log.i(TAG, "SampleRate is: " + sampleRate);
        return sampleRate;
    }

    /**
     * Converts a byte array to a string that can be printed where each byte
     * is printed in its hex form
     * @param br
     * @return
     */
    public static String byteArrayToString(byte[] br){
        String [] sr = new String[br.length];
        for (int i=0;i<br.length;i++){
            sr[i] = Integer.toHexString(br[i]);
        }
        return Arrays.toString(sr);
    }

    /**
     * gets the encoding from the header
     *
     * @see <a href="http://www-mmsp.ece.mcgill.ca/Documents/AudioFormats/WAVE/WAVE.html">Here for the different encodings.</a>
     * @return
     */
    private int getEncoding(){
        byte [] mBytes = {header[20], header[21]};
        int format = byteArrayToLeInt(mBytes);//the AudioFormat bytes
        // format type. 1-PCM, 3- IEEE float, 6 - 8bit A law, 7 - 8bit mu law
        switch (format){
            case 1:
                //1 is PCM, other values mean compression
                return bytesPerSample() == 8? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
            case 3:
                return AudioFormat.ENCODING_PCM_FLOAT;
            case 6:
                Log.e(TAG, "file uses 8bit A law, but unsure how to translate that to an AudioFormat");
                return AudioFormat.ENCODING_DEFAULT;
            case 7:
                Log.e(TAG, "File uses 8bit mu law, but unsure how to translate that to an AudioFormat");
                return AudioFormat.ENCODING_DEFAULT;
            default:
                throw new InvalidParameterException("unknown format in wav headers: " + format +
                        " The header array found was " + FilePlayer.byteArrayToString(header));
        }
    }

    /**
     * block align from the header is the number of bytes per sample.
     * @return
     */
    private int bytesPerSample(){
        byte[] mBytes = {header[20], header[21], 0, 0};
        int blockAlign = byteArrayToLeInt(mBytes);
        Log.i(TAG, "Bytes per sample is: " + blockAlign);
        return blockAlign;
    }

    /**
     * read AudioTrack parameters from header and use them
     */
    private void setupAudioTrack(){
        setupAudioFormat();

        MyAudioTrack.Builder ab = new MyAudioTrack.Builder();
        ab.setAudioAttributes(this.attributes);
        ab.setAudioFormat(this.af);
        this.at = ab.build();
        this.at.setPreferredDevice(this.device);
    }
}
