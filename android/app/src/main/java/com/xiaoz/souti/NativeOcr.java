package com.xiaoz.souti;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/** 用 Android 原生任务执行中文文字识别，避免后台 WebView/WASM Worker 被冻结。 */
public final class NativeOcr {
    private static final String TAG = "SoutiCapture";
    private NativeOcr() {}

    public static void recognize(Context context, Bitmap bitmap) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                    .process(image)
                    .addOnSuccessListener(result -> {
                        String text = result.getText() == null ? "" : result.getText().trim();
                        Log.i(TAG, "NativeOcr: complete " + text.length() + " chars");
                        if (text.isEmpty()) NativeSearchOverlay.showError("未识别到文字，请重新框选题干");
                        else MainActivity.searchTextFromOverlay(text);
                    })
                    .addOnFailureListener(error -> {
                        Log.e(TAG, "NativeOcr: failed", error);
                        NativeSearchOverlay.showError("原生识别失败：" + error.getMessage());
                    });
        } catch (Exception error) {
            Log.e(TAG, "NativeOcr: start failed", error);
            NativeSearchOverlay.showError("无法启动原生识别：" + error.getMessage());
        }
    }
}
