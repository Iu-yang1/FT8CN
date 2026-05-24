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
        this.enabled = enabled;
        this.mode = mode;
        this.ownEcho = ownEcho;
        this.mutual = mutual;
        this.manual = manual;
        this.lastDopplerHz = lastDopplerHz;
        this.previewFrequencyHz = previewFrequencyHz;
        this.observerLocation = observerLocation;
        this.moonEphemeris = moonEphemeris;
        this.statusText = statusText == null ? "" : statusText;
    }
}
