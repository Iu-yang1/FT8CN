package com.bg7yoz.ft8cn;

/**
 * FTx 共用常量与模式辅助方法。
 */
public final class FT8Common {
    private FT8Common() {
    }

    public static final int FT8_MODE = 0;
    public static final int FT4_MODE = 1;
    public static final int Q65_MODE = 2;

    public static final int SAMPLE_RATE = 12000;

    public static final int FT8_SLOT_TIME = 15;
    public static final float FT4_SLOT_TIME = 7.5f;
    public static final int Q65_SLOT_TIME = 60;

    public static final int FT8_SLOT_TIME_MILLISECOND = 15000;
    public static final int FT4_SLOT_TIME_MILLISECOND = 7500;
    public static final int Q65_SLOT_TIME_MILLISECOND = 60000;

    public static final int FT8_5_SYMBOLS_MILLISECOND = 800;
    public static final int FT8_SLOT_TIME_M = 150;
    public static final int FT4_SLOT_TIME_M = 75;
    public static final int Q65_SLOT_TIME_M = 600;
    public static final int FT8_5_SYMBOLS_TIME_M = 8;
    public static final int FT8_TRANSMIT_DELAY = 500;
    public static final long DEEP_DECODE_TIMEOUT = 7 * 1000L;
    public static final int DECODE_MAX_ITERATIONS = 1;
    public static final int EARLY_DECODE_PHASE_TICKS = 41;
    public static final int FULL_DECODE_PHASE_TICKS = 50;
    public static final long EARLY_DECODE_TIMEOUT = 1800L;
    public static final long FT4_EARLY_DECODE_TIMEOUT = 900L;

    public static final int FT8_NN = 79;
    public static final int FT4_NN = 105;
    public static final int Q65_NN = 85;

    public static final float FT8_SYMBOL_PERIOD = 0.160f;
    public static final float FT4_SYMBOL_PERIOD = 0.048f;
    public static final float Q65_SYMBOL_PERIOD = 0.0f;

    public static final float FT8_SYMBOL_BT = 2.0f;
    public static final float FT4_SYMBOL_BT = 1.0f;
    public static final float Q65_SYMBOL_BT = 0.0f;

    public static int getSlotTimeMillisecond(int mode) {
        switch (mode) {
            case FT8_MODE:
                return FT8_SLOT_TIME_MILLISECOND;
            case FT4_MODE:
                return FT4_SLOT_TIME_MILLISECOND;
            case Q65_MODE:
                return Q65_SLOT_TIME_MILLISECOND;
            default:
                return 0;
        }
    }

    public static int getSlotTimeM(int mode) {
        switch (mode) {
            case FT8_MODE:
                return FT8_SLOT_TIME_M;
            case FT4_MODE:
                return FT4_SLOT_TIME_M;
            case Q65_MODE:
                return Q65_SLOT_TIME_M;
            default:
                return 0;
        }
    }

    public static float getSlotTimeSecond(int mode) {
        switch (mode) {
            case FT8_MODE:
                return FT8_SLOT_TIME;
            case FT4_MODE:
                return FT4_SLOT_TIME;
            case Q65_MODE:
                return Q65_SLOT_TIME;
            default:
                return 0f;
        }
    }

    public static int getToneCount(int mode) {
        switch (mode) {
            case FT8_MODE:
                return FT8_NN;
            case FT4_MODE:
                return FT4_NN;
            case Q65_MODE:
                return Q65_NN;
            default:
                return 0;
        }
    }

    public static float getSymbolPeriod(int mode) {
        switch (mode) {
            case FT8_MODE:
                return FT8_SYMBOL_PERIOD;
            case FT4_MODE:
                return FT4_SYMBOL_PERIOD;
            case Q65_MODE:
                return Q65_SYMBOL_PERIOD;
            default:
                return 0f;
        }
    }

    public static float getSymbolBt(int mode) {
        switch (mode) {
            case FT8_MODE:
                return FT8_SYMBOL_BT;
            case FT4_MODE:
                return FT4_SYMBOL_BT;
            case Q65_MODE:
                return Q65_SYMBOL_BT;
            default:
                return 0f;
        }
    }

    public static int getSamplesPerSlot(int mode) {
        return (int) (getSlotTimeSecond(mode) * SAMPLE_RATE);
    }

    public static int getEarlyDecodeDurationMs(int mode) {
        return getSlotTimeMillisecond(mode) * EARLY_DECODE_PHASE_TICKS / FULL_DECODE_PHASE_TICKS;
    }

    public static long getEarlyDecodeTimeoutMs(int mode) {
        switch (mode) {
            case FT8_MODE:
                return EARLY_DECODE_TIMEOUT;
            case FT4_MODE:
                return FT4_EARLY_DECODE_TIMEOUT;
            case Q65_MODE:
            default:
                return 0L;
        }
    }

    public static boolean supportsEarlyDecodeStage(int mode) {
        return mode == FT8_MODE || mode == FT4_MODE;
    }

    public static int getImmediateTxWindowMs(int mode) {
        switch (mode) {
            case FT8_MODE:
                return 2500;
            case FT4_MODE:
                return 1200;
            case Q65_MODE:
            default:
                return 0;
        }
    }

    public static int getFrameDurationMs(int mode) {
        return Math.round(getToneCount(mode) * getSymbolPeriod(mode) * 1000f);
    }

    public static int getLateDecodeOverrideWindowMs(int mode) {
        final int slotMs = getSlotTimeMillisecond(mode);
        final int frameMs = getFrameDurationMs(mode);
        final int guardMs;
        switch (mode) {
            case FT8_MODE:
                guardMs = 450;
                break;
            case FT4_MODE:
                guardMs = 350;
                break;
            default:
                return 0;
        }
        return Math.max(0, slotMs - frameMs - guardMs);
    }

    public static int getPreferredTxLeadInMs(int mode) {
        switch (mode) {
            case FT8_MODE:
                return 260;
            case FT4_MODE:
                return 180;
            case Q65_MODE:
            default:
                return 0;
        }
    }

    public static int getLateDecodeHoldCapMs(int mode) {
        switch (mode) {
            case FT8_MODE:
                return 980;
            case FT4_MODE:
                return 620;
            case Q65_MODE:
            default:
                return 0;
        }
    }

    public static int getLateDecodeRecentWindowMs(int mode) {
        switch (mode) {
            case FT8_MODE:
                return 1300;
            case FT4_MODE:
                return 800;
            case Q65_MODE:
            default:
                return 0;
        }
    }

    public static int getTxPipelineCompensationMs(int mode) {
        switch (mode) {
            case FT8_MODE:
                return 140;
            case FT4_MODE:
                return 80;
            case Q65_MODE:
            default:
                return 0;
        }
    }

    public static String modeToString(int mode) {
        switch (mode) {
            case FT8_MODE:
                return "FT8";
            case FT4_MODE:
                return "FT4";
            case Q65_MODE:
                return "Q65";
            default:
                return "UNKNOWN(" + mode + ")";
        }
    }

    public static boolean isFt8(int mode) {
        return mode == FT8_MODE;
    }

    public static boolean isFt4(int mode) {
        return mode == FT4_MODE;
    }

    public static boolean isQ65(int mode) {
        return mode == Q65_MODE;
    }
}
