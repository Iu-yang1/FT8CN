package com.bg7yoz.ft8cn.wave;

import android.util.Log;

/**
 * 用于重采样的库。
 * @author bg7yoz
 * @date 2023-09-09
 */
public class FT8Resample {
    private static final String TAG = "FT8Resample";

    static {
        System.loadLibrary("ft8cn");
    }

    public static native short[] get16Resample16(short[] inputData, int inputRate
            , int outputRate,int channels);

    public static native float[] get32Resample16(short[] inputData, int inputRate
            , int outputRate,int channels);
    public static native short[] get16Resample32(float[] inputData, int inputRate
            , int outputRate,int channels);
    public static native float[] get32Resample32(float[] inputData, int inputRate
            , int outputRate,int channels);

    public static native byte[] get8Resample16(short[] inputData, int inputRate
            , int outputRate,int channels);

    public static native byte[] get8Resample32(float[] inputData, int inputRate
            , int outputRate,int channels);

    /**
     * 解码链路优先走 native 重采样；如果当前构建未导出对应 JNI，
     * 则退回到本地 Java 实现，保证 24k/48k 输入仍可进入 12k 解码核心。
     */
    public static float[] resampleFloatToFloatSafe(float[] inputData,
                                                   int inputRate,
                                                   int outputRate,
                                                   int channels) {
        if (inputData == null || inputData.length == 0) {
            return inputData;
        }
        if (inputRate <= 0 || outputRate <= 0 || channels <= 0) {
            return inputData;
        }
        if (inputRate == outputRate) {
            return inputData;
        }

        try {
            float[] nativeOutput = get32Resample32(inputData, inputRate, outputRate, channels);
            if (nativeOutput != null && nativeOutput.length > 0) {
                return nativeOutput;
            }
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, String.format(
                    "native get32Resample32 unavailable, fallback to java: %s",
                    e.getMessage()
            ));
        } catch (Throwable t) {
            Log.w(TAG, String.format(
                    "native get32Resample32 failed, fallback to java: %s",
                    t.getMessage()
            ));
        }

        return resampleFloatToFloatJava(inputData, inputRate, outputRate, channels);
    }

    private static float[] resampleFloatToFloatJava(float[] inputData,
                                                    int inputRate,
                                                    int outputRate,
                                                    int channels) {
        if (channels != 1) {
            return resampleLinear(inputData, inputRate, outputRate, channels);
        }

        if (outputRate < inputRate && inputRate % outputRate == 0) {
            int decimation = inputRate / outputRate;
            if (decimation == 2 || decimation == 4) {
                return decimateMonoWithLowPass(inputData, decimation);
            }
        }

        return resampleLinear(inputData, inputRate, outputRate, channels);
    }

    /**
     * 对 24k/48k -> 12k 这样的整数抽取路径先做简单低通，再按整数倍抽取。
     * 这比直接抽点更稳，能减少混叠对 FT8/FT4 同步搜索的影响。
     */
    private static float[] decimateMonoWithLowPass(float[] inputData, int decimation) {
        final int taps = decimation == 2 ? 17 : 33;
        final float cutoff = 0.45f / decimation;
        float[] kernel = buildLowPassKernel(taps, cutoff);
        float[] filtered = convolveSame(inputData, kernel);
        int outputLength = Math.max(1, (filtered.length + decimation - 1) / decimation);
        float[] output = new float[outputLength];
        int outIndex = 0;
        for (int inIndex = 0; inIndex < filtered.length && outIndex < outputLength; inIndex += decimation) {
            output[outIndex++] = filtered[inIndex];
        }
        if (outIndex == outputLength) {
            return output;
        }

        float[] trimmed = new float[outIndex];
        System.arraycopy(output, 0, trimmed, 0, outIndex);
        return trimmed;
    }

    private static float[] resampleLinear(float[] inputData,
                                          int inputRate,
                                          int outputRate,
                                          int channels) {
        int frameCount = inputData.length / channels;
        if (frameCount <= 0) {
            return new float[0];
        }
        int outputFrames = Math.max(1, Math.round(frameCount * (outputRate / (float) inputRate)));
        float[] output = new float[outputFrames * channels];
        float step = inputRate / (float) outputRate;
        for (int frame = 0; frame < outputFrames; frame++) {
            float srcPosition = frame * step;
            int srcIndex = (int) Math.floor(srcPosition);
            float frac = srcPosition - srcIndex;
            int nextIndex = Math.min(srcIndex + 1, frameCount - 1);
            srcIndex = Math.min(srcIndex, frameCount - 1);
            for (int channel = 0; channel < channels; channel++) {
                float sampleA = inputData[srcIndex * channels + channel];
                float sampleB = inputData[nextIndex * channels + channel];
                output[frame * channels + channel] = sampleA + (sampleB - sampleA) * frac;
            }
        }
        return output;
    }

    private static float[] convolveSame(float[] inputData, float[] kernel) {
        float[] output = new float[inputData.length];
        int radius = kernel.length / 2;
        for (int i = 0; i < inputData.length; i++) {
            float acc = 0.0f;
            for (int k = 0; k < kernel.length; k++) {
                int sampleIndex = i + k - radius;
                if (sampleIndex < 0) {
                    sampleIndex = 0;
                } else if (sampleIndex >= inputData.length) {
                    sampleIndex = inputData.length - 1;
                }
                acc += inputData[sampleIndex] * kernel[k];
            }
            output[i] = acc;
        }
        return output;
    }

    private static float[] buildLowPassKernel(int taps, float cutoff) {
        float[] kernel = new float[taps];
        int center = taps / 2;
        double sum = 0.0;
        for (int i = 0; i < taps; i++) {
            int n = i - center;
            double sinc;
            if (n == 0) {
                sinc = 2.0 * cutoff;
            } else {
                double x = 2.0 * Math.PI * cutoff * n;
                sinc = Math.sin(x) / (Math.PI * n);
            }
            double window = 0.54 - 0.46 * Math.cos((2.0 * Math.PI * i) / (taps - 1));
            kernel[i] = (float) (sinc * window);
            sum += kernel[i];
        }
        if (sum == 0.0) {
            return kernel;
        }
        for (int i = 0; i < taps; i++) {
            kernel[i] /= (float) sum;
        }
        return kernel;
    }

}

