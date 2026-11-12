package com.fabledzx.fbrowser;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
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

public class MainActivity extends AppCompatActivity {

    /** 默认主页（Bing） */
    private static final String HOME_URL = "https://www.bing.com/";
    /** 默认搜索（Bing） */
    private static final String SEARCH_PREFIX = "https://www.bing.com/search?q=";

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton btnBack, btnForward, btnReload, btnHome, btnGo;

    /** 广告拦截管理器 */
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

        // 初始化广告拦截（异步加载拦截库，不阻塞 UI）
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

            /* ---------- 广告拦截核心 ---------- */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (adBlocker != null && adBlocker.shouldBlock(url)) {
                    // 返回空响应，阻止广告资源加载
                    return new WebResourceResponse("text/plain", "utf-8", null);
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

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                progressBar.setVisibility(View.GONE);
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

    /** 非 http(s) 协议交给系统处理（tel:、mailto:、intent: 等） */
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