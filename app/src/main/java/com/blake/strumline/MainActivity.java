package com.blake.strumline;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

public class MainActivity extends Activity {
    // Served from a fake https origin so the page is a secure context (needed for the microphone).
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int MIC_REQ = 7;
    private static final int BROWSE_REQ = 8;

    private WebView web;
    private boolean pageReady = false;
    private String pendingShare = null;
    private String pendingSheet = null;
    private PermissionRequest pendingMic;
    private Updater updater;

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
                deliver();
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
        updater = new Updater(this);
    }

    /** Called from the page. */
    private class Bridge {
        @JavascriptInterface
        public void openBrowser(final String url) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, BrowserActivity.class);
                i.putExtra(BrowserActivity.EXTRA_URL, url);
                startActivityForResult(i, BROWSE_REQ);
            });
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == BROWSE_REQ && res == RESULT_OK && data != null) {
            pendingSheet = data.getStringExtra(BrowserActivity.RESULT_JSON);
            deliver();
        }
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
                deliver();
            }
        }
    }

    private void deliver() {
        if (!pageReady) return;
        if (pendingShare != null) {
            String js = "window.__importShared && window.__importShared(" + JSONObject.quote(pendingShare) + ")";
            pendingShare = null;
            web.evaluateJavascript(js, null);
        }
        if (pendingSheet != null) {
            String js = "window.__importSheet && window.__importSheet(" + pendingSheet + ")";
            pendingSheet = null;
            web.evaluateJavascript(js, null);
        }
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
        // Let the page step back (sheet/editor -> song list); exit only from the song list.
        web.evaluateJavascript("window.__back ? String(window.__back()) : 'false'", value -> {
            if (!"\"true\"".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); if (updater != null) updater.onResume(); }
}
