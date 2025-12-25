package com.mindustrytool.voicechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.OutputStream;
import java.net.Socket;

/**
 * Background service for capturing microphone audio and sending to Mindustry
 * mod.
 * Includes audio processing for better quality: noise suppression, echo
 * cancellation, AGC.
 */
public class AudioCaptureService extends Service {

    private static final String TAG = "AudioCaptureService";
    private static final String CHANNEL_ID = "voice_chat_channel";
    private static final int NOTIFICATION_ID = 1;

    // Audio configuration - optimized for voice
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = 960; // 20ms at 48kHz

    // Socket configuration
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 25566; // Mindustry mod listens on this port

    private static volatile boolean isRunning = false;

    private AudioRecord audioRecord;
    private Thread captureThread;
    private Socket socket;
    private OutputStream outputStream;

    // Audio processors for better quality
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;
    private AutomaticGainControl agc;

    public static boolean isRunning() {
        return isRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        startCapture();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopCapture();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Voice Chat",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Voice Chat Companion is capturing audio");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Voice Chat Active")
                .setContentText("Capturing audio for Mindustry")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void startCapture() {
        if (isRunning)
            return;
        isRunning = true;

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        // Use larger buffer for smoother audio
        int bufferSize = Math.max(minBufferSize * 2, BUFFER_SIZE * 4);

        try {
            // Use VOICE_COMMUNICATION for better voice quality and built-in processing
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized, falling back to MIC source");
                // Fallback to regular MIC source
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize);

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord still not initialized");
                    stopSelf();
                    return;
                }
            }

            // Enable audio processing effects for better quality
            int audioSessionId = audioRecord.getAudioSessionId();
            enableAudioProcessing(audioSessionId);

            audioRecord.startRecording();
            Log.i(TAG, "Audio capture started with session ID: " + audioSessionId);

            captureThread = new Thread(this::captureLoop);
            captureThread.start();

        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied: " + e.getMessage());
            stopSelf();
        }
    }

    /**
     * Enable Android's built-in audio processing for better voice quality.
     */
    private void enableAudioProcessing(int audioSessionId) {
        // Noise Suppressor - reduces background noise
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                    Log.i(TAG, "NoiseSuppressor enabled");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable NoiseSuppressor: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "NoiseSuppressor not available on this device");
        }

        // Acoustic Echo Canceler - reduces echo
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                    Log.i(TAG, "AcousticEchoCanceler enabled");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable AcousticEchoCanceler: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "AcousticEchoCanceler not available on this device");
        }

        // Automatic Gain Control - normalizes volume
        if (AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(audioSessionId);
                if (agc != null) {
                    agc.setEnabled(true);
                    Log.i(TAG, "AutomaticGainControl enabled");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable AutomaticGainControl: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "AutomaticGainControl not available on this device");
        }
    }

    /**
     * Release audio processing effects.
     */
    private void releaseAudioProcessing() {
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.release();
            } catch (Exception e) {
                // Ignore
            }
            noiseSuppressor = null;
        }

        if (echoCanceler != null) {
            try {
                echoCanceler.release();
            } catch (Exception e) {
                // Ignore
            }
            echoCanceler = null;
        }

        if (agc != null) {
            try {
                agc.release();
            } catch (Exception e) {
                // Ignore
            }
            agc = null;
        }
    }

    private void captureLoop() {
        short[] buffer = new short[BUFFER_SIZE];
        byte[] byteBuffer = new byte[BUFFER_SIZE * 2];

        while (isRunning && audioRecord != null) {
            int samplesRead = audioRecord.read(buffer, 0, BUFFER_SIZE);

            if (samplesRead > 0) {
                // Convert shorts to bytes (little endian)
                for (int i = 0; i < samplesRead; i++) {
                    byteBuffer[i * 2] = (byte) (buffer[i] & 0xFF);
                    byteBuffer[i * 2 + 1] = (byte) ((buffer[i] >> 8) & 0xFF);
                }

                // Send to Mindustry mod
                sendAudio(byteBuffer, samplesRead * 2);
            }
        }
    }

    private void sendAudio(byte[] data, int length) {
        try {
            if (socket == null || !socket.isConnected()) {
                connectToMindustry();
            }

            if (outputStream != null) {
                // Send length prefix then data
                outputStream.write((length >> 8) & 0xFF);
                outputStream.write(length & 0xFF);
                outputStream.write(data, 0, length);
                outputStream.flush();
            }
        } catch (Exception e) {
            // Connection lost, will retry next packet
            Log.w(TAG, "Send failed: " + e.getMessage());
            closeSocket();
        }
    }

    private void connectToMindustry() {
        try {
            socket = new Socket(HOST, PORT);
            outputStream = socket.getOutputStream();
            Log.i(TAG, "Connected to Mindustry mod");
        } catch (Exception e) {
            Log.w(TAG, "Cannot connect to Mindustry mod: " + e.getMessage());
        }
    }

    private void closeSocket() {
        try {
            if (outputStream != null)
                outputStream.close();
            if (socket != null)
                socket.close();
        } catch (Exception e) {
            // Ignore
        }
        outputStream = null;
        socket = null;
    }

    private void stopCapture() {
        isRunning = false;

        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }

        // Release audio processing effects
        releaseAudioProcessing();

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        closeSocket();
        Log.i(TAG, "Audio capture stopped");
    }
}
