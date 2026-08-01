package com.bg7yoz.ft8cn.wave;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class MicRecorderTest {
    @Test
    public void pcm16FallbackUsesFullScaleAndKeepsBufferBounded() {
        short[] source = {Short.MIN_VALUE, -16384, 0, 16384, Short.MAX_VALUE};
        float[] destination = new float[source.length];

        MicRecorder.pcm16ToFloat(source, destination, source.length);

        assertArrayEquals(
                new float[] {-1.0f, -0.5f, 0.0f, 0.5f, 32767.0f / 32768.0f},
                destination,
                0.0f);
    }

    @Test
    public void pcm16FallbackRejectsOversizedConversion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MicRecorder.pcm16ToFloat(new short[2], new float[1], 2));
    }
}
