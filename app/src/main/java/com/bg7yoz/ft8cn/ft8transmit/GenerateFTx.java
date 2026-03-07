package com.bg7yoz.ft8cn.ft8transmit;

import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;

/**
 * FT8 / FT4 统一发射入口
 * Java 层统一调这里，JNI 后续只需要实现 generateFtXNative(...)
 */
public final class GenerateFTx {
    private static final String TAG = "GenerateFTx";

    static {
        System.loadLibrary("ft8cn");
    }

    private GenerateFTx() {
    }

    /**
     * 按消息自身 signalFormat 生成音频
     */
    public static float[] generateFtX(Ft8Message msg, float frequency, int sampleRate) {
        if (msg == null) {
            return null;
        }
        return generateFtX(msg, frequency, sampleRate, msg.signalFormat);
    }

    /**
     * 按指定模式生成音频
     */
    public static float[] generateFtX(Ft8Message msg, float frequency, int sampleRate, int mode) {
        if (msg == null) {
            return null;
        }
        msg.signalFormat = mode;
        return generateFtXNative(msg, frequency, sampleRate, mode);
    }

    /**
     * 是否支持当前模式
     */
    public static boolean supportMode(int mode) {
        return mode == FT8Common.FT8_MODE || mode == FT8Common.FT4_MODE;
    }

    /**
     * native 统一生成接口
     *
     * @param msg        消息对象
     * @param frequency  音频频率
     * @param sampleRate 采样率
     * @param mode       FT8Common.FT8_MODE / FT8Common.FT4_MODE
     * @return float[] PCM 数据
     */
    private static native float[] generateFtXNative(Ft8Message msg, float frequency, int sampleRate, int mode);
}