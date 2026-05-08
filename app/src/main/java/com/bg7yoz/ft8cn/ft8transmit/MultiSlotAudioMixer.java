package com.bg7yoz.ft8cn.ft8transmit;

import android.util.Log;

import java.util.ArrayList;

public final class MultiSlotAudioMixer {
    private static final String TAG = "MultiSlotAudioMixer";

    private MultiSlotAudioMixer() {
    }

    public static float[] build(MultiSlotTransmitPlan plan, int sampleRate) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }

        if (plan.size() == 1) {
            MultiSlotTransmitItem item = plan.getPrimaryItem();
            return GenerateFTx.generateFtX(
                    item.message,
                    item.frequencyHz,
                    sampleRate,
                    plan.getSignalMode()
            );
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
                Log.w(TAG, "skip empty slot wave: " + item.slotIndex);
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
        return mixed;
    }
}
