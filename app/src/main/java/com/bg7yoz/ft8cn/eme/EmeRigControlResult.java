package com.bg7yoz.ft8cn.eme;

import java.util.Locale;

public final class EmeRigControlResult {
    public final boolean success;
    public final String action;
    public final String rigName;
    public final long beforeFrequencyHz;
    public final long targetFrequencyHz;
    public final double correctionHz;
    public final boolean transmitting;
    public final String reason;

    private EmeRigControlResult(boolean success,
                                String action,
                                String rigName,
                                long beforeFrequencyHz,
                                long targetFrequencyHz,
                                double correctionHz,
                                boolean transmitting,
                                String reason) {
        this.success = success;
        this.action = action == null ? "" : action;
        this.rigName = rigName == null ? "-" : rigName;
        this.beforeFrequencyHz = beforeFrequencyHz;
        this.targetFrequencyHz = targetFrequencyHz;
        this.correctionHz = correctionHz;
        this.transmitting = transmitting;
        this.reason = reason == null ? "" : reason;
    }

    public static EmeRigControlResult success(String action,
                                              String rigName,
                                              long beforeFrequencyHz,
                                              long targetFrequencyHz,
                                              double correctionHz,
                                              boolean transmitting) {
        return new EmeRigControlResult(
                true,
                action,
                rigName,
                beforeFrequencyHz,
                targetFrequencyHz,
                correctionHz,
                transmitting,
                "ok");
    }

    public static EmeRigControlResult failure(String action,
                                              String rigName,
                                              long beforeFrequencyHz,
                                              long targetFrequencyHz,
                                              double correctionHz,
                                              boolean transmitting,
                                              String reason) {
        return new EmeRigControlResult(
                false,
                action,
                rigName,
                beforeFrequencyHz,
                targetFrequencyHz,
                correctionHz,
                transmitting,
                reason);
    }

    public String toSummary() {
        return String.format(
                Locale.US,
                "success=%s action=%s rig=%s before=%d target=%d correctionHz=%.1f transmitting=%s reason=%s",
                success ? "true" : "false",
                action,
                rigName,
                beforeFrequencyHz,
                targetFrequencyHz,
                correctionHz,
                transmitting ? "true" : "false",
                reason);
    }
}
