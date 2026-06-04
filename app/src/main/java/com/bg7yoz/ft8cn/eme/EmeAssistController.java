package com.bg7yoz.ft8cn.eme;

import android.util.Log;

import java.util.Locale;

public final class EmeAssistController {
    private static final String TAG = "EmeAssist";
    private static final double[] SANITY_FREQUENCIES_HZ = new double[]{
            144_000_000.0,
            432_000_000.0,
            1_296_000_000.0,
            10_368_000_000.0
    };
    private volatile EmeAssistState state = new EmeAssistState(
            false,
            EmeAssistState.Mode.DISPLAY_ONLY,
            false,
            false,
            true,
            0.0,
            0.0,
            null,
            MoonEphemeris.unavailable(0L),
            "disabled");

    public EmeAssistState getState() {
        return state;
    }

    public EmeAssistState requestApplyMode(EmeAssistState.Mode requestedMode) {
        if (requestedMode == null || requestedMode == EmeAssistState.Mode.DISPLAY_ONLY
                || requestedMode == EmeAssistState.Mode.CAT_MANUAL_APPLY
                || requestedMode == EmeAssistState.Mode.AUDIO_OFFSET_PREVIEW) {
            return state;
        }
        Log.w(TAG, "EME correction mode disabled: requested="
                + requestedMode
                + " reason=tracking-guard");
        EmeAssistState current = state;
        state = new EmeAssistState(
                current.enabled,
                EmeAssistState.Mode.DISPLAY_ONLY,
                current.correctionDirectionMode,
                current.ownEcho,
                current.mutual,
                current.manual,
                current.lastDopplerHz,
                current.lastTxDopplerHz,
                current.previewFrequencyHz,
                current.sourceFrequencyHz,
                current.targetFrequencyHz,
                current.lastAppliedRigFrequencyHz,
                current.lastAppliedAtMillis,
                current.observerLocation,
                current.moonEphemeris,
                "correction-mode-unsupported:" + requestedMode,
                false,
                false,
                false,
                current.correctionUpdateRateLimitMs,
                current.maxCorrectionClampHz,
                current.manualOverride);
        return state;
    }

    public EmeAssistState updateCorrectionPreview(String grid,
                                                  double frequencyHz,
                                                  long nowMillis,
                                                  boolean enabled,
                                                  EmeAssistState.Mode requestedMode,
                                                  EmeAssistState.CorrectionDirectionMode directionMode,
                                                  double maxCorrectionHz,
                                                  double minElevationDeg) {
        EmeAssistState.Mode mode = requestedMode == null
                ? EmeAssistState.Mode.DISPLAY_ONLY
                : requestedMode;
        if (mode.isTrackingMode()) {
            requestApplyMode(mode);
            mode = EmeAssistState.Mode.DISPLAY_ONLY;
        }
        if (!enabled) {
            state = buildState(
                    false,
                    EmeAssistState.Mode.DISPLAY_ONLY,
                    directionMode,
                    frequencyHz,
                    0.0,
                    0.0,
                    frequencyHz,
                    null,
                    MoonEphemeris.unavailable(nowMillis),
                    "disabled",
                    false,
                    false,
                    false,
                    maxCorrectionHz,
                    0L,
                    0L);
            return state;
        }
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            state = buildState(
                    true,
                    mode,
                    directionMode,
                    frequencyHz,
                    0.0,
                    0.0,
                    frequencyHz,
                    null,
                    MoonEphemeris.unavailable(nowMillis),
                    "frequency-unavailable",
                    false,
                    false,
                    false,
                    maxCorrectionHz,
                    0L,
                    0L);
            Log.w(TAG, "EME update skipped: reason=frequency-unavailable freq=" + frequencyHz);
            return state;
        }
        ObserverLocation observerLocation = ObserverLocation.fromGrid(grid);
        if (observerLocation == null) {
            state = buildState(
                    true,
                    mode,
                    directionMode,
                    frequencyHz,
                    0.0,
                    0.0,
                    frequencyHz,
                    null,
                    MoonEphemeris.unavailable(nowMillis),
                    "observer-grid-invalid",
                    false,
                    false,
                    false,
                    maxCorrectionHz,
                    0L,
                    0L);
            Log.w(TAG, "EME update skipped: reason=observer-grid-invalid grid=" + grid);
            return state;
        }

