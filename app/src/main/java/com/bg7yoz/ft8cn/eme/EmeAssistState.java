package com.bg7yoz.ft8cn.eme;

public final class EmeAssistState {
    public enum Mode {
        DISPLAY_ONLY,
        CAT,
        AUDIO_OFFSET
    }

    public final boolean enabled;
    public final Mode mode;
    public final boolean ownEcho;
    public final boolean mutual;
    public final boolean manual;
    public final double lastDopplerHz;
    public final double previewFrequencyHz;
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
                ownEcho,
                mutual,
                manual,
                lastDopplerHz,
                previewFrequencyHz,
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
                          boolean ownEcho,
                          boolean mutual,
                          boolean manual,
                          double lastDopplerHz,
                          double previewFrequencyHz,
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
        this.ownEcho = ownEcho;
        this.mutual = mutual;
        this.manual = manual;
        this.lastDopplerHz = lastDopplerHz;
        this.previewFrequencyHz = previewFrequencyHz;
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
