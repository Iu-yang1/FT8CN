package com.bg7yoz.ft8cn.ft8transmit;

import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.GeneralVariables;

import java.util.ArrayList;

public final class MultiSlotAudioMixer {
    private static final String TAG = "MultiSlotAudioMixer";

    private MultiSlotAudioMixer() {
    }

    private static void logWaveBuildFailure(String reason,
                                            int mode,
                                            int sampleRate,
                                            MultiSlotTransmitItem item) {
        if (item == null || item.message == null) {
            Log.e(TAG, "TX wave build failed: reason=" + reason + ", mode=" + FT8Common.modeToString(mode));
            return;
        }
        Log.e(TAG, String.format(
                java.util.Locale.US,
                "TX wave build failed: reason=%s, mode=%s, submode=%s, trPeriod=%d, sampleRate=%d, freq=%.1f, slot=%d, text=%s",
                reason,
                FT8Common.modeToString(mode),
                FT8Common.getQ65SubmodeLabel(GeneralVariables.getQ65Submode()),
                GeneralVariables.getQ65TrPeriodSeconds(),
                sampleRate,
                item.frequencyHz,
                item.slotIndex,
                item.message.getMessageText()
        ));
    }

    private static String buildWaveStats(float[] wave, int sampleRate) {
        if (wave == null || wave.length == 0) {
            return "samples=0, durationMs=0.0, peak=0.000000, rms=0.000000";
        }
        float peak = 0.0f;
        double energy = 0.0;
        for (float sample : wave) {
            float abs = Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
            energy += sample * sample;
        }
        double rms = Math.sqrt(energy / wave.length);
        float durationMs = sampleRate > 0 ? wave.length * 1000.0f / sampleRate : 0.0f;
        return String.format(
                java.util.Locale.US,
                "samples=%d, durationMs=%.1f, peak=%.6f, rms=%.6f",
                wave.length,
                durationMs,
                peak,
                rms
        );
    }

    private static boolean hasOnlyFiniteSamples(float[] wave) {
        if (wave == null) {
            return false;
        }
        for (float sample : wave) {
            if (!Float.isFinite(sample)) {
                return false;
            }
        }
        return true;
    }

    public static float[] build(MultiSlotTransmitPlan plan, int sampleRate) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }

        if (plan.size() == 1) {
            MultiSlotTransmitItem item = plan.getPrimaryItem();
            Log.d(TAG, String.format(
                    java.util.Locale.US,
                    "build single-slot wave: mode=%s, submode=%s, trPeriod=%d, sampleRate=%d, freq=%.1f, text=%s",
                    FT8Common.modeToString(plan.getSignalMode()),
                    FT8Common.getQ65SubmodeLabel(GeneralVariables.getQ65Submode()),
                    GeneralVariables.getQ65TrPeriodSeconds(),
                    sampleRate,
                    item == null ? 0.0f : item.frequencyHz,
                    item == null || item.message == null ? "" : item.message.getMessageText()
            ));
            float[] wave = GenerateFTx.generateFtX(
                    item.message,
                    item.frequencyHz,
                    sampleRate,
                    plan.getSignalMode()
            );
            if (wave == null || wave.length == 0) {
                logWaveBuildFailure("single-slot-wave-empty", plan.getSignalMode(), sampleRate, item);
            } else if (!hasOnlyFiniteSamples(wave)) {
                logWaveBuildFailure("single-slot-wave-non-finite", plan.getSignalMode(), sampleRate, item);
                return null;
            } else {
                Log.d(TAG, "single-slot wave ready: " + buildWaveStats(wave, sampleRate));
            }
            return wave;
        }

        ArrayList<float[]> waves = new ArrayList<>();
        int maxLength = 0;
        for (MultiSlotTransmitItem item : plan.getItems()) {
            float[] wave = GenerateFTx.generateFtX(
                    item.message,
                    item.frequencyHz,
                    sampleRate,
                    plan.getSignalMode()
            );
            if (wave == null || wave.length == 0) {
                logWaveBuildFailure("multi-slot-wave-empty", plan.getSignalMode(), sampleRate, item);
                continue;
            }
            if (!hasOnlyFiniteSamples(wave)) {
                logWaveBuildFailure("multi-slot-wave-non-finite", plan.getSignalMode(), sampleRate, item);
                continue;
            }
            waves.add(wave);
            if (wave.length > maxLength) {
                maxLength = wave.length;
            }
        }

        if (waves.isEmpty() || maxLength == 0) {
            return null;
        }

        float[] mixed = new float[maxLength];
        float gain = 0.90f / waves.size();
        for (float[] wave : waves) {
            for (int i = 0; i < wave.length; i++) {
                mixed[i] += wave[i] * gain;
            }
        }

        float peak = 0f;
        for (float sample : mixed) {
            float abs = Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
        }
        if (peak > 0.98f) {
            float normalizeGain = 0.98f / peak;
            for (int i = 0; i < mixed.length; i++) {
                mixed[i] *= normalizeGain;
            }
        }
        Log.d(TAG, "mixed wave ready: " + buildWaveStats(mixed, sampleRate));
        return mixed;
    }
}

