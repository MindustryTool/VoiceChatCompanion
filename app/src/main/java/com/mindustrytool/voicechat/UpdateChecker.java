package com.mindustrytool.voicechat;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks for app updates from GitHub releases and handles download/install.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/MindustryTool/VoiceChatCompanion/releases/latest";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public UpdateChecker(Context context) {
        this.context = context;
    }

    /**
     * Check for updates in background and show dialog if new version available.
     */
    public void checkForUpdates() {
        executor.execute(() -> {
            try {
                String currentVersion = getCurrentVersion();
                ReleaseInfo latestRelease = fetchLatestRelease();

                if (latestRelease != null && isNewerVersion(latestRelease.version, currentVersion)) {
                    mainHandler.post(() -> showUpdateDialog(latestRelease));
                } else {
                    Log.i(TAG, "App is up to date: " + currentVersion);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to check for updates: " + e.getMessage());
            }
        });
    }

    /**
     * Check for updates and notify user of result.
     */
    public void checkForUpdatesWithNotification() {
        executor.execute(() -> {
            try {
                String currentVersion = getCurrentVersion();
                ReleaseInfo latestRelease = fetchLatestRelease();

                if (latestRelease != null && isNewerVersion(latestRelease.version, currentVersion)) {
                    mainHandler.post(() -> showUpdateDialog(latestRelease));
                } else {
                    mainHandler.post(
                            () -> Toast.makeText(context, "You have the latest version!", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                mainHandler
                        .post(() -> Toast.makeText(context, "Failed to check for updates", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String getCurrentVersion() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        URL url = new URL(GITHUB_API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        if (conn.getResponseCode() != 200) {
            throw new Exception("HTTP " + conn.getResponseCode());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        JSONObject json = new JSONObject(response.toString());
        String tagName = json.getString("tag_name").replace("v", "");
        String body = json.optString("body", "");

        // Find APK download URL
        String downloadUrl = null;
        if (json.has("assets")) {
            for (int i = 0; i < json.getJSONArray("assets").length(); i++) {
                JSONObject asset = json.getJSONArray("assets").getJSONObject(i);
                String name = asset.getString("name");
                if (name.endsWith(".apk") && name.contains("release")) {
                    downloadUrl = asset.getString("browser_download_url");
                    break;
                }
            }
            // Fallback to any APK
            if (downloadUrl == null) {
                for (int i = 0; i < json.getJSONArray("assets").length(); i++) {
                    JSONObject asset = json.getJSONArray("assets").getJSONObject(i);
                    String name = asset.getString("name");
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url");
                        break;
                    }
                }
            }
        }

        return new ReleaseInfo(tagName, body, downloadUrl);
    }

    private boolean isNewerVersion(String latestVersion, String currentVersion) {
        try {
            String[] latest = latestVersion.split("\\.");
            String[] current = currentVersion.split("\\.");

            for (int i = 0; i < Math.max(latest.length, current.length); i++) {
                int l = i < latest.length ? Integer.parseInt(latest[i].replaceAll("[^0-9]", "")) : 0;
                int c = i < current.length ? Integer.parseInt(current[i].replaceAll("[^0-9]", "")) : 0;
                if (l > c)
                    return true;
                if (l < c)
                    return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Version comparison failed: " + e.getMessage());
        }
        return false;
    }

    private void showUpdateDialog(ReleaseInfo release) {
        new AlertDialog.Builder(context)
                .setTitle("Update Available!")
                .setMessage("New version " + release.version + " is available.\n\n" +
                        release.changelog)
                .setPositiveButton("Download", (dialog, which) -> {
                    if (release.downloadUrl != null) {
                        downloadAndInstall(release.downloadUrl, release.version);
                    } else {
                        openGitHubReleases();
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void downloadAndInstall(String downloadUrl, String version) {
        Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show();

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        request.setTitle("VoiceChatCompanion v" + version);
        request.setDescription("Downloading update...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "VoiceChatCompanion-" + version + ".apk");

        long downloadId = downloadManager.enqueue(request);

        // Register receiver to install after download
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    context.unregisterReceiver(this);
                    installApk(version);
                }
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_EXPORTED : 0);
    }

    private void installApk(String version) {
        File apkFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VoiceChatCompanion-" + version + ".apk");

        if (!apkFile.exists()) {
            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private void openGitHubReleases() {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/MindustryTool/VoiceChatCompanion/releases"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static class ReleaseInfo {
        final String version;
        final String changelog;
        final String downloadUrl;

        ReleaseInfo(String version, String changelog, String downloadUrl) {
            this.version = version;
            this.changelog = changelog;
            this.downloadUrl = downloadUrl;
        }
    }
}
