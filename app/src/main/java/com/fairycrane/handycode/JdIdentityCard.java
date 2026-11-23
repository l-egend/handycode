package com.fairycrane.handycode;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.http.SslError;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.IOException;

/** Shows the live official JD identity code on the JD tab. */
final class JdIdentityCard extends FrameLayout {
    private static final long MIN_REFRESH_MS = 320L;
    private static final String MISSING_SCRIPT = "身份码页面资源读取失败，请重新安装。";
    private final Runnable changed;
    private final LinearLayout card, heading;
    private final TextView title, refresh, notice;
    private final View line;
    private final FrameLayout content;
    private final ProgressBar spinner;
    private WebView web;
    private String script = "", error = "", presentation = "";
    private boolean dark, active, destroyed, inFlight, loginRequired, loginOpen, loginReady, signedIn, loading = true;
    private boolean codeShown, keyboardVisible;
    private int generation, contentHeight;
    private long deadline, loadingStarted;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active || destroyed) return;
            sample();
            removeCallbacks(this);
            postDelayed(this, 400);
        }
    };

    JdIdentityCard(Context context, Runnable changed) {
        super(context);
        this.changed = changed;
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipToOutline(true);
        addView(card, new FrameLayout.LayoutParams(-1, -2));
        heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(14), dp(12), dp(14), dp(12));
        title = new TextView(context);
        title.setText("身份码");
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = new TextView(context);
        refresh.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        refresh.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        refresh.setMinWidth((int)Math.ceil(refresh.getPaint().measureText("刷新中")));
        refresh.setPadding(0, dp(5), 0, dp(5));
        refresh.setOnClickListener(v -> reload());
        heading.addView(refresh);
        card.addView(heading);
        line = new View(context);
        card.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        content = new FrameLayout(context);
        contentHeight = dp(218);
        card.addView(content, new LinearLayout.LayoutParams(-1, contentHeight));
        notice = new TextView(context);
        notice.setGravity(Gravity.CENTER);
        notice.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        notice.setPadding(dp(20), dp(20), dp(20), dp(20));
        notice.setOnClickListener(v -> {
            if (loginRequired && loginReady) { loginOpen = true; present(); }
            else reload();
        });
        spinner = new ProgressBar(context);
        content.addView(spinner, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
        content.addView(notice, new FrameLayout.LayoutParams(-1, -1));
        try {
            script = WebAssets.readText(context.getAssets(), "jd-identity.js");
        } catch (IOException failure) { error = MISSING_SCRIPT; loading = false; }
        setDark(false);
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    boolean isSignedIn() { return signedIn; }
    boolean isCodeVisible() { return active && codeShown && web != null && web.isShown(); }

    void setDark(boolean value) {
        dark = value;
        setBackgroundColor(AppTheme.bg(dark));
        title.setTextColor(AppTheme.ink(dark));
        line.setBackgroundColor(AppTheme.divider(dark));
        notice.setTextColor(AppTheme.muted(dark));
        if (web != null) web.evaluateJavascript(themeUpdate(), null);
        present();
    }

    void setKeyboardVisible(boolean value) {
        keyboardVisible = value;
        applyPagePadding();
    }

    private void applyPagePadding() {
        int side = loginOpen ? 0 : dp(12);
        setPadding(side, loginOpen ? 0 : dp(10), side, keyboardVisible ? 0 : dp(72));
    }

    void scrollFocusedInput() {
        if (active && loginOpen && web != null) web.evaluateJavascript(
            "(function(){var e=document.activeElement;if(e&&e.matches('input,textarea'))e.scrollIntoView({block:'center'});})()", null);
    }

    private String themeUpdate() {
        return "window.HandyJdIdentity&&window.HandyJdIdentity.setDark(" + dark + ");";
    }

    void resumePage() {
        if (destroyed) return;
        boolean recheckLogin = !active && loginRequired && !loginOpen;
        active = true;
        if (web == null && !script.isEmpty()) createWeb();
        if (web != null) {
            web.onResume();
            if (recheckLogin) {
                loginReady = false;
                startLoading();
                web.loadUrl(JdUrlPolicy.IDENTITY);
            }
        }
        removeCallbacks(tick);
        post(tick);
        present();
    }

    private void createWeb() {
        web = new WebView(getContext());
        web.setOverScrollMode(OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setTextZoom(100);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        WebView expected = web;
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (web != expected) return false;
                String text = message.message(), prefix = "__HANDYCODE_JD_IDENTITY_HEIGHT__:";
                if (JdUrlPolicy.isIdentity(web.getUrl()) && text.startsWith(prefix)) {
                    try { resize(Integer.parseInt(text.substring(prefix.length()))); } catch (NumberFormatException ignored) {}
                    return true;
                }
                return false;
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && !JdUrlPolicy.allowedMall(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return !JdUrlPolicy.allowedMall(url); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (web != view) return;
                generation++; inFlight = false; loginReady = false;
                if (!JdUrlPolicy.allowedMall(url)) { view.stopLoading(); fail("已阻止非京东官方地址。"); return; }
                startLoading();
                sample();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (web != view || !url.equals(view.getUrl())) return;
                loginReady = JdUrlPolicy.isMallLoginPage(url) && error.isEmpty();
                CookieManager.getInstance().flush();
                sample();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError problem) {
                handler.cancel(); fail("身份码安全连接验证失败，请检查网络或手机日期。");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError problem) {
                if (request.isForMainFrame()) fail("身份码暂时无法连接，点此重试。");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) fail("京东身份码页面暂时无法加载，点此重试。");
            }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (web == view) {
                    generation++; inFlight = false;
                    content.removeView(view); web = null; view.destroy();
                    signedIn = false; loginOpen = false; loginReady = false;
                    fail("身份码网页组件已停止，点此重新打开。"); changed.run();
                }
                return true;
            }
        });
        web.setVisibility(INVISIBLE);
        web.setBackgroundColor(Color.TRANSPARENT);
        content.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        loadingStarted = SystemClock.elapsedRealtime(); deadline = loadingStarted + 18000;
        web.loadUrl(JdUrlPolicy.IDENTITY);
    }

    private void resize(int cssHeight) {
        if (loginOpen || cssHeight < 120 || cssHeight > 2200) return;
        int next = dp(cssHeight);
        if (contentHeight == next) return;
        contentHeight = next;
        if (!loading) content.setLayoutParams(new LinearLayout.LayoutParams(-1, contentHeight));
    }

    private void sample() {
        final WebView expected = web;
        final int token = generation;
        if (!active || destroyed || expected == null || inFlight) return;
        String url = expected.getUrl();
        if (!JdUrlPolicy.allowedMall(url)) return;
        if (loginReady && JdUrlPolicy.isMallLoginPage(url)) {
            boolean wasSignedIn = signedIn;
            signedIn = false;
            loginRequired = true;
            loading = false;
            present();
            if (wasSignedIn) changed.run();
            return;
        }
        if (JdUrlPolicy.isIdentity(url)) {
            inFlight = true;
            int availablePixels = getHeight() - getPaddingTop() - getPaddingBottom() - heading.getMeasuredHeight() - line.getMeasuredHeight();
            int available = Math.max(180, (int)(availablePixels / getResources().getDisplayMetrics().density));
            String probe = script + ";\n" + themeUpdate()
                + "window.HandyJdIdentity&&(window.HandyJdIdentity.setAvailable(" + available + "),window.HandyJdIdentity.snapshot());";
            expected.evaluateJavascript(probe, result -> {
                if (destroyed || web != expected || generation != token) return;
                inFlight = false;
                try {
                    JSONObject data = new JSONObject(result);
                    if (!data.optString("href").equals(expected.getUrl())) return;
                    if (data.optBoolean("failed")) { fail("身份码刷新未完成，点此重试。"); return; }
                    if (data.optBoolean("refreshing") && !loading) {
                        startLoading();
                    }
                    if (data.optBoolean("ready") && !data.optBoolean("refreshing")) {
                        long hold = loading ? Math.max(0, MIN_REFRESH_MS - (SystemClock.elapsedRealtime() - loadingStarted)) : 0;
                        if (hold > 0) {
                            inFlight = true;
                            postDelayed(() -> {
                                if (destroyed || web != expected || generation != token) return;
                                inFlight = false; acceptReady(data);
                            }, hold);
                        } else acceptReady(data);
                    }
                } catch (Exception incomplete) { /* Navigation is retried within the deadline. */ }
            });
        }
        if (loading && deadline > 0 && SystemClock.elapsedRealtime() > deadline) fail("身份码暂未加载完成，点此重试。");
    }

    private void acceptReady(JSONObject data) {
        boolean notify = !signedIn || loginOpen;
        signedIn = true; loginRequired = false; loginOpen = false; loading = false; error = "";
        resize(data.optInt("height")); present();
        if (notify) changed.run();
    }

    private void fail(String message) { generation++; inFlight = false; loading = false; error = message; present(); }

    private void startLoading() {
        loading = true;
        error = "";
        loadingStarted = SystemClock.elapsedRealtime();
        deadline = loadingStarted + 18000;
        present();
    }

    private void present() {
        boolean showLogin = loginOpen && loginReady;
        boolean showCode = signedIn && !loginRequired && !loading && error.isEmpty();
        String next = loginOpen + ":" + showLogin + ":" + showCode + ":" + loading + ":" + loginRequired + ":" + error + ":" + dark + ":" + active;
        if (next.equals(presentation)) return;
        presentation = next;
        applyPagePadding();
        FrameLayout.LayoutParams cardSize = (FrameLayout.LayoutParams) card.getLayoutParams();
        int height = loginOpen ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT;
        if (cardSize.height != height) {
            cardSize.height = height;
            card.setLayoutParams(cardSize);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(loginOpen ? AppTheme.bg(dark) : AppTheme.surface(dark));
        bg.setCornerRadius(loginOpen ? 0 : dp(16));
        bg.setStroke(loginOpen ? 0 : dp(1), AppTheme.border(dark));
        card.setBackground(bg);
        heading.setVisibility(loginOpen ? GONE : VISIBLE);
        line.setVisibility(loginOpen ? GONE : VISIBLE);
        content.setLayoutParams(loginOpen ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, contentHeight));
        refresh.setText(loading ? "刷新中" : "刷新");
        refresh.setEnabled(!loading);
        refresh.setTextColor(loading ? AppTheme.muted(dark) : AppTheme.accentText(dark));
        if (web != null) {
            web.setBackgroundColor(loginOpen ? AppTheme.bg(dark) : Color.TRANSPARENT);
            web.animate().cancel();
            web.setAlpha(showCode && !codeShown && active ? 0f : 1f);
            web.setVisibility(showLogin || showCode ? VISIBLE : INVISIBLE);
            web.setImportantForAccessibility(showLogin || showCode ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (showCode && !codeShown && active) web.animate().alpha(1f).setDuration(200).start();
        }
        codeShown = showCode;
        boolean showNotice = !loading && !showLogin && !showCode;
        notice.setVisibility(showNotice ? VISIBLE : GONE);
        notice.setText(loginRequired && loginReady ? "请在京东官方页面登录身份码\n点击继续登录" : error);
        spinner.setVisibility(loading ? VISIBLE : GONE);
    }

    private void reload() {
        if (loading || destroyed || script.isEmpty()) return;
        if (loginRequired && loginReady) { loginOpen = true; present(); return; }
        startLoading();
        if (web == null) createWeb();
        else if (signedIn && JdUrlPolicy.isIdentity(web.getUrl()))
            web.evaluateJavascript("window.HandyJdIdentity&&window.HandyJdIdentity.refresh();", null);
        else web.loadUrl(JdUrlPolicy.IDENTITY);
    }

    boolean goBack() {
        if (!loginOpen) return false;
        if (web != null && web.canGoBack() && !JdUrlPolicy.isMallLoginPage(web.getUrl())) web.goBack();
        else { loginOpen = false; present(); }
        return true;
    }

    void pausePage() {
        active = false;
        removeCallbacks(tick);
        if (web != null) web.onPause();
    }

    void resetSessionView() {
        pausePage();
        disposeWeb();
        signedIn = false;
        loginRequired = false;
        loginOpen = false;
        loginReady = false;
        loading = !script.isEmpty();
        error = loading ? "" : MISSING_SCRIPT;
        contentHeight = dp(218);
        present();
    }

    void destroyPage() {
        if (destroyed) return;
        destroyed = true;
        pausePage();
        disposeWeb();
        CookieManager.getInstance().flush();
    }

    private void disposeWeb() {
        generation++;
        inFlight = false;
        if (web != null) {
            WebView previous = web;
            web = null;
            previous.setWebViewClient(new WebViewClient());
            previous.setWebChromeClient(null);
            previous.stopLoading();
            content.removeView(previous);
            previous.destroy();
        }
    }
}
