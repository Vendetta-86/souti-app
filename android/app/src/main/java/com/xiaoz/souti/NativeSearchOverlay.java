package com.xiaoz.souti;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/** 在其他 App 上方显示裁剪和结果，避免把用户带回本地搜题主界面。 */
public final class NativeSearchOverlay {
    private static WindowManager windowManager;
    private static View overlay;
    private static View notice;
    private static Bitmap currentBitmap;
    private static final int BLUE = Color.rgb(47, 111, 237);

    private NativeSearchOverlay() {}

    public static void showCrop(Context context, Bitmap bitmap) {
        close();
        currentBitmap = bitmap;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        ImageView image = new ImageView(context);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        CropSelectionView selection = new CropSelectionView(context);
        root.addView(selection, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        bar.setBackgroundColor(0xeF172033);
        TextView tip = text(context, "拖动或拉伸蓝色选框，框住题干后点击搜索", 14, Color.WHITE);
        bar.addView(tip, new LinearLayout.LayoutParams(0, -2, 1));
        Button cancel = button(context, "取消", Color.DKGRAY);
        Button search = button(context, "搜索", BLUE);
        bar.addView(cancel); bar.addView(search);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bar, barParams);
        cancel.setOnClickListener(v -> { close(); MainActivity.showFloatingButton(); });
        search.setOnClickListener(v -> {
            Bitmap crop = selection.crop(currentBitmap);
            if (crop == null) return;
            search.setEnabled(false);
            tip.setText("正在识别并匹配本地题库…");
            close();
            showSearching(context);
            NativeOcr.recognize(context, crop);
        });
        show(context, root);
    }

