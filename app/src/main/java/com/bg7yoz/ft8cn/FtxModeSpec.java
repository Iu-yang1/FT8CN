package com.bg7yoz.ft8cn;

/**
 * FTx 模式元数据。
 * 这一层把调度、收发能力和模式默认值集中起来，避免后续继续把 FT8/FT4 写死在各处。
 */
public final class FtxModeSpec {
    public static final class DecodeFrequencyRange {
        public final int minHz;
        public final int maxHz;

        private DecodeFrequencyRange(int minHz, int maxHz) {
            this.minHz = minHz;
            this.maxHz = maxHz;
        }
    }

    public final int modeId;
    public final String name;
    public final int sampleRate;
    public final int slotDurationMs;
    public final int frameDurationMs;
    public final int transmissionDurationMs;
    public final boolean supportsEarlyDecode;
    public final boolean supportsDeepSupplement;
    public final boolean supportsSubtract;
    public final boolean supportsTx;
    public final boolean supportsRx;
    public final boolean requiresFullSlot;
    public final String defaultLiveProfile;
    public final String defaultDeepProfile;
    public final DecodeFrequencyRange decodeFrequencyRange;
    public final boolean supportedInCurrentBuild;

    private FtxModeSpec(int modeId,
                        String name,
                        int sampleRate,
                        int slotDurationMs,
                        int frameDurationMs,
                        int transmissionDurationMs,
                        boolean supportsEarlyDecode,
                        boolean supportsDeepSupplement,
                        boolean supportsSubtract,
                        boolean supportsTx,
                        boolean supportsRx,
                        boolean requiresFullSlot,
                        String defaultLiveProfile,
                        String defaultDeepProfile,
                        DecodeFrequencyRange decodeFrequencyRange,
                        boolean supportedInCurrentBuild) {
        this.modeId = modeId;
        this.name = name;
        this.sampleRate = sampleRate;
        this.slotDurationMs = slotDurationMs;
        this.frameDurationMs = frameDurationMs;
        this.transmissionDurationMs = transmissionDurationMs;
        this.supportsEarlyDecode = supportsEarlyDecode;
        this.supportsDeepSupplement = supportsDeepSupplement;
        this.supportsSubtract = supportsSubtract;
        this.supportsTx = supportsTx;
        this.supportsRx = supportsRx;
        this.requiresFullSlot = requiresFullSlot;
        this.defaultLiveProfile = defaultLiveProfile;
        this.defaultDeepProfile = defaultDeepProfile;
        this.decodeFrequencyRange = decodeFrequencyRange;
        this.supportedInCurrentBuild = supportedInCurrentBuild;
    }

    private static final FtxModeSpec FT8_SPEC = new FtxModeSpec(
            FT8Common.FT8_MODE,
            "FT8",
            FT8Common.SAMPLE_RATE,
            FT8Common.FT8_SLOT_TIME_MILLISECOND,
            FT8Common.getFrameDurationMs(FT8Common.FT8_MODE),
            FT8Common.getFrameDurationMs(FT8Common.FT8_MODE),
            true,
            true,
            true,
            true,
            true,
            false,
            "live",
            "deep",
            new DecodeFrequencyRange(0, 3000),
            true
    );

    private static final FtxModeSpec FT4_SPEC = new FtxModeSpec(
            FT8Common.FT4_MODE,
            "FT4",
            FT8Common.SAMPLE_RATE,
            FT8Common.FT4_SLOT_TIME_MILLISECOND,
            FT8Common.getFrameDurationMs(FT8Common.FT4_MODE),
            FT8Common.getFrameDurationMs(FT8Common.FT4_MODE),
            true,
            true,
            true,
            true,
            true,
            false,
            "live",
            "deep",
            new DecodeFrequencyRange(0, 3000),
            true
    );

    private static FtxModeSpec buildQ65Spec() {
        int q65Submode = GeneralVariables.getQ65Submode();
        int q65TrPeriodSeconds = GeneralVariables.getQ65TrPeriodSeconds();
        int slotDurationMs = q65TrPeriodSeconds * 1000;
        return new FtxModeSpec(
                FT8Common.Q65_MODE,
                FT8Common.getQ65ModeLabel(q65Submode, q65TrPeriodSeconds),
                FT8Common.SAMPLE_RATE,
                slotDurationMs,
                slotDurationMs,
                slotDurationMs,
                false,
                false,
                false,
                true,
                true,
                true,
                "live",
                "disabled",
                new DecodeFrequencyRange(0, 5000),
                true
        );
    }

    public int samplesPerSlot() {
        if (slotDurationMs <= 0 || sampleRate <= 0) {
            return 0;
        }
        long sampleCount = (long) slotDurationMs * sampleRate / 1000L;
        return sampleCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sampleCount;
    }

    public static FtxModeSpec forMode(int modeId) {
        switch (modeId) {
            case FT8Common.FT8_MODE:
                return FT8_SPEC;
            case FT8Common.FT4_MODE:
                return FT4_SPEC;
            case FT8Common.Q65_MODE:
                return buildQ65Spec();
            default:
                return null;
        }
    }
}
