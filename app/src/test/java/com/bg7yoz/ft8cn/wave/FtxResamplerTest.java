package com.bg7yoz.ft8cn.wave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FtxResamplerTest {
    @Test
    public void outputLengthAndDcAreStable() {
        for (int sourceRate : new int[]{24000, 48000}) {
            float[] input = new float[sourceRate];
            java.util.Arrays.fill(input, 0.25f);
            float[] output = FtxResampler.resampleMono(input, sourceRate, 12000);
            assertEquals(12000, output.length);
            for (float sample : output) {
                assertEquals(0.25f, sample, 1.0e-5f);
            }
        }
    }

    @Test
    public void passbandToneIsPreservedAndAliasIsRejected() {
        for (int sourceRate : new int[]{24000, 48000}) {
            float[] passband = tone(sourceRate, 1000.0);
            float[] passbandOutput = FtxResampler.resampleMono(passband, sourceRate, 12000);
            assertTrue(toneAmplitude(passbandOutput, 12000, 1000.0) > 0.95);

            double stopFrequency = sourceRate == 24000 ? 9000.0 : 15000.0;
            float[] stopband = tone(sourceRate, stopFrequency);
            float[] stopbandOutput = FtxResampler.resampleMono(stopband, sourceRate, 12000);
            assertTrue(rms(stopbandOutput) < 0.05);
        }
    }

    @Test
    public void unsupportedRatesFailInsteadOfPassingRawInput() {
        float[] input = new float[12000];
        assertSame(input, FtxResampler.resampleMono(input, 12000, 12000));
        assertNull(FtxResampler.resampleMono(input, 44100, 12000));
        assertNull(FtxResampler.resampleMono(input, 12000, 6000));
    }

    private static float[] tone(int sampleRate, double frequencyHz) {
        float[] samples = new float[sampleRate];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = (float) Math.sin(2.0 * Math.PI * frequencyHz * index / sampleRate);
        }
        return samples;
    }

    private static double toneAmplitude(float[] samples, int sampleRate, double frequencyHz) {
        double real = 0.0;
        double imaginary = 0.0;
        int start = Math.min(256, samples.length / 8);
        int end = samples.length - start;
        for (int index = start; index < end; index++) {
            double phase = 2.0 * Math.PI * frequencyHz * index / sampleRate;
            real += samples[index] * Math.cos(phase);
            imaginary += samples[index] * Math.sin(phase);
        }
        return 2.0 * Math.hypot(real, imaginary) / Math.max(1, end - start);
    }

    private static double rms(float[] samples) {
        double power = 0.0;
        int start = Math.min(256, samples.length / 8);
        int end = samples.length - start;
        for (int index = start; index < end; index++) {
            power += samples[index] * samples[index];
        }
        return Math.sqrt(power / Math.max(1, end - start));
    }
}
