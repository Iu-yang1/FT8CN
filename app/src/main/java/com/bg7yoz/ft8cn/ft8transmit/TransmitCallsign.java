package com.bg7yoz.ft8cn.ft8transmit;
/**
 * 呼叫过程所记录的呼号信息
 * 支持 FT8 / FT4 模式标记
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.GeneralVariables;

public class TransmitCallsign {
    private static final String TAG = "TransmitCallsign";

    public String callsign;
    public float frequency;
    public int sequential;
    public int snr;
    public int i3;
    public int n3;
    public String dxcc;
    public int cqZone;
    public int itu;

    /**
     * 当前呼叫目标对应的信号模式
     * FT8Common.FT8_MODE / FT8Common.FT4_MODE
     */
    public int signalFormat = FT8Common.FT8_MODE;

    public TransmitCallsign(int i3, int n3, String callsign, int sequential) {
        this.signalFormat = GeneralVariables.getSignalMode();
        this.callsign = callsign;
        this.sequential = sequential;
        this.i3 = i3;
        this.n3 = n3;
    }

    public TransmitCallsign(int i3, int n3, String callsign, float frequency
            , int sequential, int snr) {
        this.signalFormat = GeneralVariables.getSignalMode();
        this.callsign = callsign;
        this.frequency = frequency;
        this.sequential = sequential;
        this.snr = snr;
        this.i3 = i3;
        this.n3 = n3;
    }

    /**
     * 显式指定模式的构造函数
     */
    public TransmitCallsign(int signalFormat, int i3, int n3, String callsign, int sequential) {
        this.signalFormat = signalFormat;
        this.callsign = callsign;
        this.sequential = sequential;
        this.i3 = i3;
        this.n3 = n3;
    }

    /**
     * 显式指定模式的构造函数
     */
    public TransmitCallsign(int signalFormat, int i3, int n3, String callsign, float frequency
            , int sequential, int snr) {
        this.signalFormat = signalFormat;
        this.callsign = callsign;
        this.frequency = frequency;
        this.sequential = sequential;
        this.snr = snr;
        this.i3 = i3;
        this.n3 = n3;
    }

    /**
     * 复制构造
     */
    public TransmitCallsign(TransmitCallsign other) {
        if (other == null) {
            this.signalFormat = GeneralVariables.getSignalMode();
            return;
        }
        this.signalFormat = other.signalFormat;
        this.callsign = other.callsign;
        this.frequency = other.frequency;
        this.sequential = other.sequential;
        this.snr = other.snr;
        this.i3 = other.i3;
        this.n3 = other.n3;
        this.dxcc = other.dxcc;
        this.cqZone = other.cqZone;
        this.itu = other.itu;
    }

    /**
     * 当目标呼号为空，或CQ，说明没有目标呼号
     * @return 是否有目标呼号
     */
    public boolean haveTargetCallsign() {
        if (callsign == null) {
            return false;
        }
        return !callsign.equals("CQ");
    }

    @SuppressLint("DefaultLocale")
    public String getSnr() {
        if (snr > 0) {
            return String.format("+%d", snr);
        } else {
            return String.format("%d", snr);
        }
    }

    public boolean isFt8() {
        return signalFormat == FT8Common.FT8_MODE;
    }

    public boolean isFt4() {
        return signalFormat == FT8Common.FT4_MODE;
    }

    public String getSignalFormatString() {
        return FT8Common.modeToString(signalFormat);
    }
}