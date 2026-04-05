package com.bg7yoz.ft8cn.experimental;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Experimental 4FSK / CPFSK modem path migrated from the Python prototype.
 * This is intentionally independent from FT8/FT4 framing and used only when
 * the experimental mode is enabled in settings.
 */
public final class ExperimentalCodecEngine {
    private static final float SYMBOL_RATE = 31.25f;
    private static final float TONE_SPACING_HZ = 80.0f;
    private static final float AMPLITUDE = 0.75f;
    private static final int PREAMBLE_SYMBOL_COUNT = 32;
    private static final int SYNC_WORD = 0xD391;
    private static final int MAX_PAYLOAD_BYTES = 64;
    private static final int MIN_PREAMBLE_SCORE = 24;

    private static final int[] PREAMBLE_SYMBOLS = new int[PREAMBLE_SYMBOL_COUNT];

    static {
        for (int i = 0; i < PREAMBLE_SYMBOL_COUNT; i++) {
            PREAMBLE_SYMBOLS[i] = i % 4;
        }
    }

    private ExperimentalCodecEngine() {
    }

    public static final class DecodeResult {
        public final boolean frameFound;
        public final boolean crcOk;
        public final String payloadText;
        public final int payloadLength;
        public final int preambleScore;
        public final int symbolOffset;
        public final int codecMode;

        private DecodeResult(
                boolean frameFound,
                boolean crcOk,
                String payloadText,
                int payloadLength,
                int preambleScore,
                int symbolOffset,
                int codecMode
        ) {
            this.frameFound = frameFound;
            this.crcOk = crcOk;
            this.payloadText = payloadText;
            this.payloadLength = payloadLength;
            this.preambleScore = preambleScore;
            this.symbolOffset = symbolOffset;
            this.codecMode = codecMode;
        }

        public static DecodeResult empty(int codecMode) {
            return new DecodeResult(false, false, "", 0, 0, -1, codecMode);
        }
    }

    public static float[] generateTxWave(
            Ft8Message message,
            float baseFrequencyHz,
            int sampleRate,
            int slotMode,
            int codecMode
    ) {
        if (codecMode == GeneralVariables.EXP_CODEC_MODE_OFF || sampleRate <= 0) {
            return null;
        }

        int samplesPerSymbol = getSamplesPerSymbol(sampleRate);
        int slotSamples = Math.max(1, Math.round(sampleRate * FT8Common.getSlotTimeSecond(slotMode)));
        int maxPayloadBytesForSlot = getMaxPayloadBytesForSlot(slotSamples, samplesPerSymbol);
        int payloadLimit = Math.max(1, Math.min(MAX_PAYLOAD_BYTES, maxPayloadBytesForSlot));

        byte[] payload = buildPayloadBytes(message, payloadLimit);
        byte[] frame = buildFrameBytes(payload);
        int[] symbols = buildPacketSymbols(frame);

        float[] tones = buildToneSet(baseFrequencyHz, sampleRate);
        float[] packetWave = modulateSymbols(
                symbols,
                sampleRate,
                samplesPerSymbol,
                tones,
                codecMode == GeneralVariables.EXP_CODEC_MODE_CPFSK
        );

        if (packetWave.length >= slotSamples) {
            return Arrays.copyOf(packetWave, packetWave.length);
        }

        float[] padded = new float[slotSamples];
        System.arraycopy(packetWave, 0, padded, 0, packetWave.length);
        return padded;
    }

    public static DecodeResult decodeWave(
            float[] samples,
            float baseFrequencyHz,
            int sampleRate,
            int codecMode
    ) {
        if (samples == null || samples.length == 0 || sampleRate <= 0) {
            return DecodeResult.empty(codecMode);
        }
        if (codecMode == GeneralVariables.EXP_CODEC_MODE_OFF) {
            return DecodeResult.empty(codecMode);
        }

        int samplesPerSymbol = getSamplesPerSymbol(sampleRate);
        if (samples.length < samplesPerSymbol * (PREAMBLE_SYMBOL_COUNT + 20)) {
            return DecodeResult.empty(codecMode);
        }

        float[] tones = buildToneSet(baseFrequencyHz, sampleRate);
        int offsetStep = Math.max(1, samplesPerSymbol / 8);

        DecodeResult best = DecodeResult.empty(codecMode);
        int bestQuality = Integer.MIN_VALUE;

        for (int offset = 0; offset < samplesPerSymbol; offset += offsetStep) {
            int[] symbols = demodulateSymbols(samples, offset, samplesPerSymbol, sampleRate, tones);
            if (symbols.length < PREAMBLE_SYMBOL_COUNT + 20) {
                continue;
            }

            for (int start = 0; start <= symbols.length - (PREAMBLE_SYMBOL_COUNT + 20); start++) {
                int preambleScore = scorePreamble(symbols, start);
                if (preambleScore < MIN_PREAMBLE_SCORE) {
                    continue;
                }

                int payloadStart = start + PREAMBLE_SYMBOL_COUNT;
                DecodeResult candidate = tryParseFrame(symbols, payloadStart, preambleScore, offset, codecMode);
                if (!candidate.frameFound) {
                    continue;
                }

                int quality = candidate.preambleScore + (candidate.crcOk ? 1000 : 0);
                if (quality > bestQuality) {
                    bestQuality = quality;
                    best = candidate;
                }
            }
        }

        return best;
    }

