package com.bg7yoz.ft8cn.wave;

/** 供 JNI 失败时使用的有界单声道抽取器。 */
public final class FtxResampler {
    private static final int TARGET_RATE = 12000;
    private static final int MAX_OUTPUT_SAMPLES = TARGET_RATE * 300;
    private static final float[] KERNEL_2X = buildLowPassKernel(65, 0.45 / 2.0);
    private static final float[] KERNEL_4X = buildLowPassKernel(129, 0.45 / 4.0);

    private FtxResampler() {
    }

    public static float[] resampleMono(float[] inputData, int inputRate, int outputRate) {
        if (inputData == null || inputData.length == 0 || outputRate != TARGET_RATE) {
            return null;
        }
        if (inputRate == outputRate) {
            return inputData;
        }
        final int factor;
        final float[] kernel;
        if (inputRate == 24000) {
            factor = 2;
            kernel = KERNEL_2X;
        } else if (inputRate == 48000) {
            factor = 4;
            kernel = KERNEL_4X;
        } else {
            return null;
        }

        int outputLength = inputData.length / factor;
        if (outputLength <= 0 || outputLength > MAX_OUTPUT_SAMPLES) {
            return null;
        }
        float[] output = new float[outputLength];
        int radius = kernel.length / 2;
        for (int outputIndex = 0; outputIndex < outputLength; outputIndex++) {
            int center = outputIndex * factor;
            double accumulator = 0.0;
            for (int tap = 0; tap < kernel.length; tap++) {
                int sampleIndex = center + tap - radius;
                if (sampleIndex < 0) {
                    sampleIndex = 0;
                } else if (sampleIndex >= inputData.length) {
                    sampleIndex = inputData.length - 1;
                }
                accumulator += inputData[sampleIndex] * kernel[tap];
            }
            output[outputIndex] = (float) accumulator;
        }
        return output;
    }

    private static float[] buildLowPassKernel(int taps, double cutoff) {
        float[] kernel = new float[taps];
        int center = taps / 2;
        double sum = 0.0;
        for (int index = 0; index < taps; index++) {
            int offset = index - center;
            double sinc = offset == 0
                    ? 2.0 * cutoff
                    : Math.sin(2.0 * Math.PI * cutoff * offset) / (Math.PI * offset);
            double window = 0.54
                    - 0.46 * Math.cos(2.0 * Math.PI * index / (taps - 1));
            kernel[index] = (float) (sinc * window);
            sum += kernel[index];
        }
        for (int index = 0; index < taps; index++) {
            kernel[index] /= (float) sum;
        }
        return kernel;
    }
}
