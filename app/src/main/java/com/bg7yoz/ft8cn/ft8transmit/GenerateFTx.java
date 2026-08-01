package com.bg7yoz.ft8cn.ft8transmit;

import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.experimental.ExperimentalCodecEngine;

import java.util.Locale;

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

    private static String buildQ65TxContext(Ft8Message msg,
                                            float frequency,
                                            int sampleRate,
                                            int q65Submode,
                                            int q65TrPeriodSeconds) {
        return String.format(
                Locale.US,
                "mode=%s, submode=%s, trPeriod=%d, sampleRate=%d, f=%.1f, text=%s",
                FT8Common.modeToString(FT8Common.Q65_MODE),
                FT8Common.getQ65SubmodeLabel(q65Submode),
                q65TrPeriodSeconds,
                sampleRate,
                frequency,
                msg == null ? "" : msg.getMessageText()
        );
    }

    private static String buildWaveStats(float[] wave, int sampleRate) {
        if (wave == null || wave.length == 0) {
            return "samples=0, durationMs=0.0, peak=0.000000, rms=0.000000";
        }
        float peak = 0.0f;
        double energy = 0.0;
        for (float sample : wave) {
            float abs = Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
            energy += sample * sample;
        }
        double rms = Math.sqrt(energy / wave.length);
        float durationMs = sampleRate > 0 ? wave.length * 1000.0f / sampleRate : 0.0f;
        return String.format(
                Locale.US,
                "samples=%d, durationMs=%.1f, peak=%.6f, rms=%.6f",
                wave.length,
                durationMs,
                peak,
                rms
        );
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

        if (!supportMode(mode)) {
            Log.w(TAG, "Unsupported TX mode request: mode=" + mode + ", text=" + msg.getMessageText());
            return null;
        }

        if (GeneralVariables.isExperimentalCodecEnabled() && mode != FT8Common.Q65_MODE) {
            // experimental 模式下，发射链直接切到实验调制器，避免混用 FT8/FT4 标准波形。
            Log.d(TAG, String.format(
                    "EXP TX active: codecMode=%d, txMode=%s, sampleRate=%d, f=%.1f",
                    GeneralVariables.experimentalCodecMode,
                    FT8Common.modeToString(mode),
                    sampleRate,
                    frequency
            ));
            return ExperimentalCodecEngine.generateTxWave(
                    msg,
                    frequency,
                    sampleRate,
                    mode,
                    GeneralVariables.experimentalCodecMode
            );
        }
        if (GeneralVariables.isExperimentalCodecEnabled() && mode == FT8Common.Q65_MODE) {
            Log.i(TAG, "Experimental codec is enabled, but Q65 TX stays on native WSJT-X bridge");
        }

        int q65Submode = GeneralVariables.getQ65Submode();
        int q65TrPeriodSeconds = GeneralVariables.getQ65TrPeriodSeconds();
        float[] generated = generateFtXNative(
                msg,
                frequency,
                sampleRate,
                mode,
                q65Submode,
                q65TrPeriodSeconds
        );
        if (mode == FT8Common.Q65_MODE) {
            if (generated == null || generated.length == 0) {
                Log.e(TAG, "Q65 TX waveform generation failed: reason=native-returned-empty, "
                        + buildQ65TxContext(msg, frequency, sampleRate, q65Submode, q65TrPeriodSeconds));
                return generated;
            }
            Log.i(TAG, String.format(
                    Locale.US,
                    "Q65 TX experimental: mode=%s, submode=%s, trPeriod=%d, sampleRate=%d, f=%.1f, %s, text=%s",
                    FT8Common.modeToString(mode),
                    FT8Common.getQ65SubmodeLabel(q65Submode),
                    q65TrPeriodSeconds,
                    sampleRate,
                    frequency,
                    buildWaveStats(generated, sampleRate),
                    msg.getMessageText()
            ));
        }
        return generated;
    }

    /**
     * 是否支持当前模式
     */
    public static boolean supportMode(int mode) {
        return mode == FT8Common.FT8_MODE
                || mode == FT8Common.FT4_MODE
                || mode == FT8Common.Q65_MODE;
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
    private static native float[] generateFtXNative(Ft8Message msg,
                                                    float frequency,
                                                    int sampleRate,
                                                    int mode,
                                                    int q65Submode,
                                                    int q65TrPeriodSeconds);
}

