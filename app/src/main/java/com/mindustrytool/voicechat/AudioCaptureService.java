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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Background service for capturing microphone audio and sending to Mindustry
 * mod.
 * Includes audio processing for better quality: noise suppression, echo
 * cancellation, AGC.
 * 
 * Now supports remote control commands from Mod:
 * - CMD_START_MIC (0x01): Start recording
 * - CMD_STOP_MIC (0x02): Stop recording (privacy protection)
 * - CMD_SHUTDOWN (0x03): Close the app
 */
public class AudioCaptureService extends Service {

    private static final String TAG = "AudioCaptureService";
    private static final String CHANNEL_ID = "voice_chat_channel";
    private static final int NOTIFICATION_ID = 1;

    // Control commands from Mod
    public static final byte CMD_START_MIC = 0x01;
    public static final byte CMD_STOP_MIC = 0x02;
    public static final byte CMD_SHUTDOWN = 0x03;

    // Audio configuration - optimized for voice
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = 960; // 20ms at 48kHz

    // Socket configuration
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 25566; // Mindustry mod listens on this port

    private static volatile boolean isRunning = false;
    private volatile boolean isMicActive = false; // Only record when true

    private AudioRecord audioRecord;
    private Thread captureThread;
    private Thread commandThread;
    private Socket socket;
    private OutputStream outputStream;
    private InputStream inputStream;

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

            // Critical Fix: Connect immediately to listen for commands (START_MIC)
            // Otherwise we wait for sendAudio which never happens if mic is inactive!
            new Thread(this::connectToMindustry).start();

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

            // Only send audio when mic is active (controlled by Mod)
            if (samplesRead > 0 && isMicActive) {
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

    /**
     * Listen for control commands from Mod (START_MIC, STOP_MIC, SHUTDOWN).
     */
    private void commandListenerLoop() {
        while (isRunning) {
            try {
                if (inputStream == null) {
                    Thread.sleep(100);
                    continue;
                }

                int cmd = inputStream.read();
                if (cmd < 0) {
                    // Connection closed
                    Log.w(TAG, "Command stream closed by Mod");
                    closeSocket();
                    Thread.sleep(1000); // Wait before retry
                    continue;
                }

                switch (cmd) {
                    case CMD_START_MIC:
                        Log.i(TAG, "Received CMD_START_MIC - mic activated");
                        isMicActive = true;
                        break;
                    case CMD_STOP_MIC:
                        Log.i(TAG, "Received CMD_STOP_MIC - mic paused (privacy mode)");
                        isMicActive = false;
                        break;
                    case CMD_SHUTDOWN:
                        Log.i(TAG, "Received CMD_SHUTDOWN - stopping service");
                        isMicActive = false;
                        stopSelf();
                        return;
                    default:
                        Log.w(TAG, "Unknown command: " + cmd);
                }
            } catch (java.net.SocketTimeoutException e) {
                // Timeout is OK
            } catch (Exception e) {
                Log.w(TAG, "Command listener error: " + e.getMessage());
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
            socket.setTcpNoDelay(true); // Disable Nagle's algorithm for lower latency
            socket.setSoTimeout(5000); // 5 second timeout
            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();
            Log.i(TAG, "Connected to Mindustry mod (TCP_NODELAY enabled, command channel ready)");

            // Start command listener if not already running
            if (commandThread == null || !commandThread.isAlive()) {
                commandThread = new Thread(this::commandListenerLoop);
                commandThread.setDaemon(true);
                commandThread.start();
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot connect to Mindustry mod: " + e.getMessage());
        }
    }

    private void closeSocket() {
        isMicActive = false; // Safety: stop mic on disconnect
        try {
            if (inputStream != null)
                inputStream.close();
            if (outputStream != null)
                outputStream.close();
            if (socket != null)
                socket.close();
        } catch (Exception e) {
            // Ignore
        }
        inputStream = null;
        outputStream = null;
        socket = null;
    }

    private void stopCapture() {
        isRunning = false;
        isMicActive = false;

        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }

        if (commandThread != null) {
            commandThread.interrupt();
            commandThread = null;
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
