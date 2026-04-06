package com.bg7yoz.ft8cn.experimental;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验性 4FSK / CPFSK 链路的共享调制解调器参数。
 *
 * 这些数值与 Python设计原型保持一致，以便在调制解调器尚处于开发阶段时，
 * 使 Android 端与离线扫描保持同步。
 */
public final class ExperimentalCodecConfig {
    public static final float SYMBOL_RATE = 31.25f;
    public static final float TONE_SPACING_HZ = 80.0f;
    public static final float AMPLITUDE = 0.75f;
    public static final int SYNC_WORD = 0xD391;
    public static final int MAX_PAYLOAD_BYTES = 64;
    public static final int JOINT_DETECTION_BLOCK_SIZE = 2;
    public static final int SYNC_SEARCH_TOP_K = 24;
    public static final int DECODE_CANDIDATE_LIMIT = 8;
    public static final float CFO_FINE_SPAN_HZ = 1.0f;
    public static final float CFO_FINE_STEP_HZ = 0.25f;

    public static final int[] SYNC_BLOCK_0 = new int[]{0, 1, 3, 2};
    public static final int[] SYNC_BLOCK_1 = new int[]{2, 0, 3, 1};
    public static final int[] SYNC_BLOCK_2 = new int[]{1, 3, 0, 2};

    public static final float[] CFO_COARSE_SEARCH_HZ = new float[]{
            -10.0f, -8.0f, -6.0f, -4.0f, -2.0f,
            0.0f,
            2.0f, 4.0f, 6.0f, 8.0f, 10.0f
    };

    private ExperimentalCodecConfig() {
    }

    public static int getSamplesPerSymbol(int sampleRate) {
        return Math.max(1, Math.round(sampleRate / SYMBOL_RATE));
    }

    public static float[] buildToneSet(float baseFrequencyHz, int sampleRate) {
        float nyquistSafeMax = sampleRate * 0.45f;
        float low = Math.max(200.0f, Math.min(baseFrequencyHz, nyquistSafeMax - 3.0f * TONE_SPACING_HZ));
        return new float[]{
                low,
                low + TONE_SPACING_HZ,
                low + 2.0f * TONE_SPACING_HZ,
                low + 3.0f * TONE_SPACING_HZ
        };
    }

    public static int[] buildSyncWordSymbols() {
        return bitsToSymbols(bytesToBits(new byte[]{
                (byte) ((SYNC_WORD >> 8) & 0xFF),
                (byte) (SYNC_WORD & 0xFF)
        }));
    }

    public static SyncLayout buildSyncLayout() {
        int[] syncWordSymbols = buildSyncWordSymbols();
        int[] prefixSymbols = new int[SYNC_BLOCK_0.length + syncWordSymbols.length + SYNC_BLOCK_1.length];
        int cursor = 0;
        System.arraycopy(SYNC_BLOCK_0, 0, prefixSymbols, cursor, SYNC_BLOCK_0.length);
        cursor += SYNC_BLOCK_0.length;
        System.arraycopy(syncWordSymbols, 0, prefixSymbols, cursor, syncWordSymbols.length);
        cursor += syncWordSymbols.length;
        System.arraycopy(SYNC_BLOCK_1, 0, prefixSymbols, cursor, SYNC_BLOCK_1.length);
        return new SyncLayout(prefixSymbols, SYNC_BLOCK_2.clone(), SYNC_BLOCK_1.clone());
    }

    public static List<Float> buildFineFrequencyGrid(float centerHz) {
        ArrayList<Float> values = new ArrayList<>();
        int steps = Math.round(CFO_FINE_SPAN_HZ / CFO_FINE_STEP_HZ);
        for (int step = -steps; step <= steps; step++) {
            values.add(centerHz + step * CFO_FINE_STEP_HZ);
        }
        return values;
    }

    public static byte[] bitsToBytes(int[] bits) {
        if ((bits.length & 7) != 0) {
            throw new IllegalArgumentException("比特流长度必须是 8 的倍数");
        }

        byte[] output = new byte[bits.length / 8];
        for (int byteIndex = 0; byteIndex < output.length; byteIndex++) {
            int value = 0;
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                value = (value << 1) | (bits[byteIndex * 8 + bitIndex] & 1);
            }
            output[byteIndex] = (byte) (value & 0xFF);
        }
        return output;
    }

    public static int[] bytesToBits(byte[] data) {
        int[] bits = new int[data.length * 8];
        int cursor = 0;
        for (byte value : data) {
            int unsigned = value & 0xFF;
            for (int shift = 7; shift >= 0; shift--) {
                bits[cursor++] = (unsigned >> shift) & 1;
            }
        }
        return bits;
    }

    public static int[] bitsToSymbols(int[] bits) {
        if ((bits.length & 1) != 0) {
            throw new IllegalArgumentException("4FSK 需要偶数个比特");
        }

        int[] symbols = new int[bits.length / 2];
        for (int i = 0; i < symbols.length; i++) {
            symbols[i] = ((bits[i * 2] & 1) << 1) | (bits[i * 2 + 1] & 1);
        }
        return symbols;
    }

    public static int[] symbolsToBits(int[] symbols, int start, int count) {
        int[] bits = new int[count * 2];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            int symbol = symbols[start + i] & 0x03;
            bits[cursor++] = (symbol >> 1) & 1;
            bits[cursor++] = symbol & 1;
        }
        return bits;
    }

    public static final class SyncLayout {
        public final int[] prefixSymbols;
        public final int[] middleSymbols;
        public final int[] tailSymbols;

        private SyncLayout(int[] prefixSymbols, int[] middleSymbols, int[] tailSymbols) {
            this.prefixSymbols = prefixSymbols;
            this.middleSymbols = middleSymbols;
            this.tailSymbols = tailSymbols;
        }
    }
}
