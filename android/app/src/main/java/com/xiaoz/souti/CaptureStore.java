package com.xiaoz.souti;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** 在一次性前台截屏服务与 Capacitor WebView 之间短暂交接截图文件。 */
public final class CaptureStore {
    private static final String PREFS = "souti_capture";
    private static final String KEY_PENDING_PATH = "pending_path";

    private CaptureStore() {}

    public static synchronized void put(Context context, File file) {
        File previous = getFile(context);
        if (previous != null && !previous.equals(file)) previous.delete();
        prefs(context).edit().putString(KEY_PENDING_PATH, file.getAbsolutePath()).apply();
    }

    public static synchronized File take(Context context) {
        File file = getFile(context);
        prefs(context).edit().remove(KEY_PENDING_PATH).apply();
        return file;
    }

    private static File getFile(Context context) {
        String path = prefs(context).getString(KEY_PENDING_PATH, null);
        return path == null ? null : new File(path);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
