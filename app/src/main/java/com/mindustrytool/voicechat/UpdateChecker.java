package com.mindustrytool.voicechat;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
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
 * Supports seamless in-place update without uninstalling.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/MindustryTool/VoiceChatCompanion/releases?per_page=1";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver downloadReceiver;

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

                Log.i(TAG, "Current version: " + currentVersion + ", Latest: " +
                        (latestRelease != null ? latestRelease.version : "null"));

                if (latestRelease != null && isNewerVersion(latestRelease.version, currentVersion)) {
                    Log.i(TAG, "New version available: " + latestRelease.version);
                    mainHandler.post(() -> showUpdateDialog(latestRelease, currentVersion));
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

                Log.i(TAG, "Manual check - Current: " + currentVersion + ", Latest: " +
                        (latestRelease != null ? latestRelease.version : "null"));

                if (latestRelease != null && isNewerVersion(latestRelease.version, currentVersion)) {
                    mainHandler.post(() -> showUpdateDialog(latestRelease, currentVersion));
                } else {
                    mainHandler
                            .post(() -> Toast.makeText(context, "You have the latest version (" + currentVersion + ")",
                                    Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Check failed: " + e.getMessage());
                mainHandler
                        .post(() -> Toast.makeText(context, "Failed to check for updates", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String getCurrentVersion() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String version = pInfo.versionName;
            Log.d(TAG, "Got current version: " + version);
            return version != null ? version : "0.0.0";
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get current version: " + e.getMessage());
            return "0.0.0";
        }
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        URL url = new URL(GITHUB_API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", "VoiceChatCompanion-Android");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setUseCaches(false);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        JSONArray releases = new JSONArray(response.toString());
        if (releases.length() == 0) {
            return null;
        }
        JSONObject json = releases.getJSONObject(0);

        // Get version from tag_name, strip 'v' prefix if present
        String tagName = json.getString("tag_name");
        String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
        String body = json.optString("body", "Bug fixes and improvements");

        Log.d(TAG, "Parsed release version: " + version);

        // Find APK download URL
        String downloadUrl = null;
        if (json.has("assets")) {
            JSONArray assets = json.getJSONArray("assets");

            // Priority: release APK > any APK
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name").toLowerCase();
                if (name.endsWith(".apk") && name.contains("release")) {
                    downloadUrl = asset.getString("browser_download_url");
                    Log.d(TAG, "Found release APK: " + name);
                    break;
                }
            }

            // Fallback to any APK
            if (downloadUrl == null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.getString("name").toLowerCase();
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url");
                        Log.d(TAG, "Fallback to APK: " + name);
                        break;
                    }
                }
            }
        }

        if (downloadUrl == null) {
            Log.w(TAG, "No APK found in release assets");
        }

        return new ReleaseInfo(version, body, downloadUrl);
    }

    /**
     * Compare versions. Returns true if latestVersion > currentVersion.
     */
    private boolean isNewerVersion(String latestVersion, String currentVersion) {
        if (latestVersion == null || currentVersion == null) {
            return false;
        }

        // Normalize versions
        String latest = latestVersion.trim().toLowerCase().replaceAll("^v", "");
        String current = currentVersion.trim().toLowerCase().replaceAll("^v", "");

        Log.d(TAG, "Comparing: latest='" + latest + "' vs current='" + current + "'");

        // Exact match = no update needed
        if (latest.equals(current)) {
            Log.d(TAG, "Versions are equal");
            return false;
        }

        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");

            int maxLength = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < maxLength; i++) {
                int l = 0, c = 0;

                if (i < latestParts.length) {
                    l = parseVersionPart(latestParts[i]);
                }
                if (i < currentParts.length) {
                    c = parseVersionPart(currentParts[i]);
                }

                Log.d(TAG, "Part " + i + ": latest=" + l + ", current=" + c);

                if (l > c) {
                    Log.d(TAG, "Latest is newer at part " + i);
                    return true;
                }
                if (l < c) {
                    Log.d(TAG, "Current is newer at part " + i);
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Version comparison exception: " + e.getMessage());
        }

        return false;
    }

    private int parseVersionPart(String part) {
        // Extract only numeric portion
        String numericPart = part.replaceAll("[^0-9]", "");
        if (numericPart.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(numericPart);
    }

    private void showUpdateDialog(ReleaseInfo release, String currentVersion) {
        new AlertDialog.Builder(context)
                .setTitle("Update Available!")
                .setMessage("Current: v" + currentVersion + "\n" +
                        "New: v" + release.version + "\n\n" +
                        release.changelog)
                .setPositiveButton("Update Now", (dialog, which) -> {
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

        // Delete old APK if exists
        String fileName = "VoiceChatCompanion-update.apk";
        File oldApk = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), fileName);
        if (oldApk.exists()) {
            oldApk.delete();
        }

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        request.setTitle("VoiceChatCompanion v" + version);
        request.setDescription("Downloading update...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        request.setMimeType("application/vnd.android.package-archive");

        long downloadId = downloadManager.enqueue(request);

        // Unregister previous receiver if any
        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver);
            } catch (Exception e) {
                // Ignore
            }
        }

        // Register receiver to install after download
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    try {
                        context.unregisterReceiver(this);
                    } catch (Exception e) {
                        // Ignore
                    }

                    // Check download status
                    DownloadManager.Query query = new DownloadManager.Query();
                    query.setFilterById(downloadId);
                    Cursor cursor = downloadManager.query(query);

                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);

                        if (statusIndex != -1 && uriIndex != -1) {
                            int status = cursor.getInt(statusIndex);
                            String localUri = cursor.getString(uriIndex);

                            if (status == DownloadManager.STATUS_SUCCESSFUL && localUri != null) {
                                installApk(Uri.parse(localUri));
                            } else {
                                mainHandler.post(() -> Toast.makeText(context,
                                        "Download failed", Toast.LENGTH_SHORT).show());
                            }
                        }
                        cursor.close();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(downloadReceiver, filter);
        }
    }

    private void installApk(Uri localFileUri) {
        if (localFileUri == null) {
            Toast.makeText(context, "Install failed: No file URI", Toast.LENGTH_SHORT).show();
            return;
        }

        File apkFile = new File(localFileUri.getPath());

        if (!apkFile.exists()) {
            Toast.makeText(context, "Download failed - file not found", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "Installing APK: " + apkFile.getAbsolutePath());

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkContentUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkContentUri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkContentUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkContentUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            context.startActivity(intent);

            Log.i(TAG, "Install intent started");
        } catch (Exception e) {
            Log.e(TAG, "Install failed: " + e.getMessage());
            Toast.makeText(context, "Install failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
