package com.bg7yoz.ft8cn.spectrum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bg7yoz.ft8cn.wave.HamRecorder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SpectrumListenerTest {
    @Test
    public void reusableDisplayBufferMatchesAllocatingApi() {
        int[] source = new int[481];
        for (int index = 0; index < source.length; index++) {
            source[index] = (index * 37) & 0xff;
        }
        int[] expected = SpectrumListener.normalizeDisplayBins(source, source.length);
        int[] reusable = new int[SpectrumListener.DISPLAY_BIN_COUNT];

        assertEquals(
                SpectrumListener.DISPLAY_BIN_COUNT,
                SpectrumListener.normalizeDisplayBins(source, source.length, reusable));
        assertArrayEquals(expected, reusable);
    }

    @Test
    public void peakKeepsItsNormalizedFrequencyPosition() {
        int[] source = new int[481];
        source[160] = 255;
        int[] display = new int[SpectrumListener.DISPLAY_BIN_COUNT];
        SpectrumListener.normalizeDisplayBins(source, source.length, display);

        int peakIndex = 0;
        for (int index = 1; index < display.length; index++) {
            if (display[index] > display[peakIndex]) {
                peakIndex = index;
            }
        }
        double displayedHz = peakIndex * 3000.0 / display.length;
        assertTrue("1000 Hz peak moved to " + displayedHz, Math.abs(displayedHz - 1000.0) < 8.0);
    }

    @Test
    public void subscriptionRecoversAfterSampleRateAndRecorderRestart() {
        HamRecorder recorder = new HamRecorder(null);
        recorder.setDataFromLan();
        recorder.startRecord();
        SpectrumListener listener = new SpectrumListener(recorder);

        assertEquals(1, recorder.getVoiceMonitorCount());
        float[] samples12k = new float[1920];
        java.util.Arrays.fill(samples12k, 0.25f);
        recorder.doOnWaveDataReceived(samples12k.length, samples12k, 12000);
        assertEquals(1920, listener.getDataBuffer().length);
        assertEquals(0.25f, listener.getDataBuffer()[1000], 0.0f);

        float[] samples24k = new float[3840];
        java.util.Arrays.fill(samples24k, 0.5f);
        recorder.doOnWaveDataReceived(samples24k.length, samples24k, 24000);
        assertEquals(1, recorder.getVoiceMonitorCount());
        assertEquals(3840, listener.getDataBuffer().length);
        assertEquals(0.5f, listener.getDataBuffer()[2000], 0.0f);

        float[] samples48k = new float[7680];
        java.util.Arrays.fill(samples48k, 0.75f);
        recorder.doOnWaveDataReceived(samples48k.length, samples48k, 48000);
        assertEquals(1, recorder.getVoiceMonitorCount());
        assertEquals(7680, listener.getDataBuffer().length);
        assertEquals(0.75f, listener.getDataBuffer()[4000], 0.0f);

        recorder.stopRecord();
        assertEquals(0, recorder.getVoiceMonitorCount());
        recorder.startRecord();
        assertEquals(1, recorder.getVoiceMonitorCount());

        listener.release();
        assertEquals(0, recorder.getVoiceMonitorCount());
        recorder.stopRecord();
    }

    @Test
    public void subscriptionStartsWhenRecorderBecomesAvailableLater() {
        HamRecorder recorder = new HamRecorder(null);
        recorder.setDataFromLan();
        SpectrumListener listener = new SpectrumListener(recorder);
        assertEquals(0, recorder.getVoiceMonitorCount());

        recorder.startRecord();
        assertEquals(1, recorder.getVoiceMonitorCount());
        float[] samples = new float[1920];
        java.util.Arrays.fill(samples, 0.125f);
        recorder.doOnWaveDataReceived(samples.length, samples, 12000);
        assertEquals(0.125f, listener.getDataBuffer()[1000], 0.0f);

        listener.release();
        recorder.stopRecord();
    }
}
