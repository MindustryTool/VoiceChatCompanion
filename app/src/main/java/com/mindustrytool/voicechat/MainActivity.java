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

        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        statusText = findViewById(R.id.statusText);

        startButton.setOnClickListener(v -> checkPermissionsAndStart());
        stopButton.setOnClickListener(v -> stopService());

        updateUI(AudioCaptureService.isRunning());

        // Check for updates on app start
        updateChecker = new UpdateChecker(this);
        updateChecker.checkForUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI(AudioCaptureService.isRunning());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Check for Updates");
        menu.add(0, 2, 0, "About");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                updateChecker.checkForUpdatesWithNotification();
                return true;
            case 2:
                showAbout();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showAbout() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Voice Chat Companion")
                    .setMessage("Version: " + version + "\n\n" +
                            "Captures microphone audio for Mindustry Voice Chat.\n\n" +
                            "github.com/MindustryTool/VoiceChatCompanion")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            // Ignore
        }
    }

    private void checkPermissionsAndStart() {
        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.RECORD_AUDIO },
                    PERMISSION_REQUEST_CODE);
            return;
        }

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.POST_NOTIFICATIONS },
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }

        startService();
    }

    private void startService() {
        Intent intent = new Intent(this, AudioCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateUI(true);
        Toast.makeText(this, "Voice Chat started", Toast.LENGTH_SHORT).show();
    }

    private void stopService() {
        Intent intent = new Intent(this, AudioCaptureService.class);
        stopService(intent);
        updateUI(false);
        Toast.makeText(this, "Voice Chat stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateUI(boolean isRunning) {
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);
        statusText.setText(isRunning ? "Status: Running" : "Status: Stopped");
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
