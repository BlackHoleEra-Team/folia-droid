package top.izuna.foliamajor.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import java.lang.ref.WeakReference;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

// android/app/src/main/java/top/izuna/foliamajor/shell/MainActivity.java
// 壳的入口：先在后台把内置 Node 后端拉起来（解包 → 启动 → 等健康检查），
// 就绪后加载页面，等页面画出第一帧再把启动页淡掉。页面和 API 同源，登录 Cookie 才能正常工作。
public class MainActivity extends Activity {

    private static final String TAG = "FoliaShell";
    private static final String LOCAL_URL = "http://127.0.0.1:32100/";
    private static final String HEALTH_URL = "http://127.0.0.1:32100/health";
    private static final long BOOT_TIMEOUT_MS = 90_000;

    /**
     * 解包出新版本后跑一次：注销旧 Service Worker、清掉它的缓存再刷新。
     * 壳里的资源由本地服务器提供，SW 没有存在意义，只会让更新后的第一次启动读到旧页面。
     */
    private static final String CLEAR_LEGACY_CACHES_JS =
            "(function(){"
                    + "if(!navigator.serviceWorker){return;}"
                    + "navigator.serviceWorker.getRegistrations().then(function(regs){"
                    + "if(!regs.length){return;}"
                    + "Promise.all(regs.map(function(r){return r.unregister();})).then(function(){"
                    + "var done=function(){location.reload();};"
                    + "if(!window.caches){done();return;}"
                    + "caches.keys().then(function(keys){"
                    + "return Promise.all(keys.map(function(k){return caches.delete(k);}));"
                    + "}).then(done,done);"
                    + "});"
                    + "});"
                    + "})()";

    private WebView webView;
    private LinearLayout splashView;
    private TextView statusView;
    private ProgressBar progressBar;
    private boolean splashHidden;
    private boolean legacyCachesCleared;

