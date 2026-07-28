package com.bg7yoz.ft8cn.spectrum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
