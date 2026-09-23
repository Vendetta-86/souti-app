package com.xiaoz.souti;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import java.nio.ByteBuffer;

/** 保持一个 MediaProjection 会话：首击授权，之后每次悬浮搜索都直接取新一帧。 */
public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final int NOTIFICATION_ID = 4102;
    private static final String CHANNEL_ID = "screen_capture";
    private static final String TAG = "SoutiCapture";
    public static final String ACTION_CAPTURE = "com.xiaoz.souti.CAPTURE";

    private static volatile boolean running;
    private static Context appContext;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean captureRequested;
    private boolean sessionFinished;

    public static boolean isRunning() { return running; }
    public static Context getAppContext() { return appContext; }
    public static void requestCapture(Context context) {
        Intent capture = new Intent(context, ScreenCaptureService.class).setAction(ACTION_CAPTURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(capture);
        else context.startService(capture);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        appContext = getApplicationContext();
        if (intent != null && ACTION_CAPTURE.equals(intent.getAction())) {
            captureRequested = true;
            Log.i(TAG, "ScreenCaptureService: capture requested using existing grant");
            return START_NOT_STICKY;
        }
        if (intent == null) {
            finishSession();
            return START_NOT_STICKY;
        }
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("本地搜题")
                .setContentText("截图服务已就绪")
                .setOngoing(true)
                .build();
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        Log.i(TAG, "ScreenCaptureService: foreground started");
        // 首次只建立并保留授权会话；必须由用户再次点悬浮按钮才开始截取。
        captureRequested = false;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            finishSession();
            return START_NOT_STICKY;
        }
        // CaptureActivity 需要先把本任务退到后台，重新露出用户刚才的题目 App。
        // 如果立刻创建 VirtualDisplay，首帧会错误截到本地搜题自己的 WebView。
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> startProjection(resultCode, resultData), 800);
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager == null) throw new IllegalStateException("MediaProjection 不可用");
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("未取得截屏授权");
            Log.i(TAG, "ScreenCaptureService: MediaProjection obtained");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { finishSession(); }
            }, new Handler(Looper.getMainLooper()));

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = getSystemService(WindowManager.class);
            if (windowManager == null) throw new IllegalStateException("WindowManager 不可用");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
                metrics.widthPixels = bounds.width();
                metrics.heightPixels = bounds.height();
                metrics.densityDpi = getResources().getConfiguration().densityDpi;
            } else {
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
            }
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels,
                    PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, new Handler(Looper.getMainLooper()));
            virtualDisplay = projection.createVirtualDisplay("SoutiOneShotCapture",
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
            Log.i(TAG, "ScreenCaptureService: virtual display created " + metrics.widthPixels + "x" + metrics.heightPixels);
            running = true;
        } catch (Exception error) {
            Log.e(TAG, "Unable to start MediaProjection", error);
            finishSession();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        if (!captureRequested) {
            image.close();
            return;
        }
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride,
                    image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            captureRequested = false;
            padded.recycle();
            Log.i(TAG, "ScreenCaptureService: frame ready for native crop overlay");
            NativeSearchOverlay.showCrop(this, bitmap);
        } catch (Exception error) {
            Log.e(TAG, "Unable to prepare captured frame", error);
            NativeSearchOverlay.showError("截图处理失败：" + error.getMessage());
        } finally {
            image.close();
        }
    }

    private synchronized void finishSession() {
        if (sessionFinished) return;
        sessionFinished = true;
        running = false;
        captureRequested = false;
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() { finishSession(); super.onDestroy(); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "一次性截屏",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