    private static byte[] buildPayloadBytes(Ft8Message message, int payloadLimit) {
        String text = "";
        if (message != null) {
            String messageText = message.getMessageText();
            if (messageText != null) {
                text = messageText.trim();
            }
        }
        if (text.length() == 0 && message != null) {
            text = message.toString();
        }
        if (text.length() == 0) {
            text = "EXP";
        }
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        if (all.length <= payloadLimit) {
            return all;
        }
        return Arrays.copyOf(all, payloadLimit);
    }

    private static int getMaxPayloadBytesForSlot(int slotSamples, int samplesPerSymbol) {
        int totalSymbols = slotSamples / samplesPerSymbol;
        int overheadSymbols = PREAMBLE_SYMBOL_COUNT + 20;
        int payloadSymbols = Math.max(0, totalSymbols - overheadSymbols);
        return payloadSymbols / 4;
    }

    private static int[] buildPacketSymbols(byte[] frameBytes) {
        int[] frameBits = bytesToBits(frameBytes);
        int[] payloadSymbols = bitsToSymbols(frameBits);
        int[] packet = new int[PREAMBLE_SYMBOL_COUNT + payloadSymbols.length];
        System.arraycopy(PREAMBLE_SYMBOLS, 0, packet, 0, PREAMBLE_SYMBOL_COUNT);
        System.arraycopy(payloadSymbols, 0, packet, PREAMBLE_SYMBOL_COUNT, payloadSymbols.length);
        return packet;
    }

    private static float[] modulateSymbols(
            int[] symbols,
            int sampleRate,
            int samplesPerSymbol,
            float[] toneHz,
            boolean continuousPhase
    ) {
        float[] output = new float[symbols.length * samplesPerSymbol];
        double phase = 0.0;

        for (int symbolIndex = 0; symbolIndex < symbols.length; symbolIndex++) {
            int symbol = symbols[symbolIndex] & 0x03;
            double freq = toneHz[symbol];
            double phaseStep = 2.0 * Math.PI * freq / sampleRate;
            int start = symbolIndex * samplesPerSymbol;

            if (continuousPhase) {
                for (int n = 0; n < samplesPerSymbol; n++) {
                    phase += phaseStep;
                    output[start + n] = (float) (AMPLITUDE * Math.sin(phase));
                }
            } else {
                for (int n = 0; n < samplesPerSymbol; n++) {
                    output[start + n] = (float) (AMPLITUDE * Math.sin(phaseStep * n));
                }
            }
        }
        return output;
    }

    private static int[] demodulateSymbols(
            float[] samples,
            int offset,
            int samplesPerSymbol,
            int sampleRate,
            float[] toneHz
    ) {
        int count = (samples.length - offset) / samplesPerSymbol;
        if (count <= 0) {
            return new int[0];
        }
        int[] symbols = new int[count];

        for (int symbolIndex = 0; symbolIndex < count; symbolIndex++) {
            int start = offset + symbolIndex * samplesPerSymbol;
            double bestEnergy = -1.0;
            int bestSymbol = 0;

            for (int toneIndex = 0; toneIndex < 4; toneIndex++) {
                double re = 0.0;
                double im = 0.0;
                for (int n = 0; n < samplesPerSymbol; n++) {
                    double x = samples[start + n];
                    double phase = 2.0 * Math.PI * toneHz[toneIndex] * n / sampleRate;
                    re += x * Math.cos(phase);
                    im -= x * Math.sin(phase);
                }
                double energy = re * re + im * im;
                if (energy > bestEnergy) {
                    bestEnergy = energy;
                    bestSymbol = toneIndex;
                }
            }
            symbols[symbolIndex] = bestSymbol;
        }
        return symbols;
    }

    private static int scorePreamble(int[] symbols, int start) {
        int score = 0;
        for (int i = 0; i < PREAMBLE_SYMBOL_COUNT; i++) {
            if (symbols[start + i] == PREAMBLE_SYMBOLS[i]) {
                score++;
            }
        }
        return score;
    }

