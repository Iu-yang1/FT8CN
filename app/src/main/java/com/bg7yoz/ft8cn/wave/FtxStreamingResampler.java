package com.bg7yoz.ft8cn.wave;

/**
 * 有状态单声道抽取器。输入按小块送入，输出直接写入最终 12 kHz 时隙缓冲区。
 */
public final class FtxStreamingResampler implements AutoCloseable {
    private long nativeHandle;
    private boolean finished;

    public FtxStreamingResampler(int inputRate, int outputRate) {
        nativeHandle = FT8Resample.createFloatStream(inputRate, outputRate);
        if (nativeHandle == 0L) {
            throw new IllegalArgumentException(
                    "unsupported streaming resample rate: " + inputRate + " -> " + outputRate);
        }
    }

    public synchronized int process(float[] input,
                                    int inputOffset,
                                    int inputCount,
                                    float[] output,
                                    int outputOffset,
                                    int outputCapacity) {
        ensureOpen();
        if (finished) {
            throw new IllegalStateException("resampler is already finished");
        }
        int written = FT8Resample.processFloatStream(
                nativeHandle,
                input,
                inputOffset,
                inputCount,
                output,
                outputOffset,
                outputCapacity);
        if (written < 0) {
            throw new IllegalStateException("streaming resampler process failed: " + written);
        }
        return written;
    }

    public synchronized int finish(float[] output, int outputOffset, int outputCapacity) {
        ensureOpen();
        if (finished) {
            return 0;
        }
        int written = FT8Resample.finishFloatStream(
                nativeHandle,
                output,
                outputOffset,
                outputCapacity);
        if (written < 0) {
            throw new IllegalStateException("streaming resampler finish failed: " + written);
        }
        finished = true;
        return written;
    }

    @Override
    public synchronized void close() {
        if (nativeHandle != 0L) {
            FT8Resample.destroyFloatStream(nativeHandle);
            nativeHandle = 0L;
        }
        finished = true;
    }

    private void ensureOpen() {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("resampler is closed");
        }
    }
}
