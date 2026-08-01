package com.bg7yoz.ft8cn.eme;

public final class EmeAssistState {
    public enum Mode {
        DISPLAY_ONLY,
        CAT_MANUAL_APPLY,
        CAT_TRACKING,
        AUDIO_OFFSET_PREVIEW,
        AUDIO_OFFSET_TRACKING;

        public static Mode fromConfigValue(String value) {
            if (value == null || value.trim().length() == 0) {
                return DISPLAY_ONLY;
            }
            String normalized = value.trim().toUpperCase();
            if ("CAT".equals(normalized)) {
                return CAT_MANUAL_APPLY;
            }
            if ("AUDIO_OFFSET".equals(normalized)) {
                return AUDIO_OFFSET_PREVIEW;
            }
            try {
                return Mode.valueOf(normalized);
            } catch (Exception e) {
                return DISPLAY_ONLY;
            }
        }

        public boolean isCatMode() {
            return this == CAT_MANUAL_APPLY || this == CAT_TRACKING;
        }

        public boolean isTrackingMode() {
            return this == CAT_TRACKING || this == AUDIO_OFFSET_TRACKING;
        }
    }

    public enum CorrectionDirectionMode {
        RX_CORRECTION,
        TX_CORRECTION,
        OWN_ECHO_PREVIEW,
        MANUAL;

        public static CorrectionDirectionMode fromConfigValue(String value) {
            if (value == null || value.trim().length() == 0) {
                return RX_CORRECTION;
            }
            try {
                return CorrectionDirectionMode.valueOf(value.trim().toUpperCase());
            } catch (Exception e) {
                return RX_CORRECTION;
            }
        }
    }

    public final boolean enabled;
    public final Mode mode;
    public final CorrectionDirectionMode correctionDirectionMode;
    public final boolean ownEcho;
    public final boolean mutual;
    public final boolean manual;
    public final double lastDopplerHz;
    public final double lastTxDopplerHz;
    public final double previewFrequencyHz;
    public final long sourceFrequencyHz;
    public final long targetFrequencyHz;
    public final long lastAppliedRigFrequencyHz;
    public final long lastAppliedAtMillis;
    public final ObserverLocation observerLocation;
    public final MoonEphemeris moonEphemeris;
    public final String statusText;
    public final boolean correctionEnabled;
    public final boolean applyToRig;
    public final boolean applyToAudio;
    public final long correctionUpdateRateLimitMs;
    public final double maxCorrectionClampHz;
    public final boolean manualOverride;

    public EmeAssistState(boolean enabled,
                          Mode mode,
                          boolean ownEcho,
                          boolean mutual,
                          boolean manual,
                          double lastDopplerHz,
                          double previewFrequencyHz,
                          ObserverLocation observerLocation,
                          MoonEphemeris moonEphemeris,
                          String statusText) {
        this(enabled,
                mode,
                CorrectionDirectionMode.RX_CORRECTION,
                ownEcho,
                mutual,
                manual,
                lastDopplerHz,
                0.0,
                previewFrequencyHz,
                Math.round(previewFrequencyHz),
                Math.round(previewFrequencyHz),
                0L,
                0L,
                observerLocation,
                moonEphemeris,
                statusText,
                false,
                false,
                false,
                1000L,
                5000.0,
                false);
    }

    public EmeAssistState(boolean enabled,
                          Mode mode,
                          CorrectionDirectionMode correctionDirectionMode,
                          boolean ownEcho,
                          boolean mutual,
                          boolean manual,
                          double lastDopplerHz,
                          double lastTxDopplerHz,
                          double previewFrequencyHz,
                          long sourceFrequencyHz,
                          long targetFrequencyHz,
                          long lastAppliedRigFrequencyHz,
                          long lastAppliedAtMillis,
                          ObserverLocation observerLocation,
                          MoonEphemeris moonEphemeris,
                          String statusText,
                          boolean correctionEnabled,
                          boolean applyToRig,
                          boolean applyToAudio,
                          long correctionUpdateRateLimitMs,
                          double maxCorrectionClampHz,
                          boolean manualOverride) {
        this.enabled = enabled;
        this.mode = mode == null ? Mode.DISPLAY_ONLY : mode;
        this.correctionDirectionMode = correctionDirectionMode == null
                ? CorrectionDirectionMode.RX_CORRECTION
                : correctionDirectionMode;
        this.ownEcho = ownEcho;
        this.mutual = mutual;
        this.manual = manual;
        this.lastDopplerHz = lastDopplerHz;
        this.lastTxDopplerHz = lastTxDopplerHz;
        this.previewFrequencyHz = previewFrequencyHz;
        this.sourceFrequencyHz = sourceFrequencyHz;
        this.targetFrequencyHz = targetFrequencyHz;
        this.lastAppliedRigFrequencyHz = lastAppliedRigFrequencyHz;
        this.lastAppliedAtMillis = lastAppliedAtMillis;
        this.observerLocation = observerLocation;
        this.moonEphemeris = moonEphemeris;
        this.statusText = statusText == null ? "" : statusText;
        this.correctionEnabled = correctionEnabled;
        this.applyToRig = applyToRig;
        this.applyToAudio = applyToAudio;
        this.correctionUpdateRateLimitMs = Math.max(100L, correctionUpdateRateLimitMs);
        this.maxCorrectionClampHz = Math.max(0.0, maxCorrectionClampHz);
        this.manualOverride = manualOverride;
    }
}
