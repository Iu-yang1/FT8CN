package com.bg7yoz.ft8cn.eme;

import android.util.Log;

import java.util.Locale;

public final class EmeAssistController {
    private static final String TAG = "EmeAssist";
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
}
