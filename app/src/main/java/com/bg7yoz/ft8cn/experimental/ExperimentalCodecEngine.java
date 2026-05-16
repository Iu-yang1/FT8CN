package com.bg7yoz.ft8cn.experimental;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 从 Python 原型迁移的实验性 4FSK / CPFSK 调制解调器路径。
 *
 * Android 移植版本保持了与 Python 链路相同的高层结构：
 * 分布式同步块、GF(4) 编码段、CRC 保护的载荷，以及在载荷解码前的粗略/精细 CFO 搜索。
 */
public final class ExperimentalCodecEngine {
    private static final ExperimentalGF4Codec GF4_CODEC = new ExperimentalGF4Codec();
    private static final ExperimentalCodecConfig.SyncLayout SYNC_LAYOUT =
            ExperimentalCodecConfig.buildSyncLayout();
    private static final int LENGTH_INFO_BITS = 8;
    private static final int OFFSET_SEARCH_DIVISOR = 8;
    private static final double EPSILON = 1.0e-12;

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
        public final int estimatedSnrDb;

        private DecodeResult(
                boolean frameFound,
                boolean crcOk,
                String payloadText,
                int payloadLength,
                int preambleScore,
                int symbolOffset,
                int codecMode,
                int estimatedSnrDb
        ) {
            this.frameFound = frameFound;
            this.crcOk = crcOk;
            this.payloadText = payloadText;
            this.payloadLength = payloadLength;
            this.preambleScore = preambleScore;
            this.symbolOffset = symbolOffset;
            this.codecMode = codecMode;
            this.estimatedSnrDb = estimatedSnrDb;
        }

