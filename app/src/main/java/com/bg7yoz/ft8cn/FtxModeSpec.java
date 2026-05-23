package com.bg7yoz.ft8cn;

/**
 * FTx 模式元数据。
 * 这层先把 FT8 / FT4 的调度参数从“到处写死”收拢起来，并为后续 Q65 预留占位。
 * 当前版本只把 Q65 标记为“已知但未接入当前构建”。
 */
public final class FtxModeSpec {
    public final int modeId;
    public final String name;
    public final int sampleRate;
    public final int slotDurationMs;
    public final int frameDurationMs;
    public final boolean supportsEarlyDecode;
    public final boolean supportsDeepSupplement;
    public final boolean supportsSubtract;
    public final String defaultLiveProfile;
    public final String defaultDeepProfile;
    public final boolean supportedInCurrentBuild;

    private FtxModeSpec(int modeId,
                        String name,
                        int sampleRate,
                        int slotDurationMs,
                        int frameDurationMs,
                        boolean supportsEarlyDecode,
                        boolean supportsDeepSupplement,
                        boolean supportsSubtract,
                        String defaultLiveProfile,
                        String defaultDeepProfile,
                        boolean supportedInCurrentBuild) {
        this.modeId = modeId;
        this.name = name;
        this.sampleRate = sampleRate;
        this.slotDurationMs = slotDurationMs;
        this.frameDurationMs = frameDurationMs;
        this.supportsEarlyDecode = supportsEarlyDecode;
        this.supportsDeepSupplement = supportsDeepSupplement;
        this.supportsSubtract = supportsSubtract;
        this.defaultLiveProfile = defaultLiveProfile;
        this.defaultDeepProfile = defaultDeepProfile;
        this.supportedInCurrentBuild = supportedInCurrentBuild;
    }

    private static final FtxModeSpec FT8_SPEC = new FtxModeSpec(
            FT8Common.FT8_MODE,
            "FT8",
            FT8Common.SAMPLE_RATE,
            FT8Common.FT8_SLOT_TIME_MILLISECOND,
            FT8Common.getFrameDurationMs(FT8Common.FT8_MODE),
            true,
            true,
            true,
            "live",
            "deep",
            true
    );

    private static final FtxModeSpec FT4_SPEC = new FtxModeSpec(
            FT8Common.FT4_MODE,
            "FT4",
            FT8Common.SAMPLE_RATE,
            FT8Common.FT4_SLOT_TIME_MILLISECOND,
            FT8Common.getFrameDurationMs(FT8Common.FT4_MODE),
            true,
            true,
            true,
            "live",
            "deep",
            true
    );

    private static final FtxModeSpec Q65_SPEC = new FtxModeSpec(
            FT8Common.Q65_MODE,
            "Q65",
            FT8Common.SAMPLE_RATE,
            0,
            0,
            false,
            false,
            false,
            "unsupported",
            "unsupported",
            false
    );

    public int samplesPerSlot() {
        if (slotDurationMs <= 0 || sampleRate <= 0) {
            return 0;
        }
        return slotDurationMs * sampleRate / 1000;
    }

    public static FtxModeSpec forMode(int modeId) {
        switch (modeId) {
            case FT8Common.FT8_MODE:
                return FT8_SPEC;
            case FT8Common.FT4_MODE:
                return FT4_SPEC;
            case FT8Common.Q65_MODE:
                return Q65_SPEC;
            default:
                return null;
        }
    }
}
