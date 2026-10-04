package com.webapp.wrapper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_CODE = 1001;
    private WebView webView;
    private String realUrl = "";
    private long expiresAt = 0;   // dari assets/free.json (ditulis prepare.py); 0/hilang = dianggap expired
    private String renewUrl = "";
    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        try {
            org.json.JSONObject fj = new org.json.JSONObject(readAsset("free.json"));
            expiresAt = fj.optLong("expiresAt", 0);
            renewUrl = fj.optString("renewUrl", "");
        } catch (Exception ignored) { }
        if (isExpired()) { showExpired(); return; }
        long left = (expiresAt - System.currentTimeMillis()) / 86400000L;
        Toast.makeText(this, "Versi gratis - sisa " + Math.max(left, 0) + " hari", Toast.LENGTH_LONG).show();

        realUrl = readAsset("www/url.txt").trim();

        // Jalur aman untuk buka file lokal (www/index.html) lewat https://appassets.androidplatform.net
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Hapus penanda "; wv" supaya situs dengan proteksi bot tidak menganggap ini WebView
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    return false; // tetap dibuka di dalam app
                }
                try { // tel:, mailto:, intent:, dll -> buka aplikasi lain
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !realUrl.isEmpty()) {
                    String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                            + "<body style='font-family:sans-serif;text-align:center;padding:40px'>"
                            + "<h2>Gagal memuat halaman</h2><p>Cek koneksi internet kamu.</p>"
                            + "<p><a href='" + TextUtils.htmlEncode(realUrl) + "'>Coba lagi</a></p></body></html>";
                    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
        }
        checkNetworkTime();
    }

    // Waktu terpercaya: jam HP, tapi tidak boleh mundur dari waktu terakhir yang pernah tercatat
    private boolean isExpired() {
        SharedPreferences sp = getSharedPreferences("nyr_exp", MODE_PRIVATE);
        long now = System.currentTimeMillis(), last = sp.getLong("last", 0);
        if (now < last) now = last; else sp.edit().putLong("last", now).apply();
        return expiresAt <= 0 || now > expiresAt;
    }

    // Cek jam server (header Date) supaya ubah jam HP tidak mempan
    private void checkNetworkTime() {
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://www.google.com").openConnection();
                c.setRequestMethod("HEAD"); c.setConnectTimeout(5000); c.setReadTimeout(5000);
                long net = c.getDate(); c.disconnect();
                if (net > 0) {
                    SharedPreferences sp = getSharedPreferences("nyr_exp", MODE_PRIVATE);
                    sp.edit().putLong("last", Math.max(net, sp.getLong("last", 0))).apply();
                    if (net > expiresAt) runOnUiThread(this::showExpired);
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private void showExpired() {
        String link = renewUrl.isEmpty() ? "" :
                "<a class=b href='" + TextUtils.htmlEncode(renewUrl) + "'>UPGRADE PREMIUM</a>";
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#F2E8D5;font-family:sans-serif}"
                + ".c{background:#E8B931;border:4px solid #111;box-shadow:8px 8px 0 #111;padding:24px;margin:20px;text-align:center}"
                + ".b{display:inline-block;margin-top:14px;background:#fff;color:#111;border:3px solid #111;box-shadow:4px 4px 0 #111;padding:10px 16px;font-weight:bold;text-decoration:none}"
                + "</style></head><body><div class=c><h2>APK KEDALUWARSA</h2><p>Versi gratis hanya aktif 7 hari.<br>Upgrade ke premium untuk APK tanpa batas waktu.</p>"
                + link + "</div></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isExpired()) showExpired();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_CODE && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
