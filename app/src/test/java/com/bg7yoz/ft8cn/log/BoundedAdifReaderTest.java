package com.bg7yoz.ft8cn.log;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class BoundedAdifReaderTest {
    @Test
    public void readsToEndEvenWhenAvailableReturnsZero() throws Exception {
        byte[] data = "<CALL:4>W1AW<EOR>".getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(data) {
            @Override
            public int available() {
                return 0;
            }
        };
        assertEquals("<CALL:4>W1AW<EOR>", BoundedAdifReader.readUtf8(input, 128));
    }

    @Test(expected = IOException.class)
    public void rejectsInputBeyondConfiguredLimit() throws Exception {
        BoundedAdifReader.readUtf8(
                new ByteArrayInputStream("123456789".getBytes(StandardCharsets.UTF_8)),
                8);
    }

    @Test(expected = IOException.class)
    public void rejectsMalformedUtf8() throws Exception {
        BoundedAdifReader.readUtf8(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}), 8);
    }
}