    public static void showResults(String payload) {
        Context context = appContext();
        if (context == null) return;
        close();
        try {
            JSONObject data = new JSONObject(payload);
            JSONArray matches = data.optJSONArray("matches");
            FrameLayout root = new FrameLayout(context);
            root.setBackgroundColor(0x55000000);
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 12));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(context, 16));
            card.setBackground(bg);
            LinearLayout titleRow = new LinearLayout(context); titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(text(context, "搜题结果", 20, Color.rgb(25, 35, 58)), new LinearLayout.LayoutParams(0, -2, 1));
            Button close = button(context, "关闭", Color.DKGRAY); titleRow.addView(close);
            card.addView(titleRow);
            TextView recognized = text(context, "识别：" + data.optString("text", ""), 12, Color.DKGRAY);
            recognized.setMaxLines(2); card.addView(recognized);
            ScrollView scroll = new ScrollView(context);
            LinearLayout list = new LinearLayout(context); list.setOrientation(LinearLayout.VERTICAL);
            if (matches == null || matches.length() == 0) {
                list.addView(text(context, "没有足够接近的题目，请关闭后重新框选题干。", 16, Color.DKGRAY));
            } else for (int i = 0; i < matches.length(); i++) {
                JSONObject item = matches.getJSONObject(i);
                LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(context, 14), 0, dp(context, 10));
                String question = item.optString("question");
                String answer = item.optString("answer");
                row.addView(text(context, question, 16, Color.rgb(20, 28, 45)));
                LinearLayout answerRow = new LinearLayout(context);
                answerRow.setGravity(Gravity.CENTER_VERTICAL);
                answerRow.addView(text(context, "答案：" + answer, 17, Color.rgb(18, 126, 69)), new LinearLayout.LayoutParams(0, -2, 1));
                Button copyAnswer = compactButton(context, "复制答案");
                copyAnswer.setOnClickListener(v -> copy(context, answer, "复制成功"));
                answerRow.addView(copyAnswer);
                row.addView(answerRow);
                JSONArray options = item.optJSONArray("options");
                if (options != null) for (int j = 0; j < options.length(); j++) {
                    String option = options.optString(j).replaceFirst("^[A-Z][.．、:：\\-\\s]*", "");
                    row.addView(text(context, (char)('A' + j) + ". " + option, 14, Color.DKGRAY));
                }
                row.addView(text(context, "匹配度 " + Math.round(item.optDouble("score") * 100) + "%", 12, Color.GRAY));
                list.addView(row);
            }
            scroll.addView(list); card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(card, new FrameLayout.LayoutParams(-1, dp(context, 620), Gravity.CENTER));
            close.setOnClickListener(v -> { close(); MainActivity.showFloatingButton(); });
            show(context, root);
        } catch (Exception error) { showError("结果显示失败：" + error.getMessage()); }
    }

    public static void showError(String message) {
        Context context = appContext(); if (context == null) return;
        close();
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(context, 16)); root.setBackground(bg);
        root.addView(text(context, "搜题未完成", 20, Color.rgb(25, 35, 58)));
        root.addView(text(context, message, 16, Color.DKGRAY));
        Button close = button(context, "关闭", BLUE); root.addView(close);
        close.setOnClickListener(v -> { close(); MainActivity.showFloatingButton(); }); show(context, root);
    }

    /** 悬浮窗环境中系统 Toast 常被厂商系统隐藏，改用自身的短暂提示条。 */
    public static void showNotice(Context context, String message) {
        if (context == null) return;
        if (windowManager == null) windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;
        dismissNotice();
        TextView label = text(context, message, 15, Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xdd202938); bg.setCornerRadius(dp(context, 22)); label.setBackground(bg);
        notice = label;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(context, 210), dp(context, 52),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(context, 88);
        try {
            windowManager.addView(notice, params);
            new Handler(Looper.getMainLooper()).postDelayed(NativeSearchOverlay::dismissNotice, 1600);
        } catch (Exception ignored) { notice = null; }
    }

    private static void showSearching(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(context, 16)); root.setBackground(bg);
        root.addView(text(context, "正在识别题目…", 18, Color.rgb(25, 35, 58)));
        root.addView(text(context, "正在本地匹配答案，请稍候", 14, Color.DKGRAY));
        show(context, root, dp(context, 300), dp(context, 112));
    }

    private static Context appContext() { return ScreenCaptureService.getAppContext(); }
    private static void show(Context context, View view) {
        show(context, view, -1, -1);
    }
    private static void show(Context context, View view, int width, int height) {
        if (windowManager == null) windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        overlay = view;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        if (width != -1 || height != -1) params.gravity = Gravity.CENTER;
        try { windowManager.addView(overlay, params); } catch (Exception ignored) { overlay = null; }
    }
    public static void close() { if (overlay != null && windowManager != null) try { windowManager.removeView(overlay); } catch (Exception ignored) {} overlay = null; }
    private static void dismissNotice() { if (notice != null && windowManager != null) try { windowManager.removeView(notice); } catch (Exception ignored) {} notice = null; }
    private static TextView text(Context c, String value, int sp, int color) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setPadding(0, dp(c, 4), 0, dp(c, 4));
        // 悬浮结果中的题干、答案和选项均可长按选中，使用系统复制菜单。
        v.setTextIsSelectable(true);
        v.setLongClickable(true);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        return v;
    }
    private static Button button(Context c, String value, int color) { Button b = new Button(c); b.setText(value); b.setTextColor(Color.WHITE); b.setTextSize(14); GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, 8)); b.setBackground(g); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(c, 72), dp(c, 44)); p.setMargins(dp(c, 6), 0, 0, 0); b.setLayoutParams(p); return b; }
    private static Button compactButton(Context c, String value) { Button b = button(c, value, BLUE); b.setTextSize(12); b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0); b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 116), dp(c, 38))); return b; }
    private static void copy(Context c, String value, String message) { ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("本地搜题", value)); showNotice(c, message); }
    private static int dp(Context c, int value) { return (int) (value * c.getResources().getDisplayMetrics().density + .5f); }

    private static final class CropSelectionView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private final RectF rect = new RectF(); private float lastX, lastY; private int mode;
        CropSelectionView(Context c) { super(c); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(c, 3)); paint.setColor(BLUE); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { rect.set(w * .06f, h * .22f, w * .94f, h * .72f); }
        @Override protected void onDraw(Canvas c) { super.onDraw(c); Paint dim = new Paint(); dim.setColor(0x77000000); c.drawRect(0, 0, getWidth(), rect.top, dim); c.drawRect(0, rect.bottom, getWidth(), getHeight(), dim); c.drawRect(0, rect.top, rect.left, rect.bottom, dim); c.drawRect(rect.right, rect.top, getWidth(), rect.bottom, dim); c.drawRect(rect, paint); paint.setStyle(Paint.Style.FILL); c.drawCircle(rect.left, rect.top, 14, paint); c.drawCircle(rect.right, rect.bottom, 14, paint); paint.setStyle(Paint.Style.STROKE); }
        @Override public boolean onTouchEvent(MotionEvent e) { float x=e.getX(), y=e.getY(), edge=dp(getContext(), 34); if(e.getAction()==MotionEvent.ACTION_DOWN){ lastX=x;lastY=y; mode=(Math.abs(x-rect.left)<edge?1:0)|(Math.abs(x-rect.right)<edge?2:0)|(Math.abs(y-rect.top)<edge?4:0)|(Math.abs(y-rect.bottom)<edge?8:0); if(mode==0 && rect.contains(x,y)) mode=16; return true;} if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=x-lastX,dy=y-lastY;if(mode==16){rect.offset(dx,dy); if(rect.left<0)rect.offset(-rect.left,0);if(rect.right>getWidth())rect.offset(getWidth()-rect.right,0);if(rect.top<0)rect.offset(0,-rect.top);if(rect.bottom>getHeight())rect.offset(0,getHeight()-rect.bottom);}else{if((mode&1)!=0)rect.left=Math.min(x,rect.right-edge);if((mode&2)!=0)rect.right=Math.max(x,rect.left+edge);if((mode&4)!=0)rect.top=Math.min(y,rect.bottom-edge);if((mode&8)!=0)rect.bottom=Math.max(y,rect.top+edge);}lastX=x;lastY=y;invalidate();return true;} return true; }
        Bitmap crop(Bitmap source) { if(source==null || getWidth()==0 || getHeight()==0)return null; int l=Math.max(0,Math.round(rect.left/getWidth()*source.getWidth())),t=Math.max(0,Math.round(rect.top/getHeight()*source.getHeight())),r=Math.min(source.getWidth(),Math.round(rect.right/getWidth()*source.getWidth())),b=Math.min(source.getHeight(),Math.round(rect.bottom/getHeight()*source.getHeight())); return Bitmap.createBitmap(source,l,t,Math.max(1,r-l),Math.max(1,b-t)); }
    }
}
