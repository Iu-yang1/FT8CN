package com.bg7yoz.ft8cn.ft8transmit;

import com.bg7yoz.ft8cn.GeneralVariables;

import java.util.Locale;

public final class DxpeditionFoxSlotFrequencyConfig {
    public static final int STANDARD_START_HZ = 300;
    public static final int STANDARD_STEP_HZ = 60;
    public static final int MANUAL_START_HZ = 1300;
    public static final int MIN_STEP_HZ = 20;
    public static final int MAX_STEP_HZ = 300;

    private DxpeditionFoxSlotFrequencyConfig() {
    }

    public static float resolveSlotFrequency(int slotIndex) {
        return resolveSlotFrequency(
                slotIndex,
                GeneralVariables.dxpeditionFoxManualSlotFrequency,
                getStartHz(),
                getStepHz()
        );
    }

    public static float resolveSlotFrequency(int slotIndex, boolean manual, int startHz, int stepHz) {
        if (!manual) {
            return DxpeditionFrequencyPolicy.pickFoxSlotFrequency(slotIndex);
        }
        return clampManualFrequency(
                clampManualFrequency(startHz) + Math.max(0, slotIndex) * clampStep(stepHz)
        );
    }

    public static int getStartHz() {
        return clampManualFrequency(GeneralVariables.dxpeditionFoxSlotStartHz);
    }

    public static int getStepHz() {
        int step = GeneralVariables.dxpeditionFoxSlotStepHz;
        if (step < MIN_STEP_HZ) {
            return MIN_STEP_HZ;
        }
        if (step > MAX_STEP_HZ) {
            return MAX_STEP_HZ;
        }
        return step;
    }

    public static void setManual(boolean enabled, int startHz, int stepHz) {
        GeneralVariables.dxpeditionFoxManualSlotFrequency = enabled;
        GeneralVariables.dxpeditionFoxSlotStartHz = clampManualFrequency(startHz);
        GeneralVariables.dxpeditionFoxSlotStepHz = clampStep(stepHz);
    }

    public static String getModeLabel() {
        if (!GeneralVariables.dxpeditionFoxManualSlotFrequency) {
            return "STD";
        }
        return String.format(Locale.US, "%d+%d", getStartHz(), getStepHz());
    }

    public static String buildPreview(int slots) {
        return buildPreview(
                slots,
                GeneralVariables.dxpeditionFoxManualSlotFrequency,
                getStartHz(),
                getStepHz()
        );
    }

    public static String buildPreview(int slots, boolean manual, int startHz, int stepHz) {
        int count = DxpeditionFoxSlotScheduler.clampTxSlots(slots);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("\n");
            }
            builder.append("TX")
                    .append(i + 1)
                    .append(": ")
                    .append(Math.round(resolveSlotFrequency(i, manual, startHz, stepHz)))
                    .append(" Hz");
        }
        return builder.toString();
    }

    public static int clampManualFrequency(int hz) {
        return Math.round(clamp(hz,
                DxpeditionFrequencyPolicy.TX_AUDIO_MIN_HZ,
                DxpeditionFrequencyPolicy.TX_AUDIO_MAX_HZ));
    }

    public static int clampStandardFrequency(int hz) {
        return Math.round(DxpeditionFrequencyPolicy.clampFoxTxFrequency(hz));
    }

    public static int clampStep(int hz) {
        if (hz < MIN_STEP_HZ) {
            return MIN_STEP_HZ;
        }
        if (hz > MAX_STEP_HZ) {
            return MAX_STEP_HZ;
        }
        return hz;
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
