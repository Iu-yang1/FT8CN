package com.bg7yoz.ft8cn.experimental;

public final class ExperimentalCodecBridge {
    static {
        System.loadLibrary("ft8cn");
    }

    private ExperimentalCodecBridge() {
    }

    public static final int[] PROBE_TONES_HZ = new int[]{1380, 1460, 1540, 1620};
    public static final int PROBE_SYMBOL_SAMPLES = 256;

    public static native float[] analyzeFirstSymbolEnergies(
            float[] samples,
            int sampleRate,
            int symbolSamples,
            int[] toneHz
    );

    public static native String getNativeVersion();
}

