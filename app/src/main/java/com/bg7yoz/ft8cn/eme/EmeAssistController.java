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
        if (requestedMode == null || requestedMode == EmeAssistState.Mode.DISPLAY_ONLY) {
            return state;
        }
        Log.w(TAG, "EME correction mode disabled: requested="
                + requestedMode
                + " reason=display-only-guard");
        EmeAssistState current = state;
        state = new EmeAssistState(
                current.enabled,
                EmeAssistState.Mode.DISPLAY_ONLY,
                current.ownEcho,
                current.mutual,
                current.manual,
                current.lastDopplerHz,
                current.previewFrequencyHz,
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

    public EmeAssistState updateDisplayOnly(String grid, double frequencyHz, long nowMillis) {
        return updateDisplayOnly(grid, frequencyHz, nowMillis, true);
    }

    public EmeAssistState updateDisplayOnly(String grid,
                                            double frequencyHz,
                                            long nowMillis,
                                            boolean enabled) {
        if (!enabled) {
            state = new EmeAssistState(
                    false,
                    EmeAssistState.Mode.DISPLAY_ONLY,
                    false,
                    false,
                    true,
                    0.0,
                    frequencyHz,
                    null,
                    MoonEphemeris.unavailable(nowMillis),
                    "disabled");
            return state;
        }
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            state = new EmeAssistState(
                    true,
                    EmeAssistState.Mode.DISPLAY_ONLY,
                    false,
                    false,
                    true,
                    0.0,
                    frequencyHz,
                    null,
                    MoonEphemeris.unavailable(nowMillis),
                    "frequency-unavailable");
            Log.w(TAG, "EME display-only update skipped: reason=frequency-unavailable freq=" + frequencyHz);
            return state;
        }
        ObserverLocation observerLocation = ObserverLocation.fromGrid(grid);
        if (observerLocation == null) {
            state = new EmeAssistState(
                    true,
                    EmeAssistState.Mode.DISPLAY_ONLY,
                    false,
                    false,
                    true,
                    0.0,
                    frequencyHz,
                    null,
                    MoonEphemeris.unavailable(nowMillis),
                    "observer-grid-invalid");
            Log.w(TAG, "EME display-only update skipped: reason=observer-grid-invalid grid=" + grid);
            return state;
        }

        MoonEphemeris moonEphemeris = MoonEphemeris.calculate(observerLocation, nowMillis);
        double rxCorrectionHz = EmeDopplerCalculator.calculateRxCorrectionHz(frequencyHz, moonEphemeris.rangeRateMps);
        double txCorrectionHz = EmeDopplerCalculator.calculateTxCorrectionHz(frequencyHz, moonEphemeris.rangeRateMps);
        double previewFrequencyHz = frequencyHz + rxCorrectionHz;
        state = new EmeAssistState(
                true,
                EmeAssistState.Mode.DISPLAY_ONLY,
                false,
                false,
                true,
                rxCorrectionHz,
                previewFrequencyHz,
                observerLocation,
                moonEphemeris,
                String.format(Locale.US,
                        "display-only: grid=%s lat=%.4f lon=%.4f freq=%.1f az=%.1f el=%.1f distKm=%.0f rangeRateMps=%.2f dopplerRxHz=%.1f dopplerTxHz=%.1f correctedPreviewHz=%.1f",
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
                        previewFrequencyHz));
        Log.i(TAG, state.statusText);
        return state;
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
}
