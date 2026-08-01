package com.bg7yoz.ft8cn.log;

import com.bg7yoz.ft8cn.data.logbook.AdifCodec;
import com.bg7yoz.ft8cn.data.logbook.ParsedAdif;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** 旧导入入口共用的有界 UTF-8 读取与 ADIF 解析适配器。 */
public final class BoundedAdifReader {
    private static final int BUFFER_CHARACTERS = 8 * 1024;
    public static final int MAX_INPUT_BYTES = AdifCodec.MAX_INPUT_CHARACTERS;

    private BoundedAdifReader() {
    }

    public static String readUtf8(InputStream input) throws IOException {
        return readUtf8(input, MAX_INPUT_BYTES);
    }

    static String readUtf8(InputStream input, int maximumBytes) throws IOException {
        if (input == null) {
            throw new IOException("ADIF 输入流为空");
        }
        if (maximumBytes <= 0 || maximumBytes > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("ADIF 字节上限无效");
        }
        InputStream limited = new SizeLimitedInputStream(input, maximumBytes);
        InputStreamReader reader = new InputStreamReader(
                limited,
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT));
        StringBuilder text = new StringBuilder(Math.min(maximumBytes, 64 * 1024));
        char[] buffer = new char[BUFFER_CHARACTERS];
        while (true) {
            int count = reader.read(buffer);
            if (count < 0) {
                break;
            }
            if (text.length() + count > AdifCodec.MAX_INPUT_CHARACTERS) {
                throw new IOException("ADIF 文本超过 32 MiB 字符上限");
            }
            text.append(buffer, 0, count);
        }
        return text.toString();
    }

    public static ArrayList<HashMap<String, String>> parseRecords(String text) {
        ParsedAdif parsed = AdifCodec.INSTANCE.parse(text);
        ArrayList<HashMap<String, String>> records = new ArrayList<>(parsed.getRecords().size());
        for (Map<String, String> record : parsed.getRecords()) {
            records.add(new HashMap<>(record));
        }
        return records;
    }

    private static final class SizeLimitedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long consumed;

        private SizeLimitedInputStream(InputStream delegate, long maximumBytes) {
            super(delegate);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            if (consumed >= maximumBytes) {
                return requireEndOfStream();
            }
            int value = super.read();
            if (value >= 0) {
                consumed++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (consumed >= maximumBytes) {
                return requireEndOfStream();
            }
            int permitted = (int) Math.min(length, maximumBytes - consumed);
            int count = super.read(buffer, offset, permitted);
            if (count > 0) {
                consumed += count;
            }
            return count;
        }

        private int requireEndOfStream() throws IOException {
            if (super.read() < 0) {
                return -1;
            }
            throw new IOException("ADIF 文件超过 32 MiB 上限");
        }
    }
}
