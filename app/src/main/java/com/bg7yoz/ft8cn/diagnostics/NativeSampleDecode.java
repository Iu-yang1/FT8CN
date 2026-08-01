package com.bg7yoz.ft8cn.diagnostics;

/**
 * Debug-only sample decode entry.
 * It passes one WAV path to native code and returns a readable smoke-test report.
 */
public final class NativeSampleDecode {
    static {
        System.loadLibrary("ft8cn");
    }

    private NativeSampleDecode() {
    }

    public static native void configureRuntimeDirectories(String tempDir,
                                                          String dataDir);

    public static native int getFt8SyncThreadCount();

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
