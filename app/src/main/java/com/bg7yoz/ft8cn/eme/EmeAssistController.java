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

        MoonEphemeris moonEphemeris = MoonEphemeris.unavailable(nowMillis);
        state = new EmeAssistState(
                true,
                EmeAssistState.Mode.DISPLAY_ONLY,
                false,
                false,
                true,
                0.0,
                frequencyHz,
                observerLocation,
                moonEphemeris,
                String.format(Locale.US,
                        "display-only scaffold: grid=%s lat=%.4f lon=%.4f freq=%.1f moon=unavailable",
                        observerLocation.grid,
                        observerLocation.latitudeDeg,
                        observerLocation.longitudeDeg,
                        frequencyHz));
        Log.i(TAG, state.statusText);
        return state;
    }
}
