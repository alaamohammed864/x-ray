package com.alaamohammed.xraymirror;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "XRayMirror";
    private static final String HOST = "appassets.androidplatform.net";
    // The loader strips "/assets/" from the URL and passes the remainder ("index.html", "libs/...") to the handler.
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int BG = Color.rgb(7, 21, 34);
    private static final int CAMERA_REQUEST = 4102;

    /** Serves files from assets/www with explicit MIME types (needed for ES modules and WebAssembly). */
    private static final class OfflineAssetsPathHandler implements WebViewAssetLoader.PathHandler {
        private final AssetManager assets;
        OfflineAssetsPathHandler(Context context) { assets = context.getAssets(); }

        @Override public WebResourceResponse handle(String path) {
            if (path == null || path.isEmpty() || path.contains("..") || path.startsWith("/")) return null;
            try {
                InputStream input = assets.open("www/" + path, AssetManager.ACCESS_STREAMING);
                String lower = path.toLowerCase(Locale.ROOT);
                String mime; String encoding = null;
                if (lower.endsWith(".wasm")) mime = "application/wasm";
                else if (lower.endsWith(".mjs") || lower.endsWith(".js")) { mime = "text/javascript"; encoding = "UTF-8"; }
                else if (lower.endsWith(".html")) { mime = "text/html"; encoding = "UTF-8"; }
                else if (lower.endsWith(".css")) { mime = "text/css"; encoding = "UTF-8"; }
                else if (lower.endsWith(".json")) { mime = "application/json"; encoding = "UTF-8"; }
                else mime = "application/octet-stream";           // .task model files
                return new WebResourceResponse(mime, encoding, input);
            } catch (IOException error) {
                Log.w(TAG, "Missing asset: www/" + path);
                return null;
            }
        }
    }

    /** WebView cannot download blob: URLs, so the page hands recorded video to native code. */
    private final class AndroidBridge {
        @JavascriptInterface public boolean saveVideo(String base64, String name, String mime) {
            try {
                if (base64 == null || base64.isEmpty()) return false;
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String safeName = (name == null ? "xray-mirror.webm" : name).replaceAll("[^A-Za-z0-9._-]", "_");
                String type = (mime != null && mime.startsWith("video/")) ? mime : "video/webm";
                boolean ok = Build.VERSION.SDK_INT >= 29 ? saveWithMediaStore(data, safeName, type) : saveToAppFolder(data, safeName);
                return ok;
            } catch (Exception error) {
                Log.e(TAG, "saveVideo failed", error);
                return false;
            }
        }
    }

    private boolean saveWithMediaStore(byte[] data, String name, String type) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, type);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XRayMirror");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return false;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) { resolver.delete(uri, null, null); return false; }
            out.write(data);
        } catch (IOException error) {
            resolver.delete(uri, null, null);
            throw error;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return true;
    }

    // Android 9 and below: app-specific external folder, no storage permission required.
    private boolean saveToAppFolder(byte[] data, String name) throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return false;
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { out.write(data); }
        return true;
    }

    private WebView webView;
    private PermissionRequest pendingRequest;
    private WebViewAssetLoader assetLoader;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        useLightSystemBarIcons();

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);   // chrome://inspect works for debug APKs only
        }

        assetLoader = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", new OfflineAssetsPathHandler(this))
            .build();

        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            // Only the bundled app origin may be shown; the JS bridge must never be exposed to anything else.
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !("https".equals(uri.getScheme()) && HOST.equals(uri.getHost()));
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean asksCamera = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) asksCamera = true;
                    }
                    Uri origin = request.getOrigin();
                    boolean trustedOrigin = origin != null
                        && "https".equals(origin.getScheme())
                        && HOST.equals(origin.getHost());
                    if (!asksCamera || !trustedOrigin) { request.deny(); return; }
                    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        if (pendingRequest != null) pendingRequest.deny();
                        pendingRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
                    } else {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingRequest == request) pendingRequest = null;
            }
        });

        // Android 15 (targetSdk 35) draws edge-to-edge: pad the content so the page is not under the system bars.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        webView.loadUrl(START_URL);
    }

    @SuppressWarnings("deprecation")
    private void useLightSystemBarIcons() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0)));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST && pendingRequest != null) {
            PermissionRequest request = pendingRequest;
            pendingRequest = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                request.deny();
                Toast.makeText(this, "الكاميرا مطلوبة لتشغيل التتبع", Toast.LENGTH_LONG).show();
            }
        }
    }

    // Release the camera whenever the app leaves the foreground, and restore it on return.
    @Override protected void onPause() {
        if (webView != null) {
            webView.evaluateJavascript("window.__xrayPause&&window.__xrayPause()", null);
            webView.onPause();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.evaluateJavascript("window.__xrayResume&&window.__xrayResume()", null);
        }
    }

    @Override protected void onDestroy() {
        if (webView != null) { webView.removeJavascriptInterface("AndroidBridge"); webView.loadUrl("about:blank"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
