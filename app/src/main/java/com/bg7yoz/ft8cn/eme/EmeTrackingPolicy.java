package com.bg7yoz.ft8cn.eme;

public final class EmeTrackingPolicy {
    public final boolean enabled;
    public final boolean useCurrentRigFrequency;
    public final long fixedBaseFrequencyHz;
    public final double maxCorrectionHz;
    public final int updateIntervalSeconds;
    public final double minElevationDeg;
    public final boolean allowCorrectionWhileTransmitting;
    public final boolean restoreFrequencyOnDisable;
    public final EmeAssistState.CorrectionDirectionMode correctionDirectionMode;

    public EmeTrackingPolicy(boolean enabled,
                             boolean useCurrentRigFrequency,
                             long fixedBaseFrequencyHz,
                             double maxCorrectionHz,
                             int updateIntervalSeconds,
                             double minElevationDeg,
                             boolean allowCorrectionWhileTransmitting,
                             boolean restoreFrequencyOnDisable,
                             EmeAssistState.CorrectionDirectionMode correctionDirectionMode) {
        this.enabled = enabled;
        this.useCurrentRigFrequency = useCurrentRigFrequency;
        this.fixedBaseFrequencyHz = fixedBaseFrequencyHz;
        this.maxCorrectionHz = Math.max(0.0, maxCorrectionHz);
        this.updateIntervalSeconds = Math.max(1, Math.min(updateIntervalSeconds, 60));
        this.minElevationDeg = minElevationDeg;
        this.allowCorrectionWhileTransmitting = allowCorrectionWhileTransmitting;
        this.restoreFrequencyOnDisable = restoreFrequencyOnDisable;
        this.correctionDirectionMode = correctionDirectionMode == null
                ? EmeAssistState.CorrectionDirectionMode.RX_CORRECTION
                : correctionDirectionMode;
    }
}
