package com.bg7yoz.ft8cn.wave;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class NativeFloatBufferInstrumentedTest {
    private static final int OUTPUT_RATE = 12_000;

    @Test
    public void appendTransfersExactSamplesAndCloseIsIdempotent() {
        NativeFloatBuffer buffer = new NativeFloatBuffer(8);
        buffer.append(new float[]{9f, 1f, 2f, 3f, 8f}, 1, 3);

        assertEquals(3, buffer.size());
        assertEquals(8, buffer.capacity());
        assertArrayEquals(new float[]{1f, 2f, 3f}, buffer.copyToArrayForTest(), 0f);

        buffer.close();
        buffer.close();
        assertThrows(IllegalStateException.class, buffer::size);
    }

    @Test
    public void nativeDestinationMatchesArrayDestinationAcrossChunkBoundaries() {
        assertStreamingOutputMatches(24_000, 24_017, 0x2400L);
        assertStreamingOutputMatches(48_000, 48_031, 0x4800L);
    }

    @Test
    public void capacityChecksRejectOverflowAndUnsupportedSizes() {
        assertThrows(IllegalArgumentException.class, () -> new NativeFloatBuffer(0));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeFloatBuffer(OUTPUT_RATE * 300 + 1));
    }

    @Test
    public void recorderTransfersNativeSlotOwnershipAndCancelsPartialSlot() {
        HamRecorder recorder = new HamRecorder(count -> { });
        recorder.setDataFromLan();
        recorder.startRecord();
        recorder.doOnWaveDataReceived(1, new float[]{0f}, 24_000);

        AtomicReference<NativeFloatBuffer> completed = new AtomicReference<>();
        HamRecorder.VoiceDataSubscription subscription =
                recorder.getNativeVoiceDataAtSampleRate(
                        10,
                        OUTPUT_RATE,
                        true,
                        completed::set);
        assertNotNull(subscription);
        float[] source = new float[240];
        for (int index = 0; index < source.length; index++) {
            source[index] = (float) Math.sin(index * 0.05);
        }
        recorder.doOnWaveDataReceived(source.length, source, 24_000);

        NativeFloatBuffer completedBuffer = completed.get();
        assertEquals(120, completedBuffer.size());
        assertEquals(0, recorder.getVoiceMonitorCount());
        completedBuffer.close();

        recorder.doOnWaveDataReceived(1, new float[]{0f}, 48_000);
        AtomicReference<NativeFloatBuffer> cancelledResult = new AtomicReference<>();
        subscription = recorder.getNativeVoiceDataAtSampleRate(
                100,
                OUTPUT_RATE,
                true,
                cancelledResult::set);
        assertNotNull(subscription);
        recorder.doOnWaveDataReceived(1_000, new float[1_000], 48_000);
        recorder.cancelPendingOneShotVoiceCaptures();
        recorder.doOnWaveDataReceived(4_800, new float[4_800], 48_000);

        assertEquals(null, cancelledResult.get());
        assertEquals(0, recorder.getVoiceMonitorCount());
        recorder.deleteVoiceDataMonitor(subscription);
        recorder.release();
    }

    private static void assertStreamingOutputMatches(int inputRate,
                                                     int inputSamples,
                                                     long seed) {
        float[] input = new float[inputSamples];
        Random random = new Random(seed);
        for (int index = 0; index < input.length; index++) {
            input[index] = (float) (0.6 * Math.sin(index * 0.031)
                    + 0.1 * (random.nextDouble() - 0.5));
        }
        int outputCapacity = (int) Math.ceil(inputSamples * OUTPUT_RATE / (double) inputRate) + 64;
        float[] arrayOutput = new float[outputCapacity];
        NativeFloatBuffer nativeOutput = new NativeFloatBuffer(outputCapacity);
        int arrayCount = 0;
        int nativeCount = 0;
        int inputOffset = 0;

        try (FtxStreamingResampler arrayResampler =
                     new FtxStreamingResampler(inputRate, OUTPUT_RATE);
             FtxStreamingResampler nativeResampler =
                     new FtxStreamingResampler(inputRate, OUTPUT_RATE)) {
            while (inputOffset < input.length) {
                int chunk = Math.min(input.length - inputOffset,
                        1 + random.nextInt(1_997));
                int arrayWritten = arrayResampler.process(
                        input,
                        inputOffset,
                        chunk,
                        arrayOutput,
                        arrayCount,
                        arrayOutput.length - arrayCount);
                int nativeWritten = nativeResampler.process(
                        input,
                        inputOffset,
                        chunk,
                        nativeOutput,
                        nativeCount,
                        nativeOutput.capacity() - nativeCount);
                assertEquals(arrayWritten, nativeWritten);
                arrayCount += arrayWritten;
                nativeCount += nativeWritten;
                inputOffset += chunk;
            }
            arrayCount += arrayResampler.finish(
                    arrayOutput,
                    arrayCount,
                    arrayOutput.length - arrayCount);
            nativeCount += nativeResampler.finish(
                    nativeOutput,
                    nativeCount,
                    nativeOutput.capacity() - nativeCount);
        }

        assertEquals(arrayCount, nativeCount);
        float[] expected = new float[arrayCount];
        System.arraycopy(arrayOutput, 0, expected, 0, arrayCount);
        assertArrayEquals(expected, nativeOutput.copyToArrayForTest(), 1.0e-6f);
        nativeOutput.close();
    }
}
