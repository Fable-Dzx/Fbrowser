package com.fabledzx.fbrowser;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayInputStream;

public class MainActivity extends AppCompatActivity {

    private static final String HOME_URL = "https://cn.bing.com/";
    private static final String SEARCH_PREFIX = "https://cn.bing.com/search?q=";

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton btnBack, btnForward, btnReload, btnHome, btnGo;

    private AdBlockerManager adBlocker;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView     = findViewById(R.id.webView);
        urlBar      = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        btnBack     = findViewById(R.id.btnBack);
        btnForward  = findViewById(R.id.btnForward);
        btnReload   = findViewById(R.id.btnReload);
        btnHome     = findViewById(R.id.btnHome);
        btnGo       = findViewById(R.id.btnGo);

        // 初始化广告拦截
        adBlocker = new AdBlockerManager();
        adBlocker.init();

        setupWebView();
        setupUrlBar();
        setupButtons();
        setupBackNavigation();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(HOME_URL);
        }
    }

    /* ==================== WebView 配置 ==================== */

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " FBrowser/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {

            /* ---------- 广告拦截核心（修复版） ---------- */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 【核心修复】绝对不拦截主文档（HTML），防止出现 ERR_INVALID_RESPONSE 白屏
                if (request.isForMainFrame()) {
                    return super.shouldInterceptRequest(view, request);
                }

                String url = request.getUrl().toString();
                if (adBlocker != null && adBlocker.shouldBlock(url)) {
                    // 返回空数据流，安全阻止子资源加载
                    return new WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            new ByteArrayInputStream(new byte[0])
                    );
                }
                return super.shouldInterceptRequest(view, request);
            }

            /* ---------- URL 跳转处理 ---------- */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleScheme(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleScheme(url);
            }

            /* ---------- 页面加载回调 ---------- */
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                updateUrlBar(url);
                updateNavButtons();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                updateUrlBar(url);
                updateNavButtons();
            }

            /* ---------- 错误处理（修复版） ---------- */
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    progressBar.setVisibility(View.GONE);
                    // 加载本地自定义的错误页，避免系统原生错误页导致网址错乱
                    String errorHtml = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>"
                            + "<body style='display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;font-family:sans-serif;background-color:#f9f9f9;'>"
                            + "<h2 style='color:#333;'>网页无法打开</h2>"
                            + "<p style='color:#666;font-size:14px;padding:0 20px;text-align:center;word-break:break-all;'>"
                            + request.getUrl().toString() + "</p>"
                            + "<p style='color:#999;font-size:12px;'>错误码: " + error.getErrorCode() + "</p>"
                            + "<button onclick='location.reload()' style='margin-top:20px;padding:8px 24px;background:#1565C0;color:#fff;border:none;border-radius:4px;font-size:16px;'>重新加载</button>"
                            + "</body></html>";
                    view.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null);
                }
            }

            // 兼容 API 23 以下的错误回调
            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    progressBar.setVisibility(View.GONE);
                    String errorHtml = "<html><body style='text-align:center;padding-top:50px;font-family:sans-serif;'>"
                            + "<h2>网页无法打开</h2><p style='color:gray;'>" + failingUrl + "</p>"
                            + "<button onclick='location.reload()' style='margin-top:20px;padding:8px 24px;background:#1565C0;color:#fff;border:none;border-radius:4px;'>重新加载</button>"
                            + "</body></html>";
                    view.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty()) {
                    setTitle(title);
                } else {
                    setTitle(R.string.app_name);
                }
            }
        });
    }

    private boolean handleScheme(String url) {
        if (url == null) return false;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /* ==================== 地址栏 ==================== */

    private void setupUrlBar() {
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEARCH
                        || enter) {
                    navigate(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });

        urlBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) urlBar.selectAll();
            }
        });
    }

    private void navigate(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return;

        String url;
        if (text.startsWith("http://") || text.startsWith("https://")
                || text.startsWith("about:") || text.startsWith("file://")) {
            url = text;
        } else if (looksLikeHost(text)) {
            url = "https://" + text;
        } else {
            url = SEARCH_PREFIX + Uri.encode(text);
        }
        hideKeyboard();
        urlBar.clearFocus();
        
        // 【修复】立刻更新地址栏，不再等待 WebView 的回调
        updateUrlBar(url);
        
        webView.loadUrl(url);
    }

    private boolean looksLikeHost(String text) {
        if (text.contains(" ")) return false;
        String host = text;
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (host.isEmpty()) return false;
        if (host.equalsIgnoreCase("localhost")) return true;
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return true;
        return host.contains(".") && host.matches("[A-Za-z0-9.\\-]+");
    }

    private void updateUrlBar(String url) {
        if (url == null || urlBar.hasFocus()) return;
        urlBar.setText(url);
        urlBar.setSelection(urlBar.getText().length());
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    /* ==================== 按钮 ==================== */

    private void setupButtons() {
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { navigate(urlBar.getText().toString()); }
        });

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (webView.canGoBack()) webView.goBack();
            }
        });

        btnForward.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (webView.canGoForward()) webView.goForward();
            }
        });

        btnReload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (progressBar.getVisibility() == View.VISIBLE) {
                    webView.stopLoading();
                    progressBar.setVisibility(View.GONE);
                } else {
                    webView.reload();
                }
            }
        });

        btnHome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { webView.loadUrl(HOME_URL); }
        });
    }

    private void updateNavButtons() {
        boolean back = webView.canGoBack();
        boolean fwd  = webView.canGoForward();
        btnBack.setEnabled(back);
        btnForward.setEnabled(fwd);
        btnBack.setAlpha(back ? 1f : 0.3f);
        btnForward.setAlpha(fwd ? 1f : 0.3f);
    }

    /* ==================== 返回键 ==================== */

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}