        public static DecodeResult empty(int codecMode) {
            return new DecodeResult(false, false, "", 0, 0, -1, codecMode, -99);
        }
    }

    private static final class SyncCandidate {
        public final int packetStartSample;
        public final int sampleOffset;
        public final double frequencyOffsetHz;
        public final double prefixScore;

        private SyncCandidate(
                int packetStartSample,
                int sampleOffset,
                double frequencyOffsetHz,
                double prefixScore
        ) {
            this.packetStartSample = packetStartSample;
            this.sampleOffset = sampleOffset;
            this.frequencyOffsetHz = frequencyOffsetHz;
            this.prefixScore = prefixScore;
        }
    }

    private static final class DemodulationResult {
        public final double[][] symbolLogs;
        public final double[][] toneEnergies;

        private DemodulationResult(double[][] symbolLogs, double[][] toneEnergies) {
            this.symbolLogs = symbolLogs;
            this.toneEnergies = toneEnergies;
        }
    }

    private static final class DecodedSection {
        public final int[] bits;
        public final int consumedSymbols;

        private DecodedSection(int[] bits, int consumedSymbols) {
            this.bits = bits;
            this.consumedSymbols = consumedSymbols;
        }
    }

    /**
     * 把业务消息编码成 experimental 的本地回环波形。
     * 注意这里不是 FT8/FT4 标准发射，只服务实验链路自测。
     */
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

        int samplesPerSymbol = ExperimentalCodecConfig.getSamplesPerSymbol(sampleRate);
        byte[] payload = buildPayloadBytes(message);
        int[] packetSymbols = buildPacketSymbols(payload);
        float[] tones = ExperimentalCodecConfig.buildToneSet(baseFrequencyHz, sampleRate);
        float[] packetWave = modulateSymbols(
                packetSymbols,
                sampleRate,
                samplesPerSymbol,
                tones,
                codecMode == GeneralVariables.EXP_CODEC_MODE_CPFSK
        );
        // 实验性的单次发射现在使用真实的调制解调器数据包时长，
        // 而不是强制进行 FT8/FT4 时槽大小的填充或裁剪。
        return packetWave;
    }

    /**
     * experimental 解码入口。
     * 先做候选搜索，再做 GF(4) 译码和 CRC 校验，最后只保留最优结果。
     */
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

        int samplesPerSymbol = ExperimentalCodecConfig.getSamplesPerSymbol(sampleRate);
        if (samples.length < samplesPerSymbol * SYNC_LAYOUT.prefixSymbols.length) {
            return DecodeResult.empty(codecMode);
        }

        float[] tones = ExperimentalCodecConfig.buildToneSet(baseFrequencyHz, sampleRate);
        List<SyncCandidate> syncCandidates = iterPacketCandidates(
                samples,
                tones,
                sampleRate,
                samplesPerSymbol,
                codecMode == GeneralVariables.EXP_CODEC_MODE_CPFSK
        );
        if (syncCandidates.isEmpty()) {
            return DecodeResult.empty(codecMode);
        }

        int decodeLimit = Math.min(
                ExperimentalCodecConfig.DECODE_CANDIDATE_LIMIT,
                syncCandidates.size()
        );
        DecodeResult best = DecodeResult.empty(codecMode);
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int index = 0; index < decodeLimit; index++) {
            SyncCandidate candidate = syncCandidates.get(index);
            DecodeResult decoded = tryDecodeCandidate(
                    samples,
                    tones,
                    sampleRate,
                    samplesPerSymbol,
                    codecMode,
                    candidate
            );
            if (!decoded.frameFound) {
                continue;
            }

            double quality = decoded.preambleScore + (decoded.crcOk ? 100000.0 : 0.0);
            if (quality > bestScore) {
                bestScore = quality;
                best = decoded;
            }
            if (decoded.crcOk) {
                return decoded;
            }
        }

        return best;
    }

    private static DecodeResult tryDecodeCandidate(
            float[] samples,
            float[] tones,
            int sampleRate,
            int samplesPerSymbol,
            int codecMode,
            SyncCandidate candidate
    ) {
        int packetStart = candidate.packetStartSample;
        if (packetStart < 0 || packetStart >= samples.length) {
            return DecodeResult.empty(codecMode);
        }

        float[] packetSamples = Arrays.copyOfRange(samples, packetStart, samples.length);
        DemodulationResult demodulation = demodulateSymbolLogLikelihoods(
                packetSamples,
                0,
                samplesPerSymbol,
                sampleRate,
                tones,
                candidate.frequencyOffsetHz,
                ExperimentalCodecConfig.JOINT_DETECTION_BLOCK_SIZE,
                codecMode == GeneralVariables.EXP_CODEC_MODE_CPFSK
        );
        if (demodulation.symbolLogs.length == 0) {
            return DecodeResult.empty(codecMode);
        }

        int cursor = SYNC_LAYOUT.prefixSymbols.length;
        try {
            DecodedSection lengthSection = decodeSection(demodulation.symbolLogs, cursor, LENGTH_INFO_BITS);
            byte[] lengthBytes = ExperimentalCodecConfig.bitsToBytes(lengthSection.bits);
            int payloadLength = lengthBytes[0] & 0xFF;
            if (payloadLength > ExperimentalCodecConfig.MAX_PAYLOAD_BYTES) {
                return DecodeResult.empty(codecMode);
            }
            cursor += lengthSection.consumedSymbols;

            double middleScore = symbolSequenceScore(
                    demodulation.symbolLogs,
                    cursor,
                    SYNC_LAYOUT.middleSymbols
            );
            if (!Double.isFinite(middleScore)) {
                return DecodeResult.empty(codecMode);
            }
            cursor += SYNC_LAYOUT.middleSymbols.length;

            int payloadInfoBits = payloadLength * 8 + 16;
            DecodedSection payloadSection = decodeSection(
                    demodulation.symbolLogs,
                    cursor,
                    payloadInfoBits
            );
            byte[] payloadCrcBytes = ExperimentalCodecConfig.bitsToBytes(payloadSection.bits);
            cursor += payloadSection.consumedSymbols;

            double tailScore = symbolSequenceScore(
                    demodulation.symbolLogs,
                    cursor,
                    SYNC_LAYOUT.tailSymbols
            );
            if (!Double.isFinite(tailScore)) {
                return DecodeResult.empty(codecMode);
            }

            ParsedPayload parsed = parsePayloadSection(payloadLength, payloadCrcBytes);
            String text = "";
            if (parsed.crcOk) {
                text = new String(parsed.payload, StandardCharsets.UTF_8);
            }

            int syncScore = (int) Math.round(candidate.prefixScore + middleScore + tailScore);
            int totalFrameSymbols = cursor + SYNC_LAYOUT.tailSymbols.length;
            int estimatedSnrDb = estimateFrameSnrDb(
                    demodulation.toneEnergies,
                    totalFrameSymbols
            );

            return new DecodeResult(
                    true,
                    parsed.crcOk,
                    text,
                    payloadLength,
                    syncScore,
                    candidate.sampleOffset,
                    codecMode,
                    estimatedSnrDb
            );
        } catch (IllegalArgumentException ignored) {
            return DecodeResult.empty(codecMode);
        } catch (Throwable ignored) {
            return DecodeResult.empty(codecMode);
        }
    }

    private static DecodedSection decodeSection(double[][] symbolLogs, int cursor, int infoBitCount) {
        int consumedSymbols = GF4_CODEC.encodedSymbolCount(infoBitCount);
        if (cursor < 0 || cursor + consumedSymbols > symbolLogs.length) {
            throw new IllegalArgumentException("编码段超出符号对数流");
        }
        double[][] sectionLogs = copyRows(symbolLogs, cursor, consumedSymbols);
        int[] decodedBits = GF4_CODEC.decodeSymbolLogLikelihoods(sectionLogs, infoBitCount);
        return new DecodedSection(decodedBits, consumedSymbols);
    }

    private static byte[] buildPayloadBytes(Ft8Message message) {
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

        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= ExperimentalCodecConfig.MAX_PAYLOAD_BYTES) {
            return utf8;
        }
        return Arrays.copyOf(utf8, ExperimentalCodecConfig.MAX_PAYLOAD_BYTES);
    }

    private static int[] buildPacketSymbols(byte[] payload) {
        byte[] lengthField = new byte[]{(byte) (payload.length & 0xFF)};
        byte[] payloadSection = buildPayloadSection(payload);

        int[] lengthBits = GF4_CODEC.encodeBits(ExperimentalCodecConfig.bytesToBits(lengthField));
        int[] payloadBits = GF4_CODEC.encodeBits(ExperimentalCodecConfig.bytesToBits(payloadSection));

        int[] lengthSymbols = ExperimentalCodecConfig.bitsToSymbols(lengthBits);
        int[] payloadSymbols = ExperimentalCodecConfig.bitsToSymbols(payloadBits);

        int totalSymbols = SYNC_LAYOUT.prefixSymbols.length
                + lengthSymbols.length
                + SYNC_LAYOUT.middleSymbols.length
                + payloadSymbols.length
                + SYNC_LAYOUT.tailSymbols.length;
        int[] packet = new int[totalSymbols];
        int cursor = 0;
        System.arraycopy(SYNC_LAYOUT.prefixSymbols, 0, packet, cursor, SYNC_LAYOUT.prefixSymbols.length);
        cursor += SYNC_LAYOUT.prefixSymbols.length;
        System.arraycopy(lengthSymbols, 0, packet, cursor, lengthSymbols.length);
        cursor += lengthSymbols.length;
        System.arraycopy(SYNC_LAYOUT.middleSymbols, 0, packet, cursor, SYNC_LAYOUT.middleSymbols.length);
        cursor += SYNC_LAYOUT.middleSymbols.length;
        System.arraycopy(payloadSymbols, 0, packet, cursor, payloadSymbols.length);
        cursor += payloadSymbols.length;
        System.arraycopy(SYNC_LAYOUT.tailSymbols, 0, packet, cursor, SYNC_LAYOUT.tailSymbols.length);
        return packet;
    }

    private static byte[] buildPayloadSection(byte[] payload) {
        int crc = crc16Ccitt(payload.length, payload);
        byte[] section = new byte[payload.length + 2];
        System.arraycopy(payload, 0, section, 0, payload.length);
        section[payload.length] = (byte) ((crc >> 8) & 0xFF);
        section[payload.length + 1] = (byte) (crc & 0xFF);
        return section;
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
            double frequency = toneHz[symbol];
            double phaseStep = 2.0 * Math.PI * frequency / sampleRate;
            int start = symbolIndex * samplesPerSymbol;

            if (continuousPhase) {
                for (int n = 0; n < samplesPerSymbol; n++) {
                    phase += phaseStep;
                    output[start + n] = (float) (
                            ExperimentalCodecConfig.AMPLITUDE * Math.sin(phase)
                    );
                }
            } else {
                for (int n = 0; n < samplesPerSymbol; n++) {
                    output[start + n] = (float) (
                            ExperimentalCodecConfig.AMPLITUDE * Math.sin(phaseStep * n)
                    );
                }
            }
        }

        return output;
    }

    /**
     * 先粗搜 CFO 和时偏，再复核前缀分数与有效能量，避免静音窗口误入前列。
     */
    private static List<SyncCandidate> iterPacketCandidates(
            float[] samples,
            float[] tones,
            int sampleRate,
            int samplesPerSymbol,
            boolean cpfskMode
    ) {
        ArrayList<SyncCandidate> coarseHits = new ArrayList<>();
        int offsetStep = Math.max(1, samplesPerSymbol / OFFSET_SEARCH_DIVISOR);
        int minSpacingSamples = Math.max(samplesPerSymbol / 2, 1);

        for (float coarseCfoHz : ExperimentalCodecConfig.CFO_COARSE_SEARCH_HZ) {
            for (int sampleOffset = 0; sampleOffset < samplesPerSymbol; sampleOffset += offsetStep) {
                DemodulationResult demodulation = demodulateSymbolLogLikelihoods(
                        samples,
                        sampleOffset,
                        samplesPerSymbol,
                        sampleRate,
                        tones,
                        coarseCfoHz,
                        1,
                        cpfskMode
                );
                if (demodulation.symbolLogs.length < SYNC_LAYOUT.prefixSymbols.length) {
                    continue;
                }
                collectPrefixCandidates(
                        coarseHits,
                        demodulation.symbolLogs,
                        sampleOffset,
                        samplesPerSymbol,
                        coarseCfoHz,
                        minSpacingSamples
                );
            }
        }

        coarseHits.sort((left, right) -> Double.compare(right.prefixScore, left.prefixScore));
        ArrayList<SyncCandidate> refined = new ArrayList<>();
        for (SyncCandidate coarse : coarseHits) {
            if (refined.size() >= ExperimentalCodecConfig.SYNC_SEARCH_TOP_K) {
                break;
            }

            double bestScore = Double.NEGATIVE_INFINITY;
            double bestFineCfo = coarse.frequencyOffsetHz;
            for (float fineCfoHz : ExperimentalCodecConfig.buildFineFrequencyGrid((float) coarse.frequencyOffsetHz)) {
                double score = prefixScoreAt(
                        samples,
                        coarse.packetStartSample,
                        sampleRate,
                        samplesPerSymbol,
                        tones,
                        fineCfoHz,
                        cpfskMode
                );
                if (score > bestScore) {
                    bestScore = score;
                    bestFineCfo = fineCfoHz;
                }
            }
            if (!Double.isFinite(bestScore)) {
                continue;
            }

            SyncCandidate refinedCandidate = new SyncCandidate(
                    coarse.packetStartSample,
                    coarse.sampleOffset,
                    bestFineCfo,
                    bestScore
            );
            if (!isNearExistingCandidate(refined, refinedCandidate, minSpacingSamples)) {
                refined.add(refinedCandidate);
            }
        }

        refined.sort((left, right) -> Double.compare(right.prefixScore, left.prefixScore));
        return refined;
    }

    private static void collectPrefixCandidates(
            ArrayList<SyncCandidate> coarseHits,
            double[][] symbolLogs,
            int sampleOffset,
            int samplesPerSymbol,
            double coarseCfoHz,
            int minSpacingSamples
    ) {
        ArrayList<SyncCandidate> localHits = new ArrayList<>();
        int limit = symbolLogs.length - SYNC_LAYOUT.prefixSymbols.length;
        for (int startSymbol = 0; startSymbol <= limit; startSymbol++) {
            double score = symbolSequenceScore(symbolLogs, startSymbol, SYNC_LAYOUT.prefixSymbols);
            int packetStartSample = sampleOffset + startSymbol * samplesPerSymbol;
            SyncCandidate candidate = new SyncCandidate(
                    packetStartSample,
                    sampleOffset,
                    coarseCfoHz,
                    score
            );
            if (isNearExistingCandidate(localHits, candidate, minSpacingSamples)) {
                continue;
            }
            localHits.add(candidate);
        }
        localHits.sort((left, right) -> Double.compare(right.prefixScore, left.prefixScore));

        int localKeep = Math.min(3, localHits.size());
        for (int i = 0; i < localKeep; i++) {
            coarseHits.add(localHits.get(i));
        }
    }

    private static boolean isNearExistingCandidate(
            List<SyncCandidate> candidates,
            SyncCandidate candidate,
            int minSpacingSamples
    ) {
        for (SyncCandidate existing : candidates) {
            if (Math.abs(existing.packetStartSample - candidate.packetStartSample) < minSpacingSamples) {
                return true;
            }
        }
        return false;
    }

    private static double prefixScoreAt(
            float[] samples,
            int packetStartSample,
            int sampleRate,
            int samplesPerSymbol,
            float[] tones,
            double frequencyOffsetHz,
            boolean cpfskMode
    ) {
        int prefixSampleCount = SYNC_LAYOUT.prefixSymbols.length * samplesPerSymbol;
        if (packetStartSample < 0 || packetStartSample + prefixSampleCount > samples.length) {
            return Double.NEGATIVE_INFINITY;
        }

        float[] prefixSamples = Arrays.copyOfRange(
                samples,
                packetStartSample,
                packetStartSample + prefixSampleCount
        );
        DemodulationResult demodulation = demodulateSymbolLogLikelihoods(
                prefixSamples,
                0,
                samplesPerSymbol,
                sampleRate,
                tones,
                frequencyOffsetHz,
                1,
                cpfskMode
        );
        if (demodulation.symbolLogs.length < SYNC_LAYOUT.prefixSymbols.length) {
            return Double.NEGATIVE_INFINITY;
        }
        return symbolSequenceScore(demodulation.symbolLogs, 0, SYNC_LAYOUT.prefixSymbols);
    }

    private static double symbolSequenceScore(double[][] symbolLogs, int start, int[] expectedSymbols) {
        if (start < 0 || start + expectedSymbols.length > symbolLogs.length) {
            return Double.NEGATIVE_INFINITY;
        }

        double score = 0.0;
        for (int i = 0; i < expectedSymbols.length; i++) {
            score += symbolLogs[start + i][expectedSymbols[i] & 0x03];
        }
        return score;
    }

    private static DemodulationResult demodulateSymbolLogLikelihoods(
            float[] samples,
            int sampleOffset,
            int samplesPerSymbol,
            int sampleRate,
            float[] tones,
            double frequencyOffsetHz,
            int blockSize,
            boolean cpfskMode
    ) {
        if (blockSize <= 1) {
            return singleSymbolLogLikelihoods(
                    samples,
                    sampleOffset,
                    samplesPerSymbol,
                    sampleRate,
                    tones,
                    frequencyOffsetHz
            );
        }
        return pairSymbolLogLikelihoods(
                samples,
                sampleOffset,
                samplesPerSymbol,
                sampleRate,
                tones,
                frequencyOffsetHz,
                cpfskMode
        );
    }

    private static DemodulationResult singleSymbolLogLikelihoods(
            float[] samples,
            int sampleOffset,
            int samplesPerSymbol,
            int sampleRate,
            float[] tones,
            double frequencyOffsetHz
    ) {
        int symbolCount = (samples.length - sampleOffset) / samplesPerSymbol;
        if (symbolCount <= 0) {
            return new DemodulationResult(new double[0][4], new double[0][4]);
        }

        double[][] energies = new double[symbolCount][4];
        for (int symbolIndex = 0; symbolIndex < symbolCount; symbolIndex++) {
            int start = sampleOffset + symbolIndex * samplesPerSymbol;
            energies[symbolIndex] = singleSymbolEnergies(
                    samples,
                    start,
                    samplesPerSymbol,
                    sampleRate,
                    tones,
                    frequencyOffsetHz
            );
        }
        return new DemodulationResult(symbolEnergiesToLogLikelihoods(energies), energies);
    }

    private static DemodulationResult pairSymbolLogLikelihoods(
            float[] samples,
            int sampleOffset,
            int samplesPerSymbol,
            int sampleRate,
            float[] tones,
            double frequencyOffsetHz,
            boolean cpfskMode
    ) {
        DemodulationResult single = singleSymbolLogLikelihoods(
                samples,
                sampleOffset,
                samplesPerSymbol,
                sampleRate,
                tones,
                frequencyOffsetHz
        );
        int symbolCount = single.symbolLogs.length;
        if (symbolCount < 2) {
            return single;
        }

        double[][] accumulated = new double[symbolCount][4];
        double[] supportCounts = new double[symbolCount];
        for (int pairIndex = 0; pairIndex < symbolCount - 1; pairIndex++) {
            int start = sampleOffset + pairIndex * samplesPerSymbol;
            int end = start + 2 * samplesPerSymbol;
            if (end > samples.length) {
                break;
            }

            double[] pairScores = cpfskMode
                    ? cpfskPairScores(samples, start, samplesPerSymbol, sampleRate, tones, frequencyOffsetHz)
                    : fskPairScores(samples, start, samplesPerSymbol, sampleRate, tones, frequencyOffsetHz);
            double[] pairLogs = normalizeRow(scaleMetricsToLogs(pairScores));

            double[] firstLogs = new double[4];
            double[] secondLogs = new double[4];
            for (int symbol = 0; symbol < 4; symbol++) {
                firstLogs[symbol] = logSumExp(new double[]{
                        pairLogs[symbol * 4],
                        pairLogs[symbol * 4 + 1],
                        pairLogs[symbol * 4 + 2],
                        pairLogs[symbol * 4 + 3]
                });
                secondLogs[symbol] = logSumExp(new double[]{
                        pairLogs[symbol],
                        pairLogs[symbol + 4],
                        pairLogs[symbol + 8],
                        pairLogs[symbol + 12]
                });
            }

            addInPlace(accumulated[pairIndex], firstLogs);
            addInPlace(accumulated[pairIndex + 1], secondLogs);
            supportCounts[pairIndex] += 1.0;
            supportCounts[pairIndex + 1] += 1.0;
        }

        for (int symbolIndex = 0; symbolIndex < symbolCount; symbolIndex++) {
            if (supportCounts[symbolIndex] > 0.0) {
                scaleInPlace(accumulated[symbolIndex], 1.0 / supportCounts[symbolIndex]);
            } else {
                accumulated[symbolIndex] = single.symbolLogs[symbolIndex].clone();
            }
        }

        if (cpfskMode) {
            // CPFSK 配对指标很有用，但在 CFO 不匹配的情况下，
            // 仍能从更简单的符号检测器中受益，将其作为稳定器。
            for (int symbolIndex = 0; symbolIndex < symbolCount; symbolIndex++) {
                for (int tone = 0; tone < 4; tone++) {
                    accumulated[symbolIndex][tone] =
                            0.7 * accumulated[symbolIndex][tone]
                                    + 0.3 * single.symbolLogs[symbolIndex][tone];
                }
                accumulated[symbolIndex] = normalizeRow(accumulated[symbolIndex]);
            }
        }

        return new DemodulationResult(accumulated, single.toneEnergies);
    }

    private static double[] singleSymbolEnergies(
            float[] samples,
            int start,
            int samplesPerSymbol,
            int sampleRate,
            float[] tones,
            double frequencyOffsetHz
    ) {
        double[] energies = new double[4];
        for (int toneIndex = 0; toneIndex < 4; toneIndex++) {
            double frequency = tones[toneIndex] + frequencyOffsetHz;
            double re = 0.0;
            double im = 0.0;
            for (int n = 0; n < samplesPerSymbol; n++) {
                double phase = 2.0 * Math.PI * frequency * n / sampleRate;
                double x = samples[start + n];
                re += x * Math.cos(phase);
                im -= x * Math.sin(phase);
            }
            energies[toneIndex] = re * re + im * im;
        }
        return energies;
    }

    private static double[] fskPairScores(
            float[] samples,
            int start,
            int samplesPerSymbol,
            int sampleRate,
            float[] tones,
            double frequencyOffsetHz
    ) {
        int pairSamples = 2 * samplesPerSymbol;
        double[] scores = new double[16];
        int cursor = 0;
        for (int symbolA = 0; symbolA < 4; symbolA++) {
            for (int symbolB = 0; symbolB < 4; symbolB++) {
                double dot = 0.0;
                double phaseA = 2.0 * Math.PI * (tones[symbolA] + frequencyOffsetHz) / sampleRate;
                double phaseB = 2.0 * Math.PI * (tones[symbolB] + frequencyOffsetHz) / sampleRate;
                for (int n = 0; n < pairSamples; n++) {
                    double phaseStep = n < samplesPerSymbol
                            ? phaseA * n
                            : phaseB * (n - samplesPerSymbol);
                    dot += samples[start + n] * Math.sin(phaseStep);
                }
                scores[cursor++] = dot * dot;
            }
        }
        return scores;
    }

    private static double[] cpfskPairScores(
            float[] samples,
            int start,
            int samplesPerSymbol,
            int sampleRate,
            float[] tones,
            double frequencyOffsetHz
    ) {
        int pairSamples = 2 * samplesPerSymbol;
        double[] scores = new double[16];
        int cursor = 0;
        for (int symbolA = 0; symbolA < 4; symbolA++) {
            for (int symbolB = 0; symbolB < 4; symbolB++) {
                double re = 0.0;
                double im = 0.0;
                double phase = 0.0;
                for (int n = 0; n < pairSamples; n++) {
                    double frequency = (n < samplesPerSymbol ? tones[symbolA] : tones[symbolB])
                            + frequencyOffsetHz;
                    phase += 2.0 * Math.PI * frequency / sampleRate;
                    double sample = samples[start + n];
                    re += sample * Math.cos(phase);
                    im -= sample * Math.sin(phase);
                }
                scores[cursor++] = re * re + im * im;
            }
        }
        return scores;
    }

    private static double[][] symbolEnergiesToLogLikelihoods(double[][] energies) {
        if (energies.length == 0) {
            return new double[0][4];
        }

        double scale = metricScale(energies);
        double[][] logs = new double[energies.length][4];
        for (int row = 0; row < energies.length; row++) {
            for (int tone = 0; tone < 4; tone++) {
                logs[row][tone] = energies[row][tone] / scale;
            }
            logs[row] = normalizeRow(logs[row]);
        }
        return logs;
    }

    private static double[] scaleMetricsToLogs(double[] metrics) {
        if (metrics.length == 0) {
            return new double[0];
        }
        double[] sorted = metrics.clone();
        Arrays.sort(sorted);
        int keepCount = Math.max(sorted.length - 1, 1);
        double noise = 0.0;
        for (int i = 0; i < keepCount; i++) {
            noise += sorted[i];
        }
        noise = Math.max(noise / keepCount, EPSILON);

        double[] output = new double[metrics.length];
        for (int i = 0; i < metrics.length; i++) {
            output[i] = metrics[i] / noise;
        }
        return output;
    }

    private static double metricScale(double[][] metrics) {
        double[] noiseRows = new double[metrics.length];
        for (int row = 0; row < metrics.length; row++) {
            double[] sorted = metrics[row].clone();
            Arrays.sort(sorted);
            int keepCount = Math.max(sorted.length - 1, 1);
            double noise = 0.0;
            for (int i = 0; i < keepCount; i++) {
                noise += sorted[i];
            }
            noiseRows[row] = noise / keepCount;
        }
        Arrays.sort(noiseRows);
        double median = noiseRows[noiseRows.length / 2];
        return Math.max(median, 1.0e-6);
    }

    private static ParsedPayload parsePayloadSection(int payloadLength, byte[] payloadCrcBytes) {
        int expectedLength = payloadLength + 2;
        if (payloadCrcBytes.length < expectedLength) {
            throw new IllegalArgumentException("载荷或 CRC 不完整");
        }

        byte[] payload = Arrays.copyOf(payloadCrcBytes, payloadLength);
        int receivedCrc = ((payloadCrcBytes[payloadLength] & 0xFF) << 8)
                | (payloadCrcBytes[payloadLength + 1] & 0xFF);
        int computedCrc = crc16Ccitt(payloadLength, payload);
        return new ParsedPayload(payload, receivedCrc == computedCrc);
    }

    private static int estimateFrameSnrDb(double[][] toneEnergies, int symbolCount) {
        int limit = Math.min(symbolCount, toneEnergies.length);
        if (limit <= 0) {
            return -99;
        }

        double signalEnergy = 0.0;
        double noiseEnergy = 0.0;
        for (int symbolIndex = 0; symbolIndex < limit; symbolIndex++) {
            double[] row = toneEnergies[symbolIndex].clone();
            Arrays.sort(row);
            double best = row[row.length - 1];
            double others = (row[0] + row[1] + row[2]) / 3.0;
            signalEnergy += Math.max(best - others, EPSILON);
            noiseEnergy += Math.max(others, EPSILON);
        }

        double snrLinear = signalEnergy / Math.max(noiseEnergy, EPSILON);
        double snrDb = 10.0 * Math.log10(Math.max(snrLinear, EPSILON));
        return (int) Math.round(snrDb - 6.0);
    }

    private static int crc16Ccitt(int payloadLength, byte[] payload) {
        int crc = 0xFFFF;
        crc = crcUpdate(crc, payloadLength & 0xFF);
        for (byte value : payload) {
            crc = crcUpdate(crc, value & 0xFF);
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

    private static double[][] copyRows(double[][] source, int start, int count) {
        double[][] output = new double[count][4];
        for (int i = 0; i < count; i++) {
            output[i] = source[start + i].clone();
        }
        return output;
    }

    private static double[] normalizeRow(double[] row) {
        double max = row[0];
        for (int i = 1; i < row.length; i++) {
            if (row[i] > max) {
                max = row[i];
            }
        }

        double[] output = new double[row.length];
        for (int i = 0; i < row.length; i++) {
            output[i] = row[i] - max;
        }
        return output;
    }

    private static double logSumExp(double[] values) {
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            if (value > max) {
                max = value;
            }
        }
        if (!Double.isFinite(max)) {
            return max;
        }

        double sum = 0.0;
        for (double value : values) {
            sum += Math.exp(value - max);
        }
        return max + Math.log(Math.max(sum, EPSILON));
    }

    private static void addInPlace(double[] target, double[] delta) {
        for (int i = 0; i < target.length; i++) {
            target[i] += delta[i];
        }
    }

    private static void scaleInPlace(double[] target, double scale) {
        for (int i = 0; i < target.length; i++) {
            target[i] *= scale;
        }
    }

    private static final class ParsedPayload {
        public final byte[] payload;
        public final boolean crcOk;

        private ParsedPayload(byte[] payload, boolean crcOk) {
            this.payload = payload;
            this.crcOk = crcOk;
        }
    }
}

