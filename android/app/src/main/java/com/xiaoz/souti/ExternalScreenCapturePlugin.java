package com.xiaoz.souti;

import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/** 将系统截屏流程和截得的 JPEG 暴露给 Web 端。 */
@CapacitorPlugin(name = "ExternalScreenCapture")
public class ExternalScreenCapturePlugin extends Plugin {
    @PluginMethod
    public void capture(PluginCall call) {
        try {
            CaptureActivity.start(getContext());
            call.resolve();
        } catch (Exception error) {
            call.reject("无法启动系统截屏授权", error);
        }
    }

    @PluginMethod
    public void readLatest(PluginCall call) {
        File file = CaptureStore.take(getContext());
        if (file == null || !file.isFile()) {
            call.reject("没有待处理的系统截图");
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try (FileInputStream input = new FileInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            String base64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            file.delete();
            JSObject result = new JSObject();
            result.put("base64", base64);
            result.put("mimeType", "image/jpeg");
            call.resolve(result);
        } catch (Exception error) {
            file.delete();
            call.reject("读取系统截图失败", error);
        }
    }

    /** 后台 WebView 完成本地 OCR 后，直接把结果显示在第三方做题 App 的上层。 */
    @PluginMethod
    public void showOverlayResults(PluginCall call) {
        String payload = call.getString("payload", "");
        NativeSearchOverlay.showResults(payload);
        call.resolve();
    }

    @PluginMethod
    public void showOverlayError(PluginCall call) {
        NativeSearchOverlay.showError(call.getString("message", "识别或搜题失败"));
        call.resolve();
    }
}
