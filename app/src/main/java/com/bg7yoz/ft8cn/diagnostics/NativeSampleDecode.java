package com.bg7yoz.ft8cn.diagnostics;

/**
 * 调试用样本解码入口。
 * 仅负责把 WAV 路径交给 native 侧执行一次完整解码，并返回可读文本。
 */
public final class NativeSampleDecode {
    static {
        System.loadLibrary("ft8cn");
    }

    private NativeSampleDecode() {
    }

    public static native void configureRuntimeDirectories(String tempDir,
                                                          String dataDir);

    public static native String inspectWavFile(String wavPath,
                                               int decodeMode,
                                               long utcTime);

    public static native String decodeWavFile(String wavPath,
                                              int decodeMode,
                                              long utcTime,
                                              String myCall,
                                              int decodePassCount,
                                              int multiDecodeRoundCount,
                                              int qsoFreqSensitivity,
                                              int decodeSensitivity,
                                              boolean enableEarlyDecode,
                                              boolean enableWidebandDxSearch,
                                              boolean deepDecodeEnabled,
                                              int q65Submode,
                                              int q65TrPeriodSeconds);
}
