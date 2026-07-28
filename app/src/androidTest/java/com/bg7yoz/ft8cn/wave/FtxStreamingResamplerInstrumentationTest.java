package com.bg7yoz.ft8cn.wave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bg7yoz.ft8cn.diagnostics.InternalForegroundTestSession;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class FtxStreamingResamplerInstrumentationTest {
    private static final String TAG = "Q65StreamMemoryTest";
    private static final int TARGET_RATE = 12000;

    @BeforeClass
    public static void keepInstrumentationInForeground() {
        InternalForegroundTestSession.start();
    }

    @Test
    public void chunkedOutputMatchesOneShotBitForBit() {
        for (int sourceRate : new int[]{12000, 24000, 48000}) {
            int sampleCount = sourceRate * 2 + 37;
            float[] input = new float[sampleCount];
            for (int index = 0; index < input.length; index++) {
                input[index] = (float) (0.31 * Math.sin(2.0 * Math.PI * 997.0 * index / sourceRate)
                        + 0.07 * Math.cos(2.0 * Math.PI * 4211.0 * index / sourceRate));
            }
            float[] expected = FT8Resample.get32Resample32(input, sourceRate, TARGET_RATE, 1);
            float[] actual = new float[expected.length];
            int inputOffset = 0;
            int outputOffset = 0;
            int[] chunks = {1, 7, 64, 3, 511, 29, 4096};
            int chunkIndex = 0;
            try (FtxStreamingResampler stream =
                         new FtxStreamingResampler(sourceRate, TARGET_RATE)) {
                while (inputOffset < input.length) {
                    int count = Math.min(chunks[chunkIndex % chunks.length],
                            input.length - inputOffset);
                    outputOffset += stream.process(
                            input,
                            inputOffset,
                            count,
                            actual,
                            outputOffset,
                            actual.length - outputOffset);
                    inputOffset += count;
                    chunkIndex++;
                }
                outputOffset += stream.finish(
                        actual,
                        outputOffset,
                        actual.length - outputOffset);
            }
            assertEquals("output length at " + sourceRate, expected.length, outputOffset);
            for (int index = 0; index < expected.length; index++) {
                assertEquals("sample " + index + " at " + sourceRate,
                        Float.floatToIntBits(expected[index]),
                        Float.floatToIntBits(actual[index]));
            }
        }
    }

    @Test
    public void q65ThreeHundredSecondCaptureNeverAllocatesFull48kSource() {
        final int durationMs = 300_000;
        final int sourceRate = 48_000;
        final int expectedOutput = durationMs / 1000 * TARGET_RATE;
        HamRecorder recorder = new HamRecorder(count -> { });
        AtomicReference<float[]> completed = new AtomicReference<>();
        AtomicInteger callbackCount = new AtomicInteger();
        HamRecorder.VoiceDataMonitor monitor = new HamRecorder.VoiceDataMonitor(
                durationMs,
                sourceRate,
                TARGET_RATE,
                recorder,
                true,
                data -> {
                    completed.set(data);
                    callbackCount.incrementAndGet();
                });
        monitor.voiceDataMonitor = monitor;

        float[] sourceChunk = new float[4096];
        int remaining = durationMs / 1000 * sourceRate;
        long startedAt = System.currentTimeMillis();
        while (remaining > 0) {
            int count = Math.min(sourceChunk.length, remaining);
            monitor.onHamRecord.OnReceiveData(sourceChunk, count);
            remaining -= count;
        }

        assertEquals(1, callbackCount.get());
        assertNotNull(completed.get());
        assertEquals(expectedOutput, completed.get().length);
        Log.i(TAG, "Q65 RX 300s sourceRate=48000 sourceChunk=4096 outputSamples="
                + completed.get().length
                + " sourceArraySamples=4096 elapsedMs="
                + (System.currentTimeMillis() - startedAt));
    }
}
