package com.sher.music;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.*;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("sher_prefs", MODE_PRIVATE);

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
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                String html = "<html><body style='background:#000;color:#f5f5f7;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>" +
                    "<div><h2 style='color:#fa2d48'>无法连接服务器</h2>" +
                    "<p>请确保服务器正在运行</p>" +
                    "<p style='color:#888;font-size:14px;margin-top:16px'>" + getServerUrl() + "</p>" +
                    "<button onclick='location.reload()' style='margin-top:20px;padding:10px 24px;background:#fa2d48;color:#fff;border:none;border-radius:12px;font-size:16px'>重试</button>" +
                    "<button onclick=\"Android.openSettings()\" style='margin-top:12px;padding:10px 24px;background:#333;color:#fff;border:none;border-radius:12px;font-size:16px;display:block;width:200px;margin-left:auto;margin-right:auto'>设置</button>" +
                    "</div></body></html>";
                view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http") && !url.contains(getBaseUrl())) {
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

        // JS bridge for settings
        webView.addJavascriptInterface(new JSBridge(), "Android");

        loadServer();
    }

    private String getBaseUrl() {
        String url = prefs.getString("server_url", "");
        if (url.isEmpty()) {
            url = getDefaultUrl();
        }
        return url.replaceFirst("/$", "");
    }

    private String getServerUrl() {
        return getBaseUrl();
    }

    private String getDefaultUrl() {
        // Default: try common local addresses
        return "http://192.168.1.100:3000";
    }

    private void loadServer() {
        String url = getBaseUrl();
        if (url.isEmpty()) url = getDefaultUrl();
        webView.loadUrl(url);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "设置");
        menu.add(0, 2, 0, "刷新");
        menu.add(0, 3, 0, "返回首页");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case 2:
                webView.reload();
                return true;
            case 3:
                loadServer();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Check if URL changed in settings
        String currentUrl = webView.getUrl();
        String savedUrl = getBaseUrl();
        if (currentUrl != null && !currentUrl.startsWith(savedUrl)) {
            loadServer();
        }
    }

    public class JSBridge {
        @android.webkit.JavascriptInterface
        public void openSettings() {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        }
    }
}
