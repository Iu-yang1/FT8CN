package com.bg7yoz.ft8cn;

/**
 * FT8 / FT4 共用常量与模式辅助方法。
 *
 * @author BGY70Z
 * @date 2023-03-20
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

    public static final int FT8_SLOT_TIME_MILLISECOND = 15000;   // 一个完整 FT8 时隙的毫秒数
    public static final int FT4_SLOT_TIME_MILLISECOND = 7500;    // 一个完整 FT4 时隙的毫秒数

    public static final int FT8_5_SYMBOLS_MILLISECOND = 800;     // FT8 前 5 个符号对应的毫秒数
    public static final int FT8_SLOT_TIME_M = 150;               // 15 秒，UtcTimer 的 0.1 秒单位
    public static final int FT4_SLOT_TIME_M = 75;                // 7.5 秒，UtcTimer 的 0.1 秒单位
    public static final int FT8_5_SYMBOLS_TIME_M = 8;            // FT8 前 5 个符号对应的 0.1 秒单位
    public static final int FT8_TRANSMIT_DELAY = 500;            // 默认发射延迟，单位毫秒
    public static final long DEEP_DECODE_TIMEOUT = 7 * 1000L;    // 深度解码最长预算
    public static final int DECODE_MAX_ITERATIONS = 1;           // Java 层额外重试次数
    public static final int EARLY_DECODE_PHASE_TICKS = 41;
    public static final int FULL_DECODE_PHASE_TICKS = 50;
    public static final long EARLY_DECODE_TIMEOUT = 1800L;
    public static final long FT4_EARLY_DECODE_TIMEOUT = 900L;

    // ===== 发射 / 解码模式参数 =====
    public static final int FT8_NN = 79;
    public static final int FT4_NN = 105;

    public static final float FT8_SYMBOL_PERIOD = 0.160f;
    public static final float FT4_SYMBOL_PERIOD = 0.048f;

    public static final float FT8_SYMBOL_BT = 2.0f;
    public static final float FT4_SYMBOL_BT = 1.0f;

    /**
     * 获取当前模式一个完整时隙的毫秒数。
     */
    public static int getSlotTimeMillisecond(int mode) {
        return mode == FT4_MODE ? FT4_SLOT_TIME_MILLISECOND : FT8_SLOT_TIME_MILLISECOND;
    }

    /**
     * 获取当前模式在 UtcTimer 中对应的 0.1 秒单位长度。
     */
    public static int getSlotTimeM(int mode) {
        return mode == FT4_MODE ? FT4_SLOT_TIME_M : FT8_SLOT_TIME_M;
    }

    /**
     * 获取当前模式一个完整时隙的秒数。
     */
    public static float getSlotTimeSecond(int mode) {
        return mode == FT4_MODE ? FT4_SLOT_TIME : FT8_SLOT_TIME;
    }

    /**
     * 获取当前模式的 tone 数量。
     */
    public static int getToneCount(int mode) {
        return mode == FT4_MODE ? FT4_NN : FT8_NN;
    }

    /**
     * 获取当前模式的符号周期。
     */
    public static float getSymbolPeriod(int mode) {
        return mode == FT4_MODE ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    }

    /**
     * 获取当前模式的 GFSK BT 参数。
     */
    public static float getSymbolBt(int mode) {
        return mode == FT4_MODE ? FT4_SYMBOL_BT : FT8_SYMBOL_BT;
    }

    /**
     * 获取当前模式完整时隙对应的采样点数。
     */
    public static int getSamplesPerSlot(int mode) {
        return (int) (getSlotTimeSecond(mode) * SAMPLE_RATE);
    }

    public static int getEarlyDecodeDurationMs(int mode) {
        return getSlotTimeMillisecond(mode) * EARLY_DECODE_PHASE_TICKS / FULL_DECODE_PHASE_TICKS;
    }

    public static long getEarlyDecodeTimeoutMs(int mode) {
        return mode == FT4_MODE ? FT4_EARLY_DECODE_TIMEOUT : EARLY_DECODE_TIMEOUT;
    }

    public static boolean supportsEarlyDecodeStage(int mode) {
        return mode == FT8_MODE || mode == FT4_MODE;
    }

    /**
     * 获取“立即发射”窗口长度。
     * FT4 周期更短，所以窗口也更短。
     */
    public static int getImmediateTxWindowMs(int mode) {
        return mode == FT4_MODE ? 1200 : 2500;
    }

    public static int getFrameDurationMs(int mode) {
        return Math.round(getToneCount(mode) * getSymbolPeriod(mode) * 1000f);
    }

    public static int getLateDecodeOverrideWindowMs(int mode) {
        int slotMs = getSlotTimeMillisecond(mode);
        int frameMs = getFrameDurationMs(mode);
        int guardMs = mode == FT4_MODE ? 350 : 450;
        return Math.max(0, slotMs - frameMs - guardMs);
    }

    /**
     * 推荐的最早发射构建偏移，相对时隙起点。
     */
    public static int getPreferredTxLeadInMs(int mode) {
        return mode == FT4_MODE ? 180 : 260;
    }

    /**
     * 晚到解码覆盖导致的自动发射延迟硬上限。
     */
    public static int getLateDecodeHoldCapMs(int mode) {
        return mode == FT4_MODE ? 620 : 980;
    }

    /**
     * 只有解码结果足够新时，才允许晚到解码覆盖窗口生效。
     */
    public static int getLateDecodeRecentWindowMs(int mode) {
        return mode == FT4_MODE ? 800 : 1300;
    }

    /**
     * 本地编码、排队与音频链路补偿时间，避免起发滞后。
     */
    public static int getTxPipelineCompensationMs(int mode) {
        return mode == FT4_MODE ? 80 : 140;
    }

    /**
     * 模式转字符串。
     */
    public static String modeToString(int mode) {
        if (mode == FT4_MODE) {
            return "FT4";
        }
        if (mode == Q65_MODE) {
            return "Q65";
        }
        return "FT8";
    }

    /**
     * 是否 FT8。
     */
    public static boolean isFt8(int mode) {
        return mode == FT8_MODE;
    }

    /**
     * 是否 FT4。
     */
    public static boolean isFt4(int mode) {
        return mode == FT4_MODE;
    }
}
