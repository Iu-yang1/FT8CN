package com.bg7yoz.ft8cn.database;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class OperationBandTest {
    @Test
    public void readsFragmentedStreamEvenWhenAvailableIsZero() {
        byte[] source = "*:7074000:40m\n*:14074000:20m\n*:50313000:6m\n"
                .getBytes(StandardCharsets.UTF_8);
        InputStream fragmented = new ByteArrayInputStream(source) {
            @Override
            public int available() {
                return 0;
            }

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 3));
            }
        };

        assertArrayEquals(
                new String[] {"*:7074000:40m", "*:14074000:20m", "*:50313000:6m"},
                OperationBand.getLinesFromInputStream(fragmented, "\n"));
    }

    @Test
    public void invalidInputReturnsEmptyList() throws IOException {
        assertArrayEquals(
                new String[0],
                OperationBand.getLinesFromInputStream(null, "\n"));
    }
}
