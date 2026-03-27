package com.bg7yoz.ft8cn.ft8listener;
/**
 * 用于监听音频的类。监听通过时钟UtcTimer来控制周期，通过OnWaveDataListener接口来读取音频数据。
 * 支持 FT8 / FT4 模式切换。
 *
 * 1. 每一轮解码先固定 decodeMode，避免解码过程中用户切模式造成混乱
 * 2. FT4 允许进入深度解码
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
    private static final int AP_HINT_CALL_LIMIT = 4;
    // AP-lite only keeps a few recent follow calls so the native fallback stays cheap.

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
            public void doHeartBeatTimer(long utc) {
            }

            @Override
            public void doOnSecTimer(long utc) {
                Log.d(TAG, String.format("触发录音,%d,mode=%s",
                        utc,
                        FT8Common.modeToString(GeneralVariables.getSignalMode())));
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

    public void release() {
        stopListen();
    }

    public boolean isListening() {
        return utcTimer != null && utcTimer.isRunning();
    }

    /**
     * 获取当前时间的偏移量，这里包括总的时钟偏移，也包括本实例的偏移
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * 录音。在后台以多线程的方式录音。
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "开始录音...");

        if (onWaveDataListener != null) {
            final int recordMode = GeneralVariables.getSignalMode();
            final int duration = FT8Common.getSlotTimeMillisecond(recordMode);

            onWaveDataListener.getVoiceData(
                    duration,
                    true,
                    new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            Log.d(TAG, String.format("开始解码...###,数据长度：%d,mode=%s",
                                    data.length,
                                    FT8Common.modeToString(recordMode)));
                            decodeFt8(utc, data, recordMode);
                        }
                    });
        }
    }

    /**
     * 兼容旧调用：如果外部还有旧入口，仍按当前模式跑
     */
    public void decodeFt8(long utc, float[] voiceData) {
        decodeFt8(utc, voiceData, GeneralVariables.getSignalMode());
    }

    /**
     * FT4 更保守，FT8 可略宽松
     */
    private int getMaxSubtractRounds(int decodeMode) {
        if (decodeMode == FT8Common.FT4_MODE) {
            return 1;
        }
        return 2;
    }

    private String[][] buildDecoderApHints() {
        ArrayList<String> hintCalls = new ArrayList<>();
        ArrayList<String> hintGrids = new ArrayList<>();
        String myCall = GeneralVariables.getShortCallsign(GeneralVariables.myCallsign)
                .toUpperCase()
                .trim();

        synchronized (GeneralVariables.followCallsign) {
            for (int i = GeneralVariables.followCallsign.size() - 1;
                 i >= 0 && hintCalls.size() < AP_HINT_CALL_LIMIT;
                 --i) {
                String rawCall = GeneralVariables.followCallsign.get(i);
                String shortCall = GeneralVariables.getShortCallsign(rawCall)
                        .toUpperCase()
                        .trim();

                if (shortCall.length() == 0
                        || shortCall.equals(myCall)
                        || hintCalls.contains(shortCall)) {
                    continue;
                }

                String grid = GeneralVariables.callsignAndGrids.get(rawCall);
                if (grid == null || grid.length() == 0) {
                    grid = GeneralVariables.callsignAndGrids.get(shortCall);
                }
                if (grid == null) {
                    grid = "";
                } else {
                    grid = grid.toUpperCase().trim();
                    if (grid.length() > 4) {
                        grid = grid.substring(0, 4);
                    }
                }

                hintCalls.add(shortCall);
                hintGrids.add(grid);
            }
        }

        return new String[][]{
                hintCalls.toArray(new String[0]),
                hintGrids.toArray(new String[0])
        };
        // Java fixes both the size and the order of the hint set before passing it to native.
    }

    /**
     * 判断消息是否允许进入 subtract 列表
     * 普通解码可略宽松，深解后更严格，避免误码扩散
     */
    private boolean shouldAddToSubtractList(Ft8Message msg, boolean isDeep, int decodeMode) {
        if (msg == null || !msg.isValid) {
            return false;
        }

        if (!isDeep) {
            if (decodeMode == FT8Common.FT4_MODE) {
                return msg.snr >= -17 && msg.score >= 13;
            } else {
                return msg.snr >= -24 && msg.score >= 12;
            }
        }

        if (decodeMode == FT8Common.FT4_MODE) {
            return msg.snr >= -18 && msg.score >= 15;
        } else {
            return msg.snr >= -25 && msg.score >= 14;
        }
    }

    /**
     * 当前轮结果中是否存在足够高质量的消息可继续 subtract
     */
    private boolean hasQualifiedSubtractMsg(ArrayList<Ft8Message> msgs, int decodeMode) {
        if (msgs == null || msgs.size() == 0) {
            return false;
        }

        for (Ft8Message msg : msgs) {
            if (shouldAddToSubtractList(msg, true, decodeMode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启动一次解码流程
     *
     * @param utc        当前UTC
     * @param voiceData  当前周期的音频数据
     * @param decodeMode 本轮固定模式，避免线程运行过程中模式切换导致同一轮解码混用 FT8 / FT4
     */
    public void decodeFt8(long utc, float[] voiceData, int decodeMode) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();
                final int slotTimeM = FT8Common.getSlotTimeM(decodeMode);

                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }

                boolean isFt8 = (decodeMode == FT8Common.FT8_MODE);

                // 初始化解码器，按本轮固定模式选择 FT8 / FT4
                long ft8Decoder = InitDecoder(
                        utc,
                        FT8Common.SAMPLE_RATE,
                        voiceData.length,
                        isFt8
                );

                // 压入音频数据
                String[][] apHints = buildDecoderApHints();
                DecoderSetApHints(
                        ft8Decoder,
                        GeneralVariables.getShortCallsign(GeneralVariables.myCallsign).toUpperCase().trim(),
                        apHints[0],
                        apHints[1]
                );
                // AP-lite only receives my-call plus a few follow-call/grid hints before decode starts.
                DecoderMonitorPressFloat(voiceData, ft8Decoder);

                ArrayList<Ft8Message> allMsg = new ArrayList<>();
                ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false, decodeMode, 0L);
                addMsgToList(allMsg, msgs);

                timeSec = System.currentTimeMillis() - time;

                if (onFt8Listen != null) {
                    onFt8Listen.afterDecode(
                            utc,
                            averageOffset(allMsg),
                            UtcTimer.sequential(utc, slotTimeM),
                            msgs,
                            false
                    );
                }

                // FT8 / FT4 都允许进入深度解码，但使用有限轮重解
                if (GeneralVariables.deepDecodeMode && ReBuildSignal.supportSubtract(decodeMode)) {
                    long deepDecodeDeadlineMs = System.currentTimeMillis() + FT8Common.DEEP_DECODE_TIMEOUT;
                    // The deep-decode timeout is enforced as a real deadline instead of only checking between rounds.

                    msgs = runDecode(ft8Decoder, utc, true, decodeMode, deepDecodeDeadlineMs);
                    addMsgToList(allMsg, msgs);

                    timeSec = System.currentTimeMillis() - time;

                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(
                                utc,
                                averageOffset(allMsg),
                                UtcTimer.sequential(utc, slotTimeM),
                                msgs,
                                true
                        );
                    }

                    int maxRounds = getMaxSubtractRounds(decodeMode);
                    int round = 0;

                    while (round < maxRounds) {
                        if (System.currentTimeMillis() >= deepDecodeDeadlineMs) {
                            break;
                        }

                        if (!hasQualifiedSubtractMsg(msgs, decodeMode)) {
                            break;
                        }

                        // 按本轮固定模式做 subtract，避免中途切模式
                        ReBuildSignal.subtractSignal(ft8Decoder, a91List, decodeMode);

                        msgs = runDecode(ft8Decoder, utc, true, decodeMode, deepDecodeDeadlineMs);
                        if (msgs.size() == 0) {
                            break;
                        }

                        addMsgToList(allMsg, msgs);

                        timeSec = System.currentTimeMillis() - time;

                        if (onFt8Listen != null) {
                            onFt8Listen.afterDecode(
                                    utc,
                                    averageOffset(allMsg),
                                    UtcTimer.sequential(utc, slotTimeM),
                                    msgs,
                                    true
                            );
                        }

                        round++;
                    }
                }

                DeleteDecoder(ft8Decoder);
                timeSec = System.currentTimeMillis() - time;
                decodeTimeSec.postValue(timeSec);

                if (onFt8Listen != null) {
                    onFt8Listen.afterDecodeFinished(utc, timeSec);
                }

                Log.d(TAG, String.format("解码耗时:%d毫秒,mode=%s",
                        timeSec,
                        FT8Common.modeToString(decodeMode)));
            }
        }).start();
    }

    /**
     * 执行单轮同步与解码
     *
     * @param ft8Decoder 解码器
     * @param utc        UTC
     * @param isDeep     是否深度解码
     * @param decodeMode 本轮固定模式
     */
    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep, int decodeMode,
                                            long deadlineMs) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(decodeMode);

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        ft8Message.signalFormat = decodeMode;

        a91List.clear();

        // 设置解码模式
        setDecodeMode(ft8Decoder, isDeep);

        int num_candidates = DecoderFt8FindSync(ft8Decoder);

        for (int idx = 0; idx < num_candidates; ++idx) {
            if (isDeep && deadlineMs > 0L && System.currentTimeMillis() >= deadlineMs) {
                break;
            }
            // Deep decode uses a hard wall-clock cutoff so one slow round cannot run far past the UI budget.

            try {
                ft8Message.signalFormat = decodeMode;

                if (DecoderFt8Analysis(idx, ft8Decoder, ft8Message)) {
                    if (ft8Message.isValid) {
                        Ft8Message msg = new Ft8Message(ft8Message);
                        msg.signalFormat = decodeMode;

                        if (checkMessageSame(ft8Messages, msg)) {
                            continue;
                        }

                        msg.isWeakSignal = isDeep;//是不是弱信号
                        ft8Messages.add(msg);

                        if (shouldAddToSubtractList(msg, isDeep, decodeMode)) {
                            byte[] a91 = DecoderGetA91(ft8Decoder);
                            a91List.add(a91, msg.freq_hz, msg.time_sec, msg.snr, msg.score, decodeMode);
                        }
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

    public native void DecoderSetApHints(long decoder, String myCall, String[] hintCallsigns, String[] hintGrids);
    // Native only receives a tiny hint set here; the AP logic still lives in the deep fallback path.
}
