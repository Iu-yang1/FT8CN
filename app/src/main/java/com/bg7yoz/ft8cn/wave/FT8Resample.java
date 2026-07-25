package com.bg7yoz.ft8cn.wave;

import android.util.Log;

/** 音频重采样 JNI 入口，并提供与 native 等价的安全 fallback。 */
public final class FT8Resample {
    private static final String TAG = "FT8Resample";

    static {
        System.loadLibrary("ft8cn");
    }

    private FT8Resample() {
    }

    public static native short[] get16Resample16(short[] inputData,
                                                  int inputRate,
                                                  int outputRate,
                                                  int channels);

    public static native float[] get32Resample16(short[] inputData,
                                                  int inputRate,
                                                  int outputRate,
                                                  int channels);

    public static native short[] get16Resample32(float[] inputData,
                                                  int inputRate,
                                                  int outputRate,
                                                  int channels);

    public static native float[] get32Resample32(float[] inputData,
                                                  int inputRate,
                                                  int outputRate,
                                                  int channels);

    public static native byte[] get8Resample16(short[] inputData,
                                                int inputRate,
                                                int outputRate,
                                                int channels);

    public static native byte[] get8Resample32(float[] inputData,
                                                int inputRate,
                                                int outputRate,
                                                int channels);

    /**
     * 解码链路优先使用 native 直接输出抽取；JNI 不可用时才走 Java 实现。
     * 不支持的采样率返回 null，由调用方明确跳过，不能误送给 12 kHz decoder。
     */
    public static float[] resampleFloatToFloatSafe(float[] inputData,
                                                   int inputRate,
                                                   int outputRate,
                                                   int channels) {
        if (inputData == null || inputData.length == 0
                || inputRate <= 0 || outputRate <= 0 || channels != 1) {
            return null;
        }
        if (inputRate == outputRate) {
            return inputData;
        }

        try {
            float[] nativeOutput = get32Resample32(inputData, inputRate, outputRate, channels);
            if (nativeOutput != null && nativeOutput.length > 0) {
                return nativeOutput;
            }
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "native resampler unavailable; using Java fallback", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "native resampler failed; using Java fallback", error);
        }
        return FtxResampler.resampleMono(inputData, inputRate, outputRate);
    }
}
