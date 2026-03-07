package com.bg7yoz.ft8cn.ft8listener;
/**
 * 用于监听音频的类。监听通过时钟UtcTimer来控制周期，通过OnWaveDataListener接口来读取音频数据。
 * 支持 FT8 / FT4 模式切换。
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;

import java.util.ArrayList;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";

    private UtcTimer utcTimer;
    private final OnFt8Listen onFt8Listen;//当开始监听，解码结束后触发的事件

    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>();//解码的时长
    public long timeSec = 0;//解码的时长

    private OnWaveDataListener onWaveDataListener;
    private DatabaseOpr db;

    private final A91List a91List = new A91List();//a91列表

    static {
        System.loadLibrary("ft8cn");
    }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        this.onFt8Listen = onFt8Listen;
        this.db = db;
        buildUtcTimer();
    }

    /**
     * 按当前模式创建时钟
     */
    private void buildUtcTimer() {
        utcTimer = new UtcTimer(FT8Common.getSlotTimeM(GeneralVariables.getSignalMode()), false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {//不触发时的时钟信息
            }

            @Override
            public void doOnSecTimer(long utc) {//当指定间隔时触发时
                Log.d(TAG, String.format("触发录音,%d,mode=%s", utc, FT8Common.modeToString(GeneralVariables.getSignalMode())));
                runRecorde(utc);
            }
        });
    }

    /**
     * 模式切换后重建监听周期
     */
    public void restartByCurrentMode() {
        boolean running = isListening();
        stopListen();
        buildUtcTimer();
        if (running) {
            startListen();
        }
    }

    public void startListen() {
        if (utcTimer != null) {
            utcTimer.start();
        }
    }

    public void stopListen() {
        if (utcTimer != null) {
            utcTimer.stop();
        }
    }

    public boolean isListening() {
        return utcTimer != null && utcTimer.isRunning();
    }

    /**
     * 获取当前时间的偏移量，这里包括总的时钟偏移，也包括本实例的偏移
     *
     * @return int
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * 录音。在后台以多线程的方式录音。
     *
     * @param utc 当前解码的UTC时间
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "开始录音...");

        if (onWaveDataListener != null) {
            onWaveDataListener.getVoiceData(
                    FT8Common.getSlotTimeMillisecond(GeneralVariables.getSignalMode()),
                    true,
                    new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            Log.d(TAG, String.format("开始解码...###,数据长度：%d,mode=%s",
                                    data.length, FT8Common.modeToString(GeneralVariables.getSignalMode())));
                            decodeFt8(utc, data);
                        }
                    });
        }
    }

    /**
     * 启动一次解码流程
     */
    public void decodeFt8(long utc, float[] voiceData) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();

                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }

                boolean isFt8 = GeneralVariables.isFt8Mode();

                // 初始化解码器，按当前模式选择 FT8 / FT4
                long ft8Decoder = InitDecoder(
                        utc,
                        FT8Common.SAMPLE_RATE,
                        voiceData.length,
                        isFt8
                );

                // 压入音频数据
                DecoderMonitorPressFloat(voiceData, ft8Decoder);

                ArrayList<Ft8Message> allMsg = new ArrayList<>();
                ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false);
                addMsgToList(allMsg, msgs);

                timeSec = System.currentTimeMillis() - time;
                decodeTimeSec.postValue(timeSec);

                if (onFt8Listen != null) {
                    onFt8Listen.afterDecode(
                            utc,
                            averageOffset(allMsg),
                            UtcTimer.sequential(utc, GeneralVariables.getCurrentSlotTimeM()),
                            msgs,
                            false
                    );
                }

                // FT4 初期不进入 subtractSignal 深度减码
                if (GeneralVariables.deepDecodeMode && GeneralVariables.isFt8Mode()) {
                    msgs = runDecode(ft8Decoder, utc, true);
                    addMsgToList(allMsg, msgs);

                    timeSec = System.currentTimeMillis() - time;
                    decodeTimeSec.postValue(timeSec);

                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(
                                utc,
                                averageOffset(allMsg),
                                UtcTimer.sequential(utc, GeneralVariables.getCurrentSlotTimeM()),
                                msgs,
                                true
                        );
                    }

                    do {
                        if (timeSec > FT8Common.DEEP_DECODE_TIMEOUT) {
                            break;
                        }

                        // FT8 深度减码
                        ReBuildSignal.subtractSignal(ft8Decoder, a91List);

                        msgs = runDecode(ft8Decoder, utc, true);
                        addMsgToList(allMsg, msgs);

                        timeSec = System.currentTimeMillis() - time;
                        decodeTimeSec.postValue(timeSec);

                        if (onFt8Listen != null) {
                            onFt8Listen.afterDecode(
                                    utc,
                                    averageOffset(allMsg),
                                    UtcTimer.sequential(utc, GeneralVariables.getCurrentSlotTimeM()),
                                    msgs,
                                    true
                            );
                        }

                    } while (msgs.size() > 0);
                }

                DeleteDecoder(ft8Decoder);

                Log.d(TAG, String.format("解码耗时:%d毫秒,mode=%s",
                        System.currentTimeMillis() - time,
                        FT8Common.modeToString(GeneralVariables.getSignalMode())));
            }
        }).start();
    }

    /**
     * 执行单轮同步与解码
     */
    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(GeneralVariables.getSignalMode());

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        ft8Message.signalFormat = GeneralVariables.getSignalMode();

        a91List.clear();

        // 设置解码模式
        setDecodeMode(ft8Decoder, isDeep);

        int num_candidates = DecoderFt8FindSync(ft8Decoder);

        for (int idx = 0; idx < num_candidates; ++idx) {
            try {
                ft8Message.signalFormat = GeneralVariables.getSignalMode();

                if (DecoderFt8Analysis(idx, ft8Decoder, ft8Message)) {
                    if (ft8Message.isValid) {
                        Ft8Message msg = new Ft8Message(ft8Message);
                        msg.signalFormat = GeneralVariables.getSignalMode();

                        byte[] a91 = DecoderGetA91(ft8Decoder);
                        a91List.add(a91, ft8Message.freq_hz, ft8Message.time_sec);

                        if (checkMessageSame(ft8Messages, msg)) {
                            continue;
                        }

                        msg.isWeakSignal = isDeep;//是不是弱信号
                        ft8Messages.add(msg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "runDecode error: " + e.getMessage());
            }
        }

        return ft8Messages;
    }

    /**
     * 计算平均时间偏移值
     *
     * @param messages 消息列表
     * @return 偏移值
     */
    private float averageOffset(ArrayList<Ft8Message> messages) {
        if (messages.size() == 0) return 0f;
        float dt = 0;
        for (Ft8Message msg : messages) {
            dt += msg.time_sec;
        }
        return dt / messages.size();
    }

    /**
     * 把消息添加到列表中
     *
     * @param allMsg 消息列表
     * @param newMsg 新的消息
     */
    private void addMsgToList(ArrayList<Ft8Message> allMsg, ArrayList<Ft8Message> newMsg) {
        for (int i = newMsg.size() - 1; i >= 0; i--) {
            if (checkMessageSame(allMsg, newMsg.get(i))) {
                newMsg.remove(i);
            } else {
                allMsg.add(newMsg.get(i));
            }
        }
    }

    /**
     * 检查消息列表里同样的内容是否存在
     * FT8 / FT4 视为不同模式，不互相去重
     *
     * @param ft8Messages 消息列表
     * @param ft8Message  消息
     * @return boolean
     */
    private boolean checkMessageSame(ArrayList<Ft8Message> ft8Messages, Ft8Message ft8Message) {
        for (Ft8Message msg : ft8Messages) {
            if (msg.signalFormat != ft8Message.signalFormat) {
                continue;
            }
            if (msg.getMessageText().equals(ft8Message.getMessageText())) {
                if (msg.snr < ft8Message.snr) {
                    msg.snr = ft8Message.snr;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
    }

    public OnWaveDataListener getOnWaveDataListener() {
        return onWaveDataListener;
    }

    public void setOnWaveDataListener(OnWaveDataListener onWaveDataListener) {
        this.onWaveDataListener = onWaveDataListener;
    }

    /**
     * 解码的第一步，初始化解码器，获取解码器的地址。
     *
     * @param utcTime     UTC时间
     * @param sampleRat   采样率，12000
     * @param num_samples 缓冲区数据的长度
     * @param isFt8       是否是FT8信号；false 时为 FT4
     * @return 返回解码器的地址
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * 解码的第二步，读取Wav数据。
     *
     * @param buffer  Wav数据缓冲区
     * @param decoder 解码器数据的地址
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);

    /**
     * 解码的第三步，同步数据。
     *
     * @param decoder 解码器地址
     * @return 中标信号的数量
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * 解码的第四步，分析出消息。（需要在一个循环里）
     *
     * @param idx        中标信号的序号
     * @param decoder    解码器的地址
     * @param ft8Message 解出来的消息
     * @return boolean
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * 解码的最后一步，删除解码器数据
     *
     * @param decoder 解码器数据的地址
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder);//获取当前message的a91数据

    public native void setDecodeMode(long decoder, boolean isDeep);//设置解码的模式，isDeep=true是多次迭代，=false是快速迭代
}