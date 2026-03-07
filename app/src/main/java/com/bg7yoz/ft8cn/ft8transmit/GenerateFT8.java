package com.bg7yoz.ft8cn.ft8transmit;
/**
 * 生成 FT8 音频信号的兼容类。
 * 说明：
 * 1. 保留旧接口 generateFt8(...)
 * 2. 新架构下统一转调 GenerateFTx
 * 3. FT4 发射请改为调用 GenerateFTx.generateFtX(...)
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
import com.bg7yoz.ft8cn.ui.ToastMessage;

public class GenerateFT8 {
    private static final String TAG = "GenerateFT8";

    private static final int FTX_LDPC_K = 91;
    public static final int FTX_LDPC_K_BYTES = (FTX_LDPC_K + 7) / 8;

    // 旧 FT8 参数，保留兼容
    private static final int FT8_NN = 79;
    private static final float FT8_SYMBOL_PERIOD = 0.160f;
    private static final float FT8_SYMBOL_BT = 2.0f;

    public static final int num_tones = FT8_NN;
    public static final float symbol_period = FT8_SYMBOL_PERIOD;
    private static final float symbol_bt = FT8_SYMBOL_BT;

    static {
        System.loadLibrary("ft8cn");
    }

    /**
     * 根据呼号判断消息类型 i3
     */
    public static int checkI3ByCallsign(String callsign) {
        if (callsign == null || callsign.length() == 0) {
            return 0;
        }

        String substring = callsign.length() >= 2
                ? callsign.substring(callsign.length() - 2)
                : callsign;

        if (substring.equals("/P")) {
            if (callsign.length() <= 8) {
                return 2;
            } else {
                return 4;
            }
        }
        if (substring.equals("/R")) {
            if (callsign.length() <= 8) {
                return 1;
            } else {
                return 4;
            }
        }
        if (callsign.contains("/")) {
            return 4;
        }
        if (callsign.length() > 6) {
            return 4;
        }
        if (callsign.length() == 0) {
            return 0;
        }
        return 1;
    }

    public static String byteToBinString(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder string = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            string.append(String.format(",%8s", Integer.toBinaryString(data[i] & 0xff)).replace(" ", "0"));
        }
        return string.toString();
    }

    public static String byteToHexString(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder string = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            string.append(String.format(",%02X", data[i]));
        }
        return string.toString();
    }

    /**
     * 检查是不是标准呼号
     */
    public static boolean checkIsStandardCallsign(String callsign) {
        if (callsign == null || callsign.length() == 0) {
            return false;
        }
        String temp;
        if (callsign.endsWith("/P") || callsign.endsWith("/R")) {
            temp = callsign.substring(0, callsign.length() - 2);
        } else {
            temp = callsign;
        }
        return temp.matches("[A-Z0-9]?[A-Z0-9][0-9][A-Z][A-Z0-9]?[A-Z]?");
    }

    /**
     * 检查是不是信号报告
     */
    private static boolean checkIsReport(String extraInfo) {
        if (extraInfo == null) {
            return false;
        }
        if (extraInfo.equals("73") || extraInfo.equals("RRR")
                || extraInfo.equals("RR73") || extraInfo.equals("")) {
            return false;
        }
        return !extraInfo.trim().matches("[A-Z][A-Z][0-9][0-9]");
    }

    /**
     * 旧接口：默认 FT8
     */
    public static float[] generateFt8(Ft8Message msg, float frequency, int sample_rate) {
        return generateFt8(msg, frequency, sample_rate, true);
    }

    /**
     * 旧接口：默认 FT8
     */
    public static float[] generateFt8(Ft8Message msg, float frequency, int sample_rate, boolean hasModifier) {
        if (msg == null) {
            return null;
        }
        msg.signalFormat = FT8Common.FT8_MODE;
        return GenerateFTx.generateFtX(msg, frequency, sample_rate, FT8Common.FT8_MODE);
    }

    /**
     * 新接口：按指定模式生成
     */
    public static float[] generateFtX(Ft8Message msg, float frequency, int sample_rate, int mode) {
        if (msg == null) {
            return null;
        }
        msg.signalFormat = mode;
        return GenerateFTx.generateFtX(msg, frequency, sample_rate, mode);
    }

    /**
     * 新接口：按消息本身模式生成
     */
    public static float[] generateFtX(Ft8Message msg, float frequency, int sample_rate) {
        if (msg == null) {
            return null;
        }
        return GenerateFTx.generateFtX(msg, frequency, sample_rate, msg.signalFormat);
    }

    /**
     * 生成 A91 数据
     * 这个函数保留，供以后 JNI 或调试使用
     */
    public static byte[] generateA91(Ft8Message msg, boolean hasModifier) {
        if (msg == null) {
            return null;
        }
        if (msg.callsignFrom == null || msg.callsignFrom.length() < 3) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
            return null;
        }

        // 首先，将文本数据打包为二进制消息,共12个字节
        byte[] packed = new byte[FTX_LDPC_K_BYTES];

        // 把 "<>" 去掉
        if (msg.callsignTo != null) {
            msg.callsignTo = msg.callsignTo.replace("<", "").replace(">", "");
        }
        if (msg.callsignFrom != null) {
            msg.callsignFrom = msg.callsignFrom.replace("<", "").replace(">", "");
        }

        if (hasModifier) {
            msg.modifier = GeneralVariables.toModifier;
        } else {
            msg.modifier = "";
        }

        // 判定用非标准呼号 i3=4 的条件
        if (msg.i3 != 0) {
            if (!checkIsStandardCallsign(msg.callsignFrom)
                    && (!checkIsReport(msg.extraInfo) || msg.checkIsCQ())) {
                msg.i3 = 4;
            } else if (msg.callsignFrom.endsWith("/P")
                    || (msg.callsignTo.endsWith("/P") && (!msg.callsignFrom.endsWith("/P")))) {
                msg.i3 = 2;
            } else {
                msg.i3 = 1;
            }
        }

        if (msg.i3 == 1 || msg.i3 == 2) {
            packed = FT8Package.generatePack77_i1(msg);
        } else if (msg.i3 == 4) {
            packed = FT8Package.generatePack77_i4(msg);
        } else {
            packFreeTextTo77(msg.getMessageText(), packed);
        }

        return packed;
    }

    /**
     * 旧接口保留，仅供旧 JNI / 旧逻辑调试
     * 新逻辑建议直接走 GenerateFTx
     */
    public static float[] generateFt8ByA91(byte[] a91, float frequency, int sample_rate) {
        if (a91 == null) {
            return null;
        }

        byte[] tones = new byte[num_tones];
        ft8_encode(a91, tones);

        int num_samples = (int) (0.5f + num_tones * symbol_period * sample_rate);
        float[] signal = new float[num_samples];

        for (int i = 0; i < num_samples; i++) {
            signal[i] = 0;
        }

        synth_gfsk(tones, num_tones, frequency, symbol_bt, symbol_period, sample_rate, signal, 0);
        return signal;
    }

    /**
     * 以下 native 为旧 FT8 兼容接口
     */
    private static native int packFreeTextTo77(String msg, byte[] c77);

    private static native int pack77(String msg, byte[] c77);

    private static native void ft8_encode(byte[] payload, byte[] tones);

    private static native void synth_gfsk(byte[] symbols, int n_sym, float f0,
                                          float symbol_bt, float symbol_period,
                                          int signal_rate, float[] signal, int offset);
}