    private static final String DISPLAY_MODE_PREF = "folia_shell";
    private static final String DISPLAY_MODE_KEY = "display_mode";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityRef = new WeakReference<>(this);
        // 显示模式存壳的偏好里：跟随系统旋转先横屏、播放页横屏先竖屏，页面加载后自己再校正。
        String storedMode = storedDisplayMode(this);
        applyOrientation("system".equals(storedMode) ? "landscape"
                : ("player".equals(storedMode) ? "portrait" : storedMode), this);
        WebView.setWebContentsDebuggingEnabled(true);

        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.app_background));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " FoliaShell/0.2");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        // 页面 ↔ 原生的通道：媒体通知、本机音乐流都从这里进出。
        webView.addJavascriptInterface(new FoliaNativeBridge(this), "foliaNative");
        LocalAudioServer.ensureStarted(this);
        requestNotificationPermissionIfNeeded();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 站内的页面照旧在 WebView 里走；站外的地址交给系统浏览器，别把用户困在应用里。
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return false;
                if ((scheme.equals("http") || scheme.equals("https")) && isLocalUrl(uri)) {
                    return false;
                }
                if (scheme.equals("about") || scheme.equals("javascript") || scheme.equals("file")) {
                    return false;
                }
                openExternally(uri);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.e(TAG, "[page] load failed: " + error.getDescription() + " " + request.getUrl());
                    if (!splashHidden) {
                        showBootError(getString(R.string.starting_failed) + "\n" + error.getDescription());
                    }
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                // 页面第一帧已经在 WebView 里了（这时通常正是它自己的首屏遮罩），把原生启动页淡掉。
                revealWebView();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(
                        "(function(){return JSON.stringify({w:innerWidth,h:innerHeight,dpr:devicePixelRatio,"
                                + "sw:document.documentElement.scrollWidth,sh:document.documentElement.scrollHeight});})()",
                        value -> Log.i(TAG, "[viewport] " + value));
                if (NodeRuntime.justExtracted() && !legacyCachesCleared) {
                    legacyCachesCleared = true;
                    Log.i(TAG, "[cache] clearing service worker caches from a previous build");
                    view.evaluateJavascript(CLEAR_LEGACY_CACHES_JS, null);
                }
                revealWebView();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // window.open 在壳里没有第二个 WebView 可去：接住地址，交给系统浏览器。
                WebView probe = new WebView(MainActivity.this);
                probe.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                        openExternally(request.getUrl());
                        probe.destroy();
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(probe);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.i(TAG, "[console] " + message.message()
                        + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(buildSplash(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applySafeAreaInsets(root);
        splashView.animate().alpha(1f).setDuration(320).start();
        boot();
    }

    private View buildSplash() {
        splashView = new LinearLayout(this);
        splashView.setOrientation(LinearLayout.VERTICAL);
        splashView.setGravity(Gravity.CENTER);
        splashView.setBackgroundColor(getColor(R.color.app_background));
        splashView.setAlpha(0f);

        splashView.addView(new FoliaMarkView(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(96)));

        statusView = new TextView(this);
        statusView.setText(R.string.starting_up);
        statusView.setTextSize(15f);
        statusView.setTextColor(getColor(R.color.splash_text));
        statusView.setGravity(Gravity.CENTER);
        statusView.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(36);
        splashView.addView(statusView, textParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.folia_yellow)));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.splash_track)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(168), dp(6));
        barParams.topMargin = dp(22);
        splashView.addView(progressBar, barParams);

        return splashView;
    }

    private void boot() {
        new Thread(() -> {
            try {
                NodeRuntime.ensureProject(this, this::setStatus);
                NodeRuntime.start(this, this::setStatus);
                setStatus(getString(R.string.starting_local_service));
                final boolean ready = waitForServer(BOOT_TIMEOUT_MS);
                runOnUiThread(() -> {
                    if (!ready) {
                        String tail = NodeRuntime.readLogTail(this, 800);
                        showBootError((NodeRuntime.hasExited()
                                ? getString(R.string.starting_failed) + "（Node 退出码 " + NodeRuntime.exitCode() + "）"
                                : getString(R.string.starting_timeout)) + "\n\n" + tail);
                        return;
                    }
                    String override = getIntent() != null ? getIntent().getStringExtra("url") : null;
                    String url = override != null && !override.isEmpty() ? override : LOCAL_URL;
                    Log.i(TAG, "[page] loading " + url);
                    webView.loadUrl(url);
                });
            } catch (Throwable error) {
                Log.e(TAG, "[boot] failed", error);
                runOnUiThread(() -> showBootError(getString(R.string.starting_failed) + "\n" + error.getMessage()));
            }
        }, "folia-boot").start();
    }

    private void setStatus(String message) {
        Log.i(TAG, "[boot] " + message);
        runOnUiThread(() -> statusView.setText(message));
    }

    private void showBootError(String message) {
        statusView.setTextColor(getColor(R.color.splash_text_error));
        statusView.setText(message);
        progressBar.setVisibility(View.INVISIBLE);
    }

    private void revealWebView() {
        if (splashHidden || splashView == null) return;
        splashHidden = true;
        splashView.animate()
                .alpha(0f)
                .setDuration(280)
                .withEndAction(() -> splashView.setVisibility(View.GONE))
                .start();
    }

    private boolean waitForServer(long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (NodeRuntime.hasExited()) return false;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(HEALTH_URL).openConnection();
                connection.setConnectTimeout(800);
                connection.setReadTimeout(800);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                connection.disconnect();
                if (code == 200) return true;
            } catch (IOException ignored) {
                // 服务还没起来，继续等。
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isLocalUrl(Uri uri) {
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    /** Android 13 及以上要运行时申请通知与音频库权限；没有它们媒体卡只在锁屏活着、本机音乐扫不出来。 */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            String[] wanted = {
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.READ_MEDIA_AUDIO,
            };
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String permission : wanted) {
                if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), 9001);
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 9001);
        }
    }

    /** 任何线程都能调：把一段 JS 丢进页面（主线程执行）。 */
    static void evaluateJs(String script) {
        MainActivity activity = activityRef == null ? null : activityRef.get();
        if (activity == null || activity.webView == null) return;
        activity.runOnUiThread(() -> activity.webView.evaluateJavascript(script, null));
    }

    /** 页面（foliaNative.setOrientation）或启动时调：应用显示模式，横屏为默认。 */
    static void applyDisplayMode(String mode) {
        MainActivity activity = activityRef == null ? null : activityRef.get();
        String safeMode = mode == null ? "landscape" : mode;
        Context context = activity != null ? activity : null;
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(DISPLAY_MODE_PREF, Context.MODE_PRIVATE);
            prefs.edit().putString(DISPLAY_MODE_KEY, safeMode).apply();
        }
        applyOrientation(safeMode, activity);
    }

    private static void applyOrientation(String mode, Activity activity) {
        if (activity == null) return;
        int orientation;
        switch (mode) {
            case "portrait":
                orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case "system":
                orientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
                break;
            default:
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
        activity.runOnUiThread(() -> activity.setRequestedOrientation(orientation));
    }

    private static String storedDisplayMode(Context context) {
        return context.getSharedPreferences(DISPLAY_MODE_PREF, Context.MODE_PRIVATE)
                .getString(DISPLAY_MODE_KEY, "landscape");
    }

    private static WeakReference<MainActivity> activityRef = new WeakReference<>(null);

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException error) {
            Log.w(TAG, "[link] nothing to open " + uri, error);
        }
    }

    /**
     * Android 15 起窗口强制铺满（edge-to-edge），状态栏、导航栏、输入法都会盖在内容上。
     * 这里把它们的高度补成 padding，页面拿到的是真正的可用区域。
     */
    private void applySafeAreaInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = Math.max(bars.left, ime.left);
                top = bars.top;
                right = Math.max(bars.right, ime.right);
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                    || view.getPaddingRight() != right || view.getPaddingBottom() != bottom) {
                view.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
