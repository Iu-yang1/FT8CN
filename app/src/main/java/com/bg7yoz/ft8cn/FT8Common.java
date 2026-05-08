package com.bg7yoz.ft8cn;

/**
 * FT8 / FT4 有关的常量。
 * @author BGY70Z
 * @date 2023-03-20
 */
public final class FT8Common {
    private FT8Common() {
    }

    public static final int FT8_MODE = 0;
    public static final int FT4_MODE = 1;

    public static final int SAMPLE_RATE = 12000;

    public static final int FT8_SLOT_TIME = 15;
    public static final float FT4_SLOT_TIME = 7.5f;

    public static final int FT8_SLOT_TIME_MILLISECOND = 15000;   // 一个周期的毫秒数
    public static final int FT4_SLOT_TIME_MILLISECOND = 7500;

    public static final int FT8_5_SYMBOLS_MILLISECOND = 800;     // 5个符号所需的毫秒数

    public static final int FT8_SLOT_TIME_M = 150;               // 15秒
    public static final int FT4_SLOT_TIME_M = 75;                // 7.5秒
    public static final int FT8_5_SYMBOLS_TIME_M = 8;            // 5个符号的时间长度 0.8 秒

    public static final int FT8_TRANSMIT_DELAY = 500;            // 默认发射延迟时长，毫秒
    public static final long DEEP_DECODE_TIMEOUT = 7 * 1000L;    // 深度解码的最长时间范围
    public static final int DECODE_MAX_ITERATIONS = 1;           // 迭代次数
    public static final int EARLY_DECODE_PHASE_TICKS = 41;
    public static final int FULL_DECODE_PHASE_TICKS = 50;
    public static final long EARLY_DECODE_TIMEOUT = 1800L;
    public static final long FT4_EARLY_DECODE_TIMEOUT = 900L;

    // ===== 发射/解码模式参数 =====
    public static final int FT8_NN = 79;
    public static final int FT4_NN = 105;

    public static final float FT8_SYMBOL_PERIOD = 0.160f;
    public static final float FT4_SYMBOL_PERIOD = 0.048f;

    public static final float FT8_SYMBOL_BT = 2.0f;
    public static final float FT4_SYMBOL_BT = 1.0f;

    /**
     * 获取当前模式一个周期的毫秒数
     */
    public static int getSlotTimeMillisecond(int mode) {
        return mode == FT4_MODE ? FT4_SLOT_TIME_MILLISECOND : FT8_SLOT_TIME_MILLISECOND;
    }

    /**
     * 获取当前模式一个周期的 UtcTimer 单位长度
     */
    public static int getSlotTimeM(int mode) {
        return mode == FT4_MODE ? FT4_SLOT_TIME_M : FT8_SLOT_TIME_M;
    }

    /**
     * 获取当前模式一个周期的秒数
     */
    public static float getSlotTimeSecond(int mode) {
        return mode == FT4_MODE ? FT4_SLOT_TIME : FT8_SLOT_TIME;
    }

    /**
     * 获取当前模式 tone 个数
     */
    public static int getToneCount(int mode) {
        return mode == FT4_MODE ? FT4_NN : FT8_NN;
    }

    /**
     * 获取当前模式符号周期
     */
    public static float getSymbolPeriod(int mode) {
        return mode == FT4_MODE ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    }

    /**
     * 获取当前模式 GFSK BT 参数
     */
    public static float getSymbolBt(int mode) {
        return mode == FT4_MODE ? FT4_SYMBOL_BT : FT8_SYMBOL_BT;
    }

    /**
     * 获取当前模式整周期采样点数
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
     * 获取“立即发射”窗口长度
     * FT4 周期更短，因此窗口也应更小
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
     * Preferred earliest transmit build offset from slot start.
     * 推荐的最早发射构建偏移（相对时隙起点）。
     */
    public static int getPreferredTxLeadInMs(int mode) {
        return mode == FT4_MODE ? 180 : 260;
    }

    /**
     * Hard cap for delayed auto-TX start caused by late-decode override.
     * 晚到解码覆盖引起的自动发射延迟硬上限。
     */
    public static int getLateDecodeHoldCapMs(int mode) {
        return mode == FT4_MODE ? 620 : 980;
    }

    /**
     * Only when decode updates are very recent do we allow late-decode override.
     * 仅当解码更新“足够新”时才允许晚到解码覆盖窗口生效。
     */
    public static int getLateDecodeRecentWindowMs(int mode) {
        return mode == FT4_MODE ? 800 : 1300;
    }

    /**
     * Compensation for local encode/queue/audio path latency.
     * 本地编码/排队/音频链路补偿时间，避免对端看到滞后起发。
     */
    public static int getTxPipelineCompensationMs(int mode) {
        return mode == FT4_MODE ? 80 : 140;
    }

    /**
     * 模式转字符串
     */
    public static String modeToString(int mode) {
        return mode == FT4_MODE ? "FT4" : "FT8";
    }

    /**
     * 是否 FT8
     */
    public static boolean isFt8(int mode) {
        return mode == FT8_MODE;
    }

    /**
     * 是否 FT4
     */
    public static boolean isFt4(int mode) {
        return mode == FT4_MODE;
    }
}

