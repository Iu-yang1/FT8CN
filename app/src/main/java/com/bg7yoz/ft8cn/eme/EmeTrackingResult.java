package com.bg7yoz.ft8cn.eme;

import java.util.Locale;

public final class EmeTrackingResult {
    public final EmeTrackingStatus status;
    public final String reason;
    public final String rigName;
    public final boolean rigConnected;
    public final boolean transmitting;
    public final EmeFrequencySource frequencySource;
    public final long currentFrequencyHz;
    public final long targetFrequencyHz;
    public final double rawCorrectionHz;
    public final double selectedCorrectionHz;
    public final double rxDopplerHz;
    public final double txDopplerHz;
    public final double moonAzimuthDeg;
    public final double moonElevationDeg;
    public final double rangeRateMps;
    public final EmeRigControlResult catResult;
    public final long timestampMillis;

    public EmeTrackingResult(EmeTrackingStatus status,
                             String reason,
                             String rigName,
                             boolean rigConnected,
                             boolean transmitting,
                             EmeFrequencySource frequencySource,
                             long currentFrequencyHz,
                             long targetFrequencyHz,
                             double rawCorrectionHz,
                             double selectedCorrectionHz,
                             double rxDopplerHz,
                             double txDopplerHz,
                             double moonAzimuthDeg,
                             double moonElevationDeg,
                             double rangeRateMps,
                             EmeRigControlResult catResult,
                             long timestampMillis) {
        this.status = status == null ? EmeTrackingStatus.OFF : status;
        this.reason = reason == null ? "" : reason;
        this.rigName = rigName == null ? "-" : rigName;
        this.rigConnected = rigConnected;
        this.transmitting = transmitting;
        this.frequencySource = frequencySource == null ? EmeFrequencySource.UNAVAILABLE : frequencySource;
        this.currentFrequencyHz = currentFrequencyHz;
        this.targetFrequencyHz = targetFrequencyHz;
        this.rawCorrectionHz = rawCorrectionHz;
        this.selectedCorrectionHz = selectedCorrectionHz;
        this.rxDopplerHz = rxDopplerHz;
        this.txDopplerHz = txDopplerHz;
        this.moonAzimuthDeg = moonAzimuthDeg;
        this.moonElevationDeg = moonElevationDeg;
        this.rangeRateMps = rangeRateMps;
        this.catResult = catResult;
        this.timestampMillis = timestampMillis;
    }

    public static EmeTrackingResult off(String reason, long nowMillis) {
        return new EmeTrackingResult(
                EmeTrackingStatus.OFF,
                reason,
                "-",
                false,
                false,
                EmeFrequencySource.UNAVAILABLE,
                0L,
                0L,
                0.0,
                0.0,
                0.0,
                0.0,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                null,
                nowMillis);
    }

    public String toSummary() {
        return String.format(
                Locale.US,
                "trackingState=%s rigConnected=%s rigName=%s frequencySource=%s currentFrequencyHz=%d rawCorrectionHz=%.1f selectedCorrectionHz=%.1f targetFrequencyHz=%d moonAz=%.1f moonEl=%.1f rangeRateMps=%.2f transmitting=%s resultReason=%s cat=%s",
                status,
                rigConnected ? "true" : "false",
                rigName,
                frequencySource,
                currentFrequencyHz,
                rawCorrectionHz,
                selectedCorrectionHz,
                targetFrequencyHz,
                moonAzimuthDeg,
                moonElevationDeg,
                rangeRateMps,
                transmitting ? "true" : "false",
                reason,
                catResult == null ? "-" : catResult.toSummary());
    }
}
