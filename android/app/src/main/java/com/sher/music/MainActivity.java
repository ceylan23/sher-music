package com.sher.music;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.*;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private EmbeddedServer server;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http") && !url.contains("127.0.0.1")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
            }
        });

        startServer();
    }

    private void startServer() {
        progressBar.setVisibility(View.VISIBLE);
        server = new EmbeddedServer(getApplicationContext());
        server.start();

        // Wait for server to be ready
        handler.postDelayed(() -> {
            int port = server.getPort();
            if (port > 0) {
                webView.loadUrl("http://127.0.0.1:" + port + "/index.html");
            } else {
                // Retry
                handler.postDelayed(() -> {
                    int p = server.getPort();
                    if (p > 0) webView.loadUrl("http://127.0.0.1:" + p + "/index.html");
                    else showError();
                }, 500);
            }
        }, 300);
    }

    private void showError() {
        progressBar.setVisibility(View.GONE);
        webView.loadData(
            "<html><body style='background:#000;color:#f5f5f7;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>" +
            "<div><h2 style='color:#fa2d48;font-size:24px'>Sher</h2>" +
            "<p style='color:#8e8e93;margin-top:8px'>服务器启动失败</p>" +
            "<button onclick='location.reload()' style='margin-top:20px;padding:10px 24px;background:#fa243c;color:#fff;border:none;border-radius:12px;font-size:16px'>重试</button>" +
            "</div></body></html>",
            "text/html", "UTF-8"
        );
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "刷新");
        menu.add(0, 2, 0, "返回首页");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: webView.reload(); return true;
            case 2:
                int port = server != null ? server.getPort() : 0;
                if (port > 0) webView.loadUrl("http://127.0.0.1:" + port + "/index.html");
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stopServer();
        super.onDestroy();
    }
}
