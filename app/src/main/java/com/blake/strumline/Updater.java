package com.blake.strumline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the GitHub repo's latest release. Each push builds a release tagged "build-N";
 * if N is newer than this app's version, offers to download and install it.
 */
class Updater {
    private static final String LATEST = "https://api.github.com/repos/cupidsrage/Strumline/releases/latest";
    private static final long CHECK_EVERY_MS = 30 * 60 * 1000;

    private final Activity act;
    private long lastCheck = 0;
    private int dismissedBuild = -1;
    private boolean dialogOpen = false;
    private String pendingApkUrl = null; // waiting for "install unknown apps" permission
    private int pendingBuild = 0;

    Updater(Activity act) { this.act = act; }

    /** Call from onResume. */
    void onResume() {
        if (pendingApkUrl != null && canInstall()) {
            String url = pendingApkUrl; pendingApkUrl = null;
            download(url, pendingBuild);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_EVERY_MS) return;
        lastCheck = now;
        new Thread(this::check).start();
    }

    private int currentBuild() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return Integer.MAX_VALUE; }
    }

    private void check() {
        try {
            JSONObject rel = new JSONObject(new String(get(LATEST, null), "UTF-8"));
            String tag = rel.optString("tag_name", "");
            if (!tag.startsWith("build-")) return;
            final int latest = Integer.parseInt(tag.substring(6));
            if (latest <= currentBuild() || latest == dismissedBuild) return;
            String apk = null;
            JSONArray assets = rel.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
            }
            if (apk == null) return;
            String notes = rel.optString("body", "").trim();
            if (notes.length() > 500) notes = notes.substring(0, 500) + "…";
            final String apkUrl = apk, msg = notes.isEmpty() ? "A new version of Strumline is ready." : notes;
            act.runOnUiThread(() -> offer(latest, apkUrl, msg));
        } catch (Exception ignored) { /* offline or GitHub unavailable: try again later */ }
    }

    private void offer(int build, String apkUrl, String notes) {
        if (dialogOpen || act.isFinishing()) return;
        dialogOpen = true;
        new AlertDialog.Builder(act)
                .setTitle("Update available (build " + build + ")")
                .setMessage(notes)
                .setPositiveButton("Update", (d, w) -> { dialogOpen = false; start(apkUrl, build); })
                .setNegativeButton("Later", (d, w) -> { dialogOpen = false; dismissedBuild = build; })
                .setOnCancelListener(d -> { dialogOpen = false; dismissedBuild = build; })
                .show();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    private void start(String apkUrl, int build) {
        if (!canInstall()) {
            pendingApkUrl = apkUrl; pendingBuild = build;
            Toast.makeText(act, "Allow Strumline to install updates, then go back", Toast.LENGTH_LONG).show();
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            return;
        }
        download(apkUrl, build);
    }

    private void download(String apkUrl, int build) {
        final AlertDialog progress = new AlertDialog.Builder(act)
                .setTitle("Updating Strumline")
                .setMessage("Downloading build " + build + "…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            try {
                File dir = new File(act.getCacheDir(), "updates");
                dir.mkdirs();
                File out = new File(dir, "strumline.apk");
                get(apkUrl, out);
                act.runOnUiThread(() -> { progress.dismiss(); install(out); });
            } catch (Exception e) {
                act.runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(act, "Couldn't download the update. Try again later.", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void install(File apk) {
        Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
    }

    /** GET a URL, following redirects. Writes to {@code dest} if given, otherwise returns the body. */
    private static byte[] get(String address, File dest) throws Exception {
        URL url = new URL(address);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Strumline-Updater");
            c.setRequestProperty("Accept", dest == null ? "application/vnd.github+json" : "application/octet-stream");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                url = new URL(url, loc);
                continue;
            }
            if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code); }
            try (InputStream in = c.getInputStream()) {
                if (dest != null) {
                    try (FileOutputStream fo = new FileOutputStream(dest)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                    return null;
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally { c.disconnect(); }
        }
        throw new Exception("too many redirects");
    }
}
