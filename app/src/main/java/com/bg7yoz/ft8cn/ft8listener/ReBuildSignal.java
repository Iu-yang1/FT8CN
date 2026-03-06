package com.bg7yoz.ft8cn.ft8listener;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.GeneralVariables;

/**
 * 重建并减去已解码信号。
 * 说明：
 * 1. 当前增加 mode 参数，供 JNI 层区分 FT8 / FT4
 * 2. 旧接口 subtractSignal(decoder,a91List) 保留
 */
public class ReBuildSignal {
    private static final String TAG = "ReBuildSignal";

    static {
        System.loadLibrary("ft8cn");
    }

    /**
     * 兼容旧调用：按当前全局模式减信号
     */
    public static void subtractSignal(long decoder, A91List a91List) {
        subtractSignal(decoder, a91List, GeneralVariables.getSignalMode());
    }

    /**
     * 按指定模式减去已解码信号
     *
     * @param decoder 解码器
     * @param a91List A91 列表
     * @param mode    FT8Common.FT8_MODE / FT8Common.FT4_MODE
     */
    public static void subtractSignal(long decoder, A91List a91List, int mode) {
        if (a91List == null || a91List.list == null || a91List.list.size() == 0) {
            return;
        }

        for (A91List.A91 a91 : a91List.list) {
            doSubtractSignal(
                    decoder,
                    a91.a91,
                    FT8Common.SAMPLE_RATE,
                    a91.freq_hz,
                    a91.time_sec,
                    mode
            );
        }
    }

    /**
     * 当前模式是否支持 subtract
     * FT4 在 JNI 适配完成后即可返回 true
     */
    public static boolean supportSubtract(int mode) {
        return mode == FT8Common.FT8_MODE || mode == FT8Common.FT4_MODE;
    }

    /**
     * JNI：减去一个已重建信号
     *
     * @param decoder     解码器
     * @param payload     A91 数据
     * @param sample_rate 采样率
     * @param frequency   音频频率
     * @param time_sec    时间偏移
     * @param mode        FT8 / FT4 模式
     */
    private static native void doSubtractSignal(long decoder,
                                                byte[] payload,
                                                int sample_rate,
                                                float frequency,
                                                float time_sec,
                                                int mode);
}