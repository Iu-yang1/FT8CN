package com.bg7yoz.ft8cn.rigs;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.connector.BaseRigConnector;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFTx;

/**
 * 电台的抽象类。
 *
 * 关键修改：
 * 1. 增加 buildTxWave(...)，统一生成 FT8 / FT4 发射波形
 * 2. 增加 getTxMode(...)，统一决定当前发射模式
 * 3. 增加 getDefaultSampleRate()，给子类覆盖
 *
 * 后续所有支持音频发送的 Rig，只需要：
 * float[] data = buildTxWave(message, sampleRate);
 * 然后把 data 发给设备即可
 *
 * @author BGY70Z
 * @date 2023-03-20
 */
public abstract class BaseRig {
    private long freq;//当前频率值
    public MutableLiveData<Long> mutableFrequency = new MutableLiveData<>();
    private int controlMode;//控制模式
    private OnRigStateChanged onRigStateChanged;//当电台的一些状态发生变化的回调
    private int civAddress;//CIV地址
    private int baudRate;//波特率
    private boolean isPttOn = false;//ptt是否打开
    private BaseRigConnector connector = null;//连接电台的对象

    public abstract boolean isConnected();//确认电台是否连接

    public abstract void setUsbModeToRig();//设置电台上边带方式

    public abstract void setFreqToRig();//设置电台频率

    public abstract void onReceiveData(byte[] data);//当电台发送回数据的动作

    public abstract void readFreqFromRig();//从电台读取频率

    public abstract String getName();//获取电台的名字

    private final OnConnectReceiveData onConnectReceiveData = new OnConnectReceiveData() {
        @Override
        public void onData(byte[] data) {
            onReceiveData(data);
        }
    };

    public void setPTT(boolean on) {//设置PTT打开或关闭
        isPttOn = on;
        if (onRigStateChanged != null) {
            onRigStateChanged.onPttChanged(on);
        }
    }

    /**
     * 默认发送接口，留给支持音频发送的设备覆写。
     */
    public void sendWaveData(Ft8Message message) {
        // 留给各子类实现
    }

    public long getFreq() {
        return freq;
    }

    public void setFreq(long freq) {
        if (freq == this.freq) return;
        if (freq == 0) return;
        if (freq == -1) return;
        mutableFrequency.postValue(freq);
        this.freq = freq;
        if (onRigStateChanged != null) {
            onRigStateChanged.onFreqChanged(freq);
        }
    }

    public void setConnector(BaseRigConnector connector) {
        this.connector = connector;
        this.connector.setOnRigStateChanged(onRigStateChanged);
        this.connector.setOnConnectReceiveData(new OnConnectReceiveData() {
            @Override
            public void onData(byte[] data) {
                onReceiveData(data);
            }
        });
    }

    public void setControlMode(int mode) {
        controlMode = mode;
        if (connector != null) {
            connector.setControlMode(mode);
        }
    }

    public int getControlMode() {
        return controlMode;
    }

    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }

    public BaseRigConnector getConnector() {
        return connector;
    }

    public OnRigStateChanged getOnRigStateChanged() {
        return onRigStateChanged;
    }

    public void setOnRigStateChanged(OnRigStateChanged onRigStateChanged) {
        this.onRigStateChanged = onRigStateChanged;
    }

    public int getCivAddress() {
        return civAddress;
    }

    public void setCivAddress(int civAddress) {
        this.civAddress = civAddress;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    public boolean isPttOn() {
        return isPttOn;
    }

    /**
     * 当前消息的发射模式。
     * 优先使用 message 自身 signalFormat，防止点选 FT4 消息回复时被当前全局模式覆盖。
     */
    protected int getTxMode(Ft8Message message) {
        if (message != null) {
            return message.signalFormat;
        }
        return GeneralVariables.getSignalMode();
    }

    /**
     * 默认采样率，子类可覆写。
     * 常见设备用 12000，网络/音频类也可能是 24000。
     */
    protected int getDefaultSampleRate() {
        return 12000;
    }

    /**
     * 统一生成发射波形。
     * 后续所有支持音频发送的 rig 都走这里，避免各类重复写 FT8/FT4 判断。
     *
     * @param message    发射消息
     * @param sampleRate 采样率
     * @return 波形数组，失败返回 null
     */
    protected float[] buildTxWave(Ft8Message message, int sampleRate) {
        if (message == null) {
            return null;
        }

        int txMode = getTxMode(message);

        return GenerateFTx.generateFtX(
                message,
                GeneralVariables.getBaseFrequency(),
                sampleRate,
                txMode
        );
    }

    /**
     * 使用默认采样率生成发射波形。
     */
    protected float[] buildTxWave(Ft8Message message) {
        return buildTxWave(message, getDefaultSampleRate());
    }

    /**
     * 2023-08-16 由DS1UFX提交修改（基于0.9版），增加(tr)uSDX audio over cat的支持。
     */
    public boolean supportWaveOverCAT() {
        return false;
    }

    public void onDisconnecting() {
    }
}