package com.bg7yoz.ft8cn.ft8transmit;

import com.bg7yoz.ft8cn.FT8Common;

/**
 * Q65 A-E 波形的有界分块生成器；官方 core 只生成一次 tone 序列。
 */
public final class Q65WaveStream implements AutoCloseable {
    static {
        System.loadLibrary("ft8cn");
    }

    private long nativeHandle;
    private final long totalSamples;
    private long samplesRead;

    public Q65WaveStream(String message,
                         float frequencyHz,
                         int sampleRate,
                         int submode,
                         int trPeriodSeconds) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Q65 message is empty");
        }
        if (submode < FT8Common.Q65_SUBMODE_A || submode > FT8Common.Q65_SUBMODE_E) {
            throw new IllegalArgumentException("Q65F is diagnostic-only");
        }
        FT8Common.requireQ65TrPeriodSeconds(trPeriodSeconds);
        totalSamples = requiredSamplesNative(trPeriodSeconds, sampleRate);
        if (totalSamples <= 0L || totalSamples > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("unsupported Q65 period/sample rate");
        }
        nativeHandle = createNative(
                message,
                frequencyHz,
                sampleRate,
                submode,
                trPeriodSeconds);
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Q65 stream initialization failed");
        }
    }

    public static long requiredSamples(int trPeriodSeconds, int sampleRate) {
        FT8Common.requireQ65TrPeriodSeconds(trPeriodSeconds);
        return requiredSamplesNative(trPeriodSeconds, sampleRate);
    }

    public synchronized int read(float[] output, int offset, int count) {
        ensureOpen();
        if (output == null || offset < 0 || count < 0
                || (long) offset + count > output.length) {
            throw new IllegalArgumentException("invalid Q65 output range");
        }
        int produced = readNative(nativeHandle, output, offset, count);
        if (produced < 0) {
            throw new IllegalStateException("Q65 stream generation failed");
        }
        samplesRead += produced;
        return produced;
    }

    public long getTotalSamples() {
        return totalSamples;
    }

    public long getSamplesRead() {
        return samplesRead;
    }

    @Override
    public synchronized void close() {
        if (nativeHandle != 0L) {
            destroyNative(nativeHandle);
            nativeHandle = 0L;
        }
    }

    private void ensureOpen() {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Q65 stream is closed");
        }
    }

    private static native long requiredSamplesNative(int trPeriodSeconds, int sampleRate);

    private static native long createNative(String message,
                                            float frequencyHz,
                                            int sampleRate,
                                            int submode,
                                            int trPeriodSeconds);

    private static native int readNative(long handle,
                                         float[] output,
                                         int outputOffset,
                                         int requestedSamples);

    private static native void destroyNative(long handle);
}
