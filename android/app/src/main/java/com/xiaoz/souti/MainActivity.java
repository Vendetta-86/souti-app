package com.xiaoz.souti;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int FLOATING_PERMISSION_CODE = 1001;
    private FloatingSearchView floatingSearchView;
    private static MainActivity instance;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 必须在 BridgeActivity 创建 bridge 前注册，才能从 Web 端调用自定义截图插件。
        registerPlugin(ExternalScreenCapturePlugin.class);
        super.onCreate(savedInstanceState);
        instance = this;

        // 延迟初始化浮窗，确保 bridge 已就绪
        postInit();
    }

    private void postInit() {
        try {
            initFloatingSearch();
        } catch (Exception e) {
            // 浮窗初始化失败不影响主流程
            e.printStackTrace();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // App 回到前台时，确保浮窗可见
        if (floatingSearchView != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        floatingSearchView.show();
                    }
                } else {
                    floatingSearchView.show();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void initFloatingSearch() {
        floatingSearchView = new FloatingSearchView(this);
        floatingSearchView.setOnCaptureListener(() -> {
            floatingSearchView.hide();
            // 已取得的 MediaProjection 在本次 App 会话内持续保留；后续点击无需重复授权。
            if (ScreenCaptureService.isRunning()) {
                ScreenCaptureService.requestCapture(this);
            } else {
                CaptureActivity.start(this);
            }
        });

        // 检查悬浮权限
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    floatingSearchView.show();
                } else {
                    requestFloatingPermission();
                }
            } else {
                floatingSearchView.show();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void requestFloatingPermission() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityForResult(intent, FLOATING_PERMISSION_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FLOATING_PERMISSION_CODE) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        floatingSearchView.show();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(CaptureActivity.EXTRA_CAPTURE_READY, false)) {
            notifyWebCaptureReady();
        }
    }

    private void notifyWebCaptureReady() {
        if (bridge == null || bridge.getWebView() == null) return;
        bridge.getWebView().post(() -> bridge.getWebView().evaluateJavascript(
                "window.__souti_native_capture_ready && window.__souti_native_capture_ready();", null));
    }

    /** 原生裁剪层把图片交给后台 WebView 做本地 OCR 和题库匹配，不抢回前台。 */
    public static void searchFromOverlay(String base64) {
        MainActivity current = instance;
        if (current == null || current.bridge == null || current.bridge.getWebView() == null) {
            NativeSearchOverlay.showError("搜题引擎未就绪，请先打开一次本地搜题");
            return;
        }
        current.bridge.getWebView().post(() -> {
            // MainActivity 在用户做题时处于后台。部分 Android 系统会暂停后台 WebView 的 Worker/
            // timer；主动恢复它，避免“切回本 App 才突然出结果”。
            current.bridge.getWebView().onResume();
            current.bridge.getWebView().resumeTimers();
            Log.i("SoutiCapture", "MainActivity: resuming background WebView for overlay search");
            current.bridge.getWebView().evaluateJavascript(
                    "window.__souti_overlay_search && window.__souti_overlay_search(" + JSONObject.quote(base64) + ");", null);
        });
    }

    /** 原生 OCR 完成后，只把纯文本交给 WebView 做 IndexedDB 题库匹配。 */
    public static void searchTextFromOverlay(String text) {
        MainActivity current = instance;
        if (current == null || current.bridge == null || current.bridge.getWebView() == null) {
            NativeSearchOverlay.showError("题库引擎未就绪，请先打开一次本地搜题");
            return;
        }
        current.bridge.getWebView().post(() -> {
            current.bridge.getWebView().onResume();
            current.bridge.getWebView().resumeTimers();
            Log.i("SoutiCapture", "MainActivity: matching native OCR text in local bank");
            current.bridge.getWebView().evaluateJavascript(
                    "window.__souti_overlay_match && window.__souti_overlay_match(" + JSONObject.quote(text) + ");", null);
        });
    }

    public static void showFloatingButton() {
        MainActivity current = instance;
        if (current != null && current.floatingSearchView != null) {
            current.runOnUiThread(() -> current.floatingSearchView.show());
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