        MoonEphemeris moonEphemeris = MoonEphemeris.calculate(observerLocation, nowMillis);
        double rxCorrectionHz = EmeDopplerCalculator.calculateRxCorrectionHz(frequencyHz, moonEphemeris.rangeRateMps);
        double txCorrectionHz = EmeDopplerCalculator.calculateTxCorrectionHz(frequencyHz, moonEphemeris.rangeRateMps);
        double selectedCorrectionHz = selectCorrectionHz(rxCorrectionHz, txCorrectionHz, directionMode);
        double clampedCorrectionHz = clampCorrectionHz(selectedCorrectionHz, maxCorrectionHz);
        double previewFrequencyHz = frequencyHz + clampedCorrectionHz;
        String status = String.format(Locale.US,
                "%s: grid=%s lat=%.4f lon=%.4f freq=%.1f az=%.1f el=%.1f distKm=%.0f rangeRateMps=%.2f rxHz=%.1f txHz=%.1f selectedHz=%.1f targetHz=%.1f",
                modeLabel(mode),
                observerLocation.grid,
                observerLocation.latitudeDeg,
                observerLocation.longitudeDeg,
                frequencyHz,
                moonEphemeris.azimuthDeg,
                moonEphemeris.elevationDeg,
                moonEphemeris.distanceKm,
                moonEphemeris.rangeRateMps,
                rxCorrectionHz,
                txCorrectionHz,
                clampedCorrectionHz,
                previewFrequencyHz);
        if (moonEphemeris.elevationDeg < minElevationDeg) {
            status += String.format(Locale.US,
                    " guard=below-min-elevation minEl=%.1f",
                    minElevationDeg);
        }
        state = buildState(
                true,
                mode,
                directionMode,
                frequencyHz,
                rxCorrectionHz,
                txCorrectionHz,
                previewFrequencyHz,
                observerLocation,
                moonEphemeris,
                status,
                mode.isCatMode(),
                mode.isCatMode(),
                mode == EmeAssistState.Mode.AUDIO_OFFSET_PREVIEW,
                maxCorrectionHz,
                state.lastAppliedRigFrequencyHz,
                state.lastAppliedAtMillis);
        Log.i(TAG, state.statusText);
        return state;
    }

    public EmeRigControlResult applyManualCatCorrection(String grid,
                                                        double frequencyHz,
                                                        long nowMillis,
                                                        EmeRigControlAdapter rigAdapter,
                                                        boolean useCurrentRigFrequency,
                                                        double maxCorrectionHz,
                                                        double minElevationDeg,
                                                        boolean allowWhileTransmitting,
                                                        EmeAssistState.CorrectionDirectionMode directionMode) {
        if (rigAdapter == null || !rigAdapter.canSetMainFrequency()) {
            return finishCatApply(EmeRigControlResult.failure(
                    "manual-cat-apply",
                    rigAdapter == null ? "-" : rigAdapter.getRigName(),
                    rigAdapter == null ? 0L : rigAdapter.getCachedMainFrequencyHz(),
                    0L,
                    0.0,
                    rigAdapter != null && rigAdapter.isTransmitting(),
                    "rig-unavailable"));
        }
        rigAdapter.requestReadMainFrequency();
        double sourceFrequencyHz = frequencyHz;
        if (useCurrentRigFrequency && rigAdapter.getCachedMainFrequencyHz() > 0L) {
            sourceFrequencyHz = rigAdapter.getCachedMainFrequencyHz();
        }
        EmeAssistState preview = updateCorrectionPreview(
                grid,
                sourceFrequencyHz,
                nowMillis,
                true,
                EmeAssistState.Mode.CAT_MANUAL_APPLY,
                directionMode,
                maxCorrectionHz,
                minElevationDeg);
        if (preview.moonEphemeris == null || !preview.moonEphemeris.available) {
            return finishCatApply(EmeRigControlResult.failure(
                    "manual-cat-apply",
                    rigAdapter.getRigName(),
                    rigAdapter.getCachedMainFrequencyHz(),
                    preview.targetFrequencyHz,
                    preview.targetFrequencyHz - preview.sourceFrequencyHz,
                    rigAdapter.isTransmitting(),
                    preview.statusText));
        }
        if (preview.moonEphemeris.elevationDeg < minElevationDeg) {
            return finishCatApply(EmeRigControlResult.failure(
                    "manual-cat-apply",
                    rigAdapter.getRigName(),
                    rigAdapter.getCachedMainFrequencyHz(),
                    preview.targetFrequencyHz,
                    preview.targetFrequencyHz - preview.sourceFrequencyHz,
                    rigAdapter.isTransmitting(),
                    "below-min-elevation"));
        }
        return finishCatApply(rigAdapter.setMainFrequencyHz(
                preview.targetFrequencyHz,
                preview.targetFrequencyHz - preview.sourceFrequencyHz,
                allowWhileTransmitting));
    }

    public EmeAssistState updateDisplayOnly(String grid, double frequencyHz, long nowMillis) {
        return updateDisplayOnly(grid, frequencyHz, nowMillis, true);
    }

    public EmeAssistState updateDisplayOnly(String grid,
                                            double frequencyHz,
                                            long nowMillis,
                                            boolean enabled) {
        return updateCorrectionPreview(
                grid,
                frequencyHz,
                nowMillis,
                enabled,
                EmeAssistState.Mode.DISPLAY_ONLY,
                EmeAssistState.CorrectionDirectionMode.RX_CORRECTION,
                state.maxCorrectionClampHz,
                0.0);
    }

    public String buildSanitySummary(String grid, long nowMillis) {
        ObserverLocation observerLocation = ObserverLocation.fromGrid(grid);
        if (observerLocation == null) {
            String summary = String.format(Locale.US,
                    "sanity reason=observer-grid-invalid grid=%s",
                    grid == null ? "-" : grid);
            Log.w(TAG, summary);
            return summary;
        }

        MoonEphemeris moonEphemeris = MoonEphemeris.calculate(observerLocation, nowMillis);
        if (!moonEphemeris.available) {
            String summary = "sanity reason=moon-unavailable";
            Log.w(TAG, summary);
            return summary;
        }

        StringBuilder builder = new StringBuilder();
        for (double frequencyHz : SANITY_FREQUENCIES_HZ) {
            double rxDopplerHz = EmeDopplerCalculator.calculateRxCorrectionHz(
                    frequencyHz,
                    moonEphemeris.rangeRateMps);
            double txDopplerHz = EmeDopplerCalculator.calculateTxCorrectionHz(
                    frequencyHz,
                    moonEphemeris.rangeRateMps);
            boolean finite = Double.isFinite(moonEphemeris.azimuthDeg)
                    && Double.isFinite(moonEphemeris.elevationDeg)
                    && Double.isFinite(moonEphemeris.distanceKm)
                    && Double.isFinite(moonEphemeris.rangeRateMps)
                    && Double.isFinite(rxDopplerHz)
                    && Double.isFinite(txDopplerHz);
            String line = String.format(Locale.US,
                    "sanity grid=%s freqMHz=%.3f az=%.1f el=%.1f horizon=%s distanceKm=%.0f rangeRateMps=%.2f rxDopplerHz=%.1f txDopplerHz=%.1f finite=%s",
                    observerLocation.grid,
                    frequencyHz / 1_000_000.0,
                    moonEphemeris.azimuthDeg,
                    moonEphemeris.elevationDeg,
                    moonEphemeris.elevationDeg < 0.0 ? "below" : "above",
                    moonEphemeris.distanceKm,
                    moonEphemeris.rangeRateMps,
                    rxDopplerHz,
                    txDopplerHz,
                    finite ? "true" : "false");
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        String summary = builder.toString();
        Log.i(TAG, summary);
        return summary;
    }

    private EmeRigControlResult finishCatApply(EmeRigControlResult result) {
        EmeAssistState current = state;
        if (result != null && result.success) {
            state = new EmeAssistState(
                    current.enabled,
                    current.mode,
                    current.correctionDirectionMode,
                    current.ownEcho,
                    current.mutual,
                    current.manual,
                    current.lastDopplerHz,
                    current.lastTxDopplerHz,
                    current.previewFrequencyHz,
                    current.sourceFrequencyHz,
                    current.targetFrequencyHz,
                    result.targetFrequencyHz,
                    System.currentTimeMillis(),
                    current.observerLocation,
                    current.moonEphemeris,
                    current.statusText + " catApply=" + result.toSummary(),
                    current.correctionEnabled,
                    current.applyToRig,
                    current.applyToAudio,
                    current.correctionUpdateRateLimitMs,
                    current.maxCorrectionClampHz,
                    current.manualOverride);
        }
        Log.i(TAG, "EME CAT apply result: " + (result == null ? "null" : result.toSummary()));
        return result;
    }

    private EmeAssistState buildState(boolean enabled,
                                      EmeAssistState.Mode mode,
                                      EmeAssistState.CorrectionDirectionMode directionMode,
                                      double sourceFrequencyHz,
                                      double rxCorrectionHz,
                                      double txCorrectionHz,
                                      double previewFrequencyHz,
                                      ObserverLocation observerLocation,
                                      MoonEphemeris moonEphemeris,
                                      String statusText,
                                      boolean correctionEnabled,
                                      boolean applyToRig,
                                      boolean applyToAudio,
                                      double maxCorrectionHz,
                                      long lastAppliedRigFrequencyHz,
                                      long lastAppliedAtMillis) {
        return new EmeAssistState(
                enabled,
                mode,
                directionMode,
                false,
                false,
                true,
                rxCorrectionHz,
                txCorrectionHz,
                previewFrequencyHz,
                Math.round(sourceFrequencyHz),
                Math.round(previewFrequencyHz),
                lastAppliedRigFrequencyHz,
                lastAppliedAtMillis,
                observerLocation,
                moonEphemeris,
                statusText,
                correctionEnabled,
                applyToRig,
                applyToAudio,
                1000L,
                maxCorrectionHz,
                false);
    }

    private double selectCorrectionHz(double rxCorrectionHz,
                                      double txCorrectionHz,
                                      EmeAssistState.CorrectionDirectionMode directionMode) {
        if (directionMode == EmeAssistState.CorrectionDirectionMode.TX_CORRECTION) {
            return txCorrectionHz;
        }
        if (directionMode == EmeAssistState.CorrectionDirectionMode.OWN_ECHO_PREVIEW) {
            return rxCorrectionHz + txCorrectionHz;
        }
        if (directionMode == EmeAssistState.CorrectionDirectionMode.MANUAL) {
            return 0.0;
        }
        return rxCorrectionHz;
    }

    private double clampCorrectionHz(double correctionHz, double maxCorrectionHz) {
        if (!Double.isFinite(correctionHz)) {
            return 0.0;
        }
        double limit = Math.max(0.0, maxCorrectionHz);
        if (limit <= 0.0) {
            return correctionHz;
        }
        return Math.max(-limit, Math.min(limit, correctionHz));
    }

    private String modeLabel(EmeAssistState.Mode mode) {
        if (mode == EmeAssistState.Mode.CAT_MANUAL_APPLY) {
            return "cat-manual";
        }
        if (mode == EmeAssistState.Mode.CAT_TRACKING) {
            return "cat-tracking";
        }
        if (mode == EmeAssistState.Mode.AUDIO_OFFSET_PREVIEW) {
            return "audio-offset-preview";
        }
        if (mode == EmeAssistState.Mode.AUDIO_OFFSET_TRACKING) {
            return "audio-offset-tracking";
        }
        return "display-only";
    }
}
