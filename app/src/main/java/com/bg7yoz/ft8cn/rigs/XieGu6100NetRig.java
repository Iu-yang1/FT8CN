package com.bg7yoz.ft8cn.rigs;

import android.util.Log;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.connector.X6100Connector;
import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFTx;

/**
 * XieGu6100的ft8cns模式，只支持网络模式，所以在设置baseRig时要做好判断
 */
public class XieGu6100NetRig extends BaseRig {
    private static final String TAG = "x6100RigNet";

    //private final int ctrAddress = 0xE0;//接收地址，默认0xE0;电台回复命令有时也可以是0x00




    @Override
    public void setPTT(boolean on) {
        super.setPTT(on);
        if (getConnector() != null) {
                getConnector().setPttOn(on);
        }
    }

    @Override
    public boolean isConnected() {
        if (getConnector() == null) {
            return false;
        }
        return getConnector().isConnected();
    }

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            X6100Connector x6100Connector =(X6100Connector)getConnector();
            x6100Connector.getXieguRadio().commandSetMode("u-dig",1);
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            X6100Connector x6100Connector =(X6100Connector)getConnector();
            x6100Connector.getXieguRadio().commandTuneFreq(getFreq());
        }
    }


    @Override
    public void onReceiveData(byte[] data) {
        //命令解析都在X6100Radio中完成了，此处不需要动作了
    }

    @Override
    public void sendWaveData(Ft8Message message) {
        if (getConnector() != null) {
            int txMode = message != null ? message.signalFormat : GeneralVariables.getSignalMode();

            // X6100 FT8CNS 设备专有 A91 通道目前只保留给 FT8。
            // FT4 一律走普通 PCM 音频路径，避免设备端仍按 FT8 调制。
            if (GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS
                    && txMode == FT8Common.FT8_MODE) {
                getConnector().sendFt8A91(
                        GenerateFT8.generateA91(message, true),
                        GeneralVariables.getBaseFrequency()
                );
            } else {
                float[] data = GenerateFTx.generateFtX(
                        message,
                        GeneralVariables.getBaseFrequency(),
                        12000,
                        txMode
                );

                if (data == null) {
                    setPTT(false);
                    return;
                }

                getConnector().sendWaveData(data);
            }
        }
    }

    @Override
    public void readFreqFromRig() {//通过X6100Radio的状态来获取频率，此处获取频率指令不需要了

    }

    @Override
    public String getName() {
        return "XIEGU X6100 series";
    }

    public XieGu6100NetRig(int civAddress) {
        Log.d(TAG, "x6100RigNet: Create.");
        setCivAddress(civAddress);
    }
}
