package com.blake.strumline;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Strumline's built-in browser. You browse any chord site yourself; when the page you're on
 * has a chord sheet, "Add to Strumline" reads it and hands it back to the player.
 */
public class BrowserActivity extends Activity {
    static final String EXTRA_URL = "url";
    static final String RESULT_JSON = "json";

    private static final int BG = Color.parseColor("#0C1513");
    private static final int SURFACE = Color.parseColor("#15211E");
    private static final int INK = Color.parseColor("#E4EEEB");
    private static final int MUTED = Color.parseColor("#93A6A1");
    private static final int ACCENT = Color.parseColor("#3CC0B3");
    private static final int ACCENT_INK = Color.parseColor("#06211E");

    private WebView web;
    private Button add;
    private TextView title;
    private ProgressBar bar;
    private String extractJs = "";
    private String lastJson = null;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        extractJs = readAsset("extract.js");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(4), dp(12), dp(4));
        top.setBackgroundColor(SURFACE);

        Button close = new Button(this, null, android.R.attr.borderlessButtonStyle);
        close.setText("✕");
        close.setTextColor(INK);
        close.setTextSize(18);
        close.setContentDescription("Close browser");
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(48)));

        title = new TextView(this);
        title.setTextColor(INK);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText("Loading…");
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        add = new Button(this);
        add.setAllCaps(false);
        add.setTextSize(16);
        add.setPadding(dp(16), dp(14), dp(16), dp(14));
        add.setOnClickListener(v -> addSong());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(8), dp(12), dp(12));
        root.addView(add, lp);
        setFound(false);

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                // Stay in this browser for web pages; ignore links that try to open other apps.
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                setFound(false);
                lastJson = null;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CharSequence t = v.getTitle();
                title.setText(t == null || t.length() == 0 ? url : t);
                detect();
                handler.postDelayed(BrowserActivity.this::detect, 1500); // pages that fill in later
                handler.postDelayed(BrowserActivity.this::detect, 4000);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        web.loadUrl(url != null ? url : "https://www.google.com/search?q=ukulele+chords");
    }

    private void setFound(boolean found) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (found) {
            bg.setColor(ACCENT);
            add.setTextColor(ACCENT_INK);
            add.setText("Add to Strumline");
        } else {
            bg.setColor(SURFACE);
            bg.setStroke(dp(1), Color.parseColor("#2A3A36"));
            add.setTextColor(MUTED);
            add.setText("Open a song page to add it");
        }
        add.setBackground(bg);
    }

    /** Looks for a chord sheet on the current page and lights up the Add button if one is there. */
    private void detect() {
        if (isFinishing()) return;
        web.evaluateJavascript(extractJs, value -> {
            JSONObject o = parse(value);
            boolean found = o != null && o.optInt("score", 0) >= 2 && o.optString("text", "").length() > 0;
            lastJson = found ? o.toString() : null;
            setFound(found);
        });
    }

    private void addSong() {
        web.evaluateJavascript(extractJs, value -> {
            JSONObject o = parse(value);
            if (o != null && o.optInt("score", 0) >= 2 && o.optString("text", "").length() > 0) {
                setResult(RESULT_OK, new Intent().putExtra(RESULT_JSON, o.toString()));
                finish();
            } else if (lastJson != null) {
                setResult(RESULT_OK, new Intent().putExtra(RESULT_JSON, lastJson));
                finish();
            } else {
                Toast.makeText(this, "No chords found on this page. Open a song's chord page first.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private static JSONObject parse(String value) {
        try {
            Object v = new JSONTokener(value).nextValue(); // the script's JSON comes back as a quoted string
            if (v instanceof String) return new JSONObject((String) v);
        } catch (Exception ignored) { }
        return null;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "JSON.stringify({score:0})";
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
