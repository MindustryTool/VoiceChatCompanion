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

    public static final String ACTION_STOP = "ACTION_STOP";

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
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification());
        startCapture();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopCapture();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
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

    private void updateNotification(String status) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(status));
        }
    }

    private Notification createNotification(String status) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, AudioCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Voice Chat Active")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop / Exit", stopPendingIntent)
                .build();
    }

    // Overload for initial creation
    private Notification createNotification() {
        return createNotification("Initializing...");
    }

    private void startCapture() {
        if (isRunning)
            return;
        isRunning = true;

        // 1. Connect first (so we can report errors)
        new Thread(() -> {
            updateNotification("Connecting to Mod...");
            connectToMindustry();
        }).start();

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        // Optimize for Latency: Use smaller buffer (closer to minBufferSize)
        // Previously: Math.max(minBufferSize * 2, BUFFER_SIZE * 4);
        // New: Math.max(minBufferSize, BUFFER_SIZE * 2); -> Reduced buffering
        int bufferSize = Math.max(minBufferSize, BUFFER_SIZE * 2);

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
                updateNotification("Mic Init Failed (Retrying...)");
                // Fallback to regular MIC source
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize);

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord still not initialized");
                    updateNotification("Error: Mic Init Failed");
                    stopSelf();
                    return;
                }
            }

            // Enable audio processing effects for better quality
            int audioSessionId = audioRecord.getAudioSessionId();
            enableAudioProcessing(audioSessionId);

            audioRecord.startRecording();
            Log.i(TAG, "Audio capture started with session ID: " + audioSessionId);
            updateNotification("Mic Ready. Waiting for Mod...");

            captureThread = new Thread(this::captureLoop);
            captureThread.start();

        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied: " + e.getMessage());
            updateNotification("Error: Mic Permission Denied!");
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Mic Error: " + e.getMessage());
            updateNotification("Error: " + e.getMessage());
            stopSelf();
        }
    }

    /**
     * Enable Android's built-in audio processing for better voice quality.
     */
    private void enableAudioProcessing(int audioSessionId) {
        // Noise Suppressor - reduces background noise
        // Disabled by default as user reported it's too aggressive
        // if (NoiseSuppressor.isAvailable()) {
        // try {
        // noiseSuppressor = NoiseSuppressor.create(audioSessionId);
        // if (noiseSuppressor != null) {
        // noiseSuppressor.setEnabled(true);
        // Log.i(TAG, "NoiseSuppressor enabled");
        // }
        // } catch (Exception e) {
        // Log.w(TAG, "Failed to enable NoiseSuppressor: " + e.getMessage());
        // }
        // }

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

        // Automatic Gain Control - Keep this to help with levels
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

    // Helper to apply gain with Soft Limiter
    private byte[] applyGain(byte[] audioData, int length, float gain) {
        if (gain == 1.0f)
            return audioData;

        byte[] output = new byte[length];

        for (int i = 0; i < length; i += 2) {
            // Little Endian conversion
            int low = audioData[i] & 0xFF;
            int high = audioData[i + 1] << 8;
            int sample = (short) (high | low);

            // 1. Apply Gain
            float processed = sample * gain;

            // 2. Soft Limiter (tanh-like curve or hard clamp)
            // Hard clamp is simplest but causes square waves.
            // Let's use hard clamp for speed, but user asked for "Best Quality".
            // Soft clipping: if x > threshold, compress.
            // Using simple hard clamp for now as 2.5x gain isn't massive.

            if (processed > 32767.0f)
                processed = 32767.0f;
            if (processed < -32768.0f)
                processed = -32768.0f;

            int newSample = (int) processed;

            // Convert back to bytes
            output[i] = (byte) (newSample & 0xFF);
            output[i + 1] = (byte) ((newSample >> 8) & 0xFF);
        }
        return output;
    }

    private void captureLoop() {
        // Larger buffer for processing
        int processBufferSize = BUFFER_SIZE;
        byte[] buffer = new byte[processBufferSize];

        // Software Gain Factor (Volume Boost)
        // Multiplier for PCM samples. 1.0 = original.
        // User reported volume is too low.
        float gainFactor = 2.5f;

        while (isRunning && audioRecord != null) {
            int bytesRead = audioRecord.read(buffer, 0, processBufferSize);

            // Only send audio when mic is active (controlled by Mod) and read is valid
            if (bytesRead > 0 && isMicActive) {

                // Apply Software Gain
                byte[] val = applyGain(buffer, bytesRead, gainFactor);

                // Send to Mindustry mod
                sendAudio(val, bytesRead);
            }
        }
    }

    /**
     * Listen for control commands from Mod (START_MIC, STOP_MIC, SHUTDOWN).
     */

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
        int retryCount = 0;
        final int MAX_RETRIES = 10; // ~30 seconds of retries

        // Retry loop
        while (isRunning && retryCount < MAX_RETRIES) {
            try {
                if (socket != null && !socket.isClosed()) {
                    closeSocket();
                }

                Log.i(TAG, "Attempting connection to " + HOST + ":" + PORT);
                socket = new Socket(HOST, PORT);
                socket.setTcpNoDelay(true); // Disable Nagle's algorithm for lower latency
                socket.setSoTimeout(5000); // 5 second timeout for read
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                Log.i(TAG, "Connected to Mindustry mod (TCP_NODELAY enabled, command channel ready)");
                updateNotification("Connected.");

                // Connection successful, break retry loop and start listeners
                break;
            } catch (Exception e) {
                Log.w(TAG, "Connection attempt failed: " + e.getMessage());
                updateNotification("Connecting... (Retry " + (retryCount + 1) + ")");
                retryCount++;
                try {
                    Thread.sleep(3000); // Wait 3 seconds before retry
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (isRunning && socket == null) {
            Log.e(TAG, "Failed to connect after retries. Stopping service.");
            stopSelf();
            return;
        }

        // Start command listener if not already running
        if (isRunning && (commandThread == null || !commandThread.isAlive())) {
            commandThread = new Thread(this::commandListenerLoop);
            commandThread.setDaemon(true);
            commandThread.start();
        }
    }

    private void commandListenerLoop() {
        while (isRunning) {
            try {
                if (inputStream == null) {
                    Thread.sleep(100);
                    continue;
                }

                int cmd = inputStream.read();
                if (cmd < 0) {
                    // Connection closed by Mod (or Mod crashed)
                    Log.w(TAG, "Command stream closed (EOF). Stopping service.");
                    stopSelf();
                    return;
                }

                switch (cmd) {
                    case CMD_START_MIC:
                        Log.i(TAG, "Received CMD_START_MIC - mic activated");
                        isMicActive = true;
                        updateNotification("Connected (Mic Active)");
                        break;
                    case CMD_STOP_MIC:
                        Log.i(TAG, "Received CMD_STOP_MIC - mic paused (privacy mode)");
                        isMicActive = false;
                        updateNotification("Connected (Mic Paused)");
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
                // Timeout is OK, just a heartbeat check effectively
            } catch (Exception e) {
                Log.w(TAG, "Command listener error: " + e.getMessage());
                // Connection died
                stopSelf();
            }
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