    private static DecodeResult tryParseFrame(
            int[] symbols,
            int payloadStartSymbol,
            int preambleScore,
            int offset,
            int codecMode
    ) {
        int totalPayloadSymbols = symbols.length - payloadStartSymbol;
        if (totalPayloadSymbols < 20) {
            return DecodeResult.empty(codecMode);
        }

        int[] bits = symbolsToBits(symbols, payloadStartSymbol, totalPayloadSymbols);
        if (bits.length < 40) {
            return DecodeResult.empty(codecMode);
        }

        int sync = bitsToInt(bits, 0, 16);
        if (sync != SYNC_WORD) {
            return DecodeResult.empty(codecMode);
        }

        int payloadLength = bitsToInt(bits, 16, 8);
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            return DecodeResult.empty(codecMode);
        }

        int expectedBits = 16 + 8 + payloadLength * 8 + 16;
        if (bits.length < expectedBits) {
            return DecodeResult.empty(codecMode);
        }

        byte[] payload = bitsToBytes(bits, 24, payloadLength);
        int receivedCrc = bitsToInt(bits, 24 + payloadLength * 8, 16);
        int computedCrc = crc16Ccitt(payloadLength, payload);

        boolean crcOk = receivedCrc == computedCrc;
        String text;
        try {
            text = new String(payload, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            text = "";
        }

        return new DecodeResult(
                true,
                crcOk,
                text,
                payloadLength,
                preambleScore,
                offset,
                codecMode
        );
    }

    private static int getSamplesPerSymbol(int sampleRate) {
        return Math.max(1, Math.round(sampleRate / SYMBOL_RATE));
    }

    private static float[] buildToneSet(float baseFrequencyHz, int sampleRate) {
        float nyquistSafeMax = sampleRate * 0.45f;
        float low = Math.max(200.0f, Math.min(baseFrequencyHz, nyquistSafeMax - 3.0f * TONE_SPACING_HZ));
        return new float[]{
                low,
                low + TONE_SPACING_HZ,
                low + 2.0f * TONE_SPACING_HZ,
                low + 3.0f * TONE_SPACING_HZ
        };
    }

    private static byte[] buildFrameBytes(byte[] payload) {
        int length = payload.length;
        byte[] frame = new byte[2 + 1 + length + 2];
        frame[0] = (byte) ((SYNC_WORD >> 8) & 0xFF);
        frame[1] = (byte) (SYNC_WORD & 0xFF);
        frame[2] = (byte) (length & 0xFF);
        System.arraycopy(payload, 0, frame, 3, length);
        int crc = crc16Ccitt(length, payload);
        frame[3 + length] = (byte) ((crc >> 8) & 0xFF);
        frame[4 + length] = (byte) (crc & 0xFF);
        return frame;
    }

    private static int crc16Ccitt(int payloadLength, byte[] payload) {
        int crc = 0xFFFF;
        crc = crcUpdate(crc, payloadLength & 0xFF);
        for (byte b : payload) {
            crc = crcUpdate(crc, b & 0xFF);
        }
        return crc & 0xFFFF;
    }

    private static int crcUpdate(int crc, int value) {
        int c = crc ^ (value << 8);
        for (int i = 0; i < 8; i++) {
            if ((c & 0x8000) != 0) {
                c = (c << 1) ^ 0x1021;
            } else {
                c <<= 1;
            }
        }
        return c & 0xFFFF;
    }

    private static int[] bytesToBits(byte[] data) {
        int[] bits = new int[data.length * 8];
        int idx = 0;
        for (byte datum : data) {
            int v = datum & 0xFF;
            for (int shift = 7; shift >= 0; shift--) {
                bits[idx++] = (v >> shift) & 1;
            }
        }
        return bits;
    }

    private static int[] bitsToSymbols(int[] bits) {
        int symbolCount = bits.length / 2;
        int[] symbols = new int[symbolCount];
        for (int i = 0; i < symbolCount; i++) {
            int b0 = bits[i * 2];
            int b1 = bits[i * 2 + 1];
            symbols[i] = ((b0 & 1) << 1) | (b1 & 1);
        }
        return symbols;
    }

    private static int[] symbolsToBits(int[] symbols, int start, int count) {
        int[] bits = new int[count * 2];
        int idx = 0;
        for (int i = 0; i < count; i++) {
            int s = symbols[start + i] & 0x03;
            bits[idx++] = (s >> 1) & 1;
            bits[idx++] = s & 1;
        }
        return bits;
    }

    private static int bitsToInt(int[] bits, int start, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 1) | (bits[start + i] & 1);
        }
        return value;
    }

    private static byte[] bitsToBytes(int[] bits, int start, int byteCount) {
        byte[] output = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            int v = bitsToInt(bits, start + i * 8, 8);
            output[i] = (byte) (v & 0xFF);
        }
        return output;
    }
}
