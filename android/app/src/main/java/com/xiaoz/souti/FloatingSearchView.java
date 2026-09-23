package com.xiaoz.souti;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class FloatingSearchView {

    private View floatingView;
    private WindowManager windowManager;
    private Activity activity;
    private boolean shown = false;
    private OnCaptureListener listener;
    private WindowManager.LayoutParams layoutParams;
    private static final String PREFS = "floating_search_position";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";

    public interface OnCaptureListener {
        void onCapture();
    }

    public FloatingSearchView(Activity activity) {
        this.activity = activity;
    }

    public void setOnCaptureListener(OnCaptureListener listener) {
        this.listener = listener;
    }

    public void show() {
        if (shown || activity == null) return;
        windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        int size = dpToPx(24);

        layoutParams = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        // 使用屏幕左上坐标，拖动时位置计算直观、不会受 Bottom/End 方向反转影响。
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences saved = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        layoutParams.x = saved.getInt(KEY_X, dpToPx(16));
        layoutParams.y = saved.getInt(KEY_Y, dpToPx(300));

        floatingView = createButton();
        try {
            windowManager.addView(floatingView, layoutParams);
            shown = true;
        } catch (Exception e) {
            e.printStackTrace();
            shown = false;
        }
    }

    public void hide() {
        if (!shown) return;
        try {
            windowManager.removeView(floatingView);
        } catch (Exception ignored) {}
        floatingView = null;
        shown = false;
    }

    public boolean isShown() {
        return shown;
    }

    private int dpToPx(int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private View createButton() {
        TextView tv = new TextView(activity);
        tv.setTextSize(19);
        tv.setText("\uD83D\uDD0D"); // 🔍
        tv.setGravity(Gravity.CENTER);
        tv.setContentDescription("搜题");
        tv.setBackgroundColor(Color.TRANSPARENT);
        tv.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = layoutParams.x; startY = layoutParams.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                        if (Math.abs(dx) > dpToPx(3) || Math.abs(dy) > dpToPx(3)) moved = true;
                        if (moved) {
                            layoutParams.x = Math.max(0, startX + Math.round(dx));
                            layoutParams.y = Math.max(0, startY + Math.round(dy));
                            try { windowManager.updateViewLayout(floatingView, layoutParams); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                    .putInt(KEY_X, layoutParams.x).putInt(KEY_Y, layoutParams.y).apply();
                        } else if (listener != null) listener.onCapture();
                        return true;
                    default:
                        return true;
                }
            }
        });

        return tv;
    }
}
