package com.xiaoz.souti;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * 从悬浮窗启动的透明 Activity。系统的 MediaProjection 授权界面必须由 Activity 发起，
 * 透明主题确保用户在授权前仍能看到原先的题目页面。
 */
public class CaptureActivity extends Activity {
    public static final String EXTRA_CAPTURE_READY = "capture_ready";
    private static final int REQUEST_MEDIA_PROJECTION = 4101;
    private static final String TAG = "SoutiCapture";

    public static void start(Context context) {
        Intent intent = new Intent(context, CaptureActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "CaptureActivity: requesting MediaProjection consent");
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            Toast.makeText(this, "设备不支持系统截屏", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // 对 Android 14+ 明确请求整个显示屏，避免系统额外跳入“选择要共享的应用”页。
        // 这是一次性静态截图，用户仍会看到并确认标准的系统授权提示。
        Intent request = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(request, REQUEST_MEDIA_PROJECTION);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MEDIA_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "CaptureActivity: consent denied");
            Toast.makeText(this, "未授权截屏", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Intent service = new Intent(this, ScreenCaptureService.class)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        Log.i(TAG, "CaptureActivity: projection service started");
        // 此 Activity 与主界面可能位于同一个任务中。把任务退到后台后，服务创建虚拟显示时
        // 才会看到用户刚才做题的第三方 App，而非本地搜题的 WebView。
        moveTaskToBack(true);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            MainActivity.showFloatingButton();
            NativeSearchOverlay.showNotice(getApplicationContext(), "授权成功");
        }, 500);
        finish();
    }
}
