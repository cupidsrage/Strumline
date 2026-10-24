package com.blake.strumline;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final int MIC_REQ = 7;
    private WebView web;
    private PermissionRequest pendingMic;
    private boolean pageReady = false;
    private String pendingShare = null;
    private final ExecutorService net = Executors.newFixedThreadPool(2);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // saved songs and settings live in localStorage
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                deliverShare();
            }
        });
        // Listen mode: the page asks for the microphone; ask Android for permission, then pass it on.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                            } else {
                                pendingMic = request;
                                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQ);
                            }
                            return;
                        }
                    }
                    request.deny();
                });
            }
        });
        web.addJavascriptInterface(new Bridge(), "StrumlineNative");

        setContentView(web);
        handleShare(getIntent());
        web.loadUrl(START_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    private void handleShare(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.trim().isEmpty()) {
                pendingShare = text;
                deliverShare();
            }
        }
    }

    private void deliverShare() {
        if (!pageReady || pendingShare == null) return;
        String js = "window.__importShared && window.__importShared(" + JSONObject.quote(pendingShare) + ")";
        pendingShare = null;
        web.evaluateJavascript(js, null);
    }

    /** Lets the page download a song page (normal web pages can't read other sites). */
    private class Bridge {
        @JavascriptInterface
        public void fetchUrl(final String url, final String id) {
            net.execute(() -> {
                boolean ok;
                String body;
                try {
                    body = download(url);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                    body = "Couldn't get that page (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").";
                }
                final String b64 = Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                final String js = "window.__fetchDone(" + JSONObject.quote(id) + "," + ok + ",\"" + b64 + "\")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            });
        }
    }

    private static String download(String address) throws Exception {
        URL url = new URL(address);
        for (int hop = 0; hop < 6; hop++) {
            String proto = url.getProtocol();
            if (!"https".equals(proto) && !"http".equals(proto)) throw new Exception("only web links work");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirect without a destination");
                url = new URL(url, loc);
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new Exception("the site answered " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) break;
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == MIC_REQ && pendingMic != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingMic.deny();
            }
            pendingMic = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back ? String(window.__back()) : 'false'", value -> {
            if (!"\"true\"".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() { net.shutdownNow(); super.onDestroy(); }
}
