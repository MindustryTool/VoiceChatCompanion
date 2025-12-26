package com.mindustrytool.voicechat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Main activity for Voice Chat Companion app.
 * Handles permission requests and starts the audio capture service.
 */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private UpdateChecker updateChecker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);

        updateUI(AudioCaptureService.isRunning());

        // Check for updates on app start
        updateChecker = new UpdateChecker(this);
        updateChecker.checkForUpdates();

        // Handle Auto-Launch from Mod
        if (getIntent().getBooleanExtra("EXTRA_AUTO_LAUNCH", false)) {
            if (checkPermissionsAndStart()) {
                // Only minimize if permission granted and service starting
                moveTaskToBack(true);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI(AudioCaptureService.isRunning());
    }

    // ... (keep menu code) ...

    private boolean checkPermissionsAndStart() {
        // ... (keep permission checks) ...
        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.RECORD_AUDIO },
                    PERMISSION_REQUEST_CODE);
            return false;
        }

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.POST_NOTIFICATIONS },
                        PERMISSION_REQUEST_CODE);
                return false;
            }
        }

        startService();
        return true;
    }

    private void startService() {
        Intent intent = new Intent(this, AudioCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateUI(true);
    }

    // stopService method removed as manual stop is not needed

    private void updateUI(boolean isRunning) {
        if (statusText != null) {
            statusText.setText(isRunning ? "Status: Running" : "Status: Waiting for Mod...");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, try to start again
                checkPermissionsAndStart();
            } else {
                Toast.makeText(this, "Microphone permission is required for Voice Chat",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
