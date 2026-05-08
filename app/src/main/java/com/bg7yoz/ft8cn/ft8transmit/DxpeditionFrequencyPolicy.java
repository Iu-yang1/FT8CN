package com.bg7yoz.ft8cn.ft8transmit;

public final class DxpeditionFrequencyPolicy {
    public static final float TX_AUDIO_MIN_HZ = 200f;
    public static final float TX_AUDIO_MAX_HZ = 4000f;

    public static final float FOX_TX_MIN_HZ = 300f;
    public static final float FOX_TX_MAX_HZ = 900f;
    public static final float FOX_RANDOM_TX_MAX_HZ = 600f;

    public static final float HOUND_INITIAL_TX_MIN_HZ = 1000f;
    public static final float HOUND_INITIAL_TX_MAX_HZ = 4000f;
    public static final float HOUND_R_REPORT_SHIFT_HZ = 300f;

    private DxpeditionFrequencyPolicy() {
    }

    public static boolean isFoxTxFrequency(float hz) {
        return hz >= FOX_TX_MIN_HZ && hz <= FOX_TX_MAX_HZ;
    }

    public static boolean isHoundInitialFrequency(float hz) {
        return hz >= HOUND_INITIAL_TX_MIN_HZ && hz <= HOUND_INITIAL_TX_MAX_HZ;
    }

    public static float clampFoxTxFrequency(float hz) {
        return clamp(hz, FOX_TX_MIN_HZ, FOX_TX_MAX_HZ);
    }

    public static float clampHoundInitialFrequency(float hz) {
        return clamp(hz, HOUND_INITIAL_TX_MIN_HZ, HOUND_INITIAL_TX_MAX_HZ);
    }

    public static float pickFoxCqFrequency(long seed) {
        int slots = 1 + (int) ((FOX_RANDOM_TX_MAX_HZ - FOX_TX_MIN_HZ) / 60f);
        if (slots <= 0) {
            return FOX_TX_MIN_HZ;
        }
        int idx = (int) (Math.abs(seed) % slots);
        return FOX_TX_MIN_HZ + idx * 60f;
    }

    public static float pickFoxSlotFrequency(int slotIndex) {
        if (slotIndex < 0) {
            slotIndex = 0;
        }
        return clampFoxTxFrequency(FOX_TX_MIN_HZ + slotIndex * 60f);
    }

    public static float resolveHoundRReportFrequency(float foxReplyFrequency, int tx3SentCount) {
        float base = clampFoxTxFrequency(foxReplyFrequency);
        if (tx3SentCount <= 0) {
            return base;
        }

        int shiftStep = (tx3SentCount + 1) / 2;
        float delta = shiftStep * HOUND_R_REPORT_SHIFT_HZ;
        boolean upper = (tx3SentCount % 2) == 1;
        float candidate = upper ? base + delta : base - delta;
        if (candidate < TX_AUDIO_MIN_HZ || candidate > TX_AUDIO_MAX_HZ) {
            candidate = upper ? base - delta : base + delta;
        }
        return clamp(candidate, TX_AUDIO_MIN_HZ, TX_AUDIO_MAX_HZ);
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}

