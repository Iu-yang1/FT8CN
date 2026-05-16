package com.bg7yoz.ft8cn.ft8listener;
/**
 * 用于监听音频并驱动 FT8 / FT4 解码。时隙节奏由 UtcTimer 控制，
 * 音频数据通过 OnWaveDataListener 提供。
 *
 * 1. 每一轮解码都会先固定 decodeMode，避免解码过程中被 UI 切换 FT8 / FT4 打断。
 * 2. FT4 也允许进入深度解码，但仍然受整体解码预算约束。
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.content.Context;
import android.media.AudioFormat;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.BuildConfig;
import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.experimental.ExperimentalCodecBridge;
import com.bg7yoz.ft8cn.experimental.ExperimentalCodecEngine;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.wave.FT8Resample;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.wave.WriteWavHeader;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    private static final int AP_HINT_CALL_LIMIT = 4;
    private static final int DECODE_STAGE_FULL = 0;
    private static final int DECODE_STAGE_EARLY = 1;
    private static final long NATIVE_DECODE_THREAD_STACK_BYTES = 16L * 1024L * 1024L;
    // AP-lite only keeps a few recent follow calls so the native fallback stays cheap.

    private UtcTimer utcTimer;
    private final OnFt8Listen onFt8Listen; // 监听开始、解码完成后的回调

    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>(); // 最近一次解码耗时
    public long timeSec = 0; // 最近一次解码耗时缓存

    private OnWaveDataListener onWaveDataListener;
    private DatabaseOpr db;
    public MutableLiveData<String> decodeStatusText = new MutableLiveData<>(); // 左上角解码指示文本

    private final A91List a91List = new A91List(); // subtract 所需的 A91 缓存
    private final Object slotDedupeLock = new Object();
    private final ArrayList<SlotDedupeEntry> slotDedupeEntries = new ArrayList<>();
    private long slotDedupeUtc = Long.MIN_VALUE;
    private int slotDedupeMode = -1;
    private final Object liveFullDecodeLock = new Object();
    private final boolean[] liveFullDecodeRunning = new boolean[]{false, false};
    private final long[] liveFullDecodeUtc = new long[]{Long.MIN_VALUE, Long.MIN_VALUE};

    static {
        System.loadLibrary("ft8cn");
    }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);

        int getCurrentSampleRate();
    }

    private static class SlotFilterResult {
        final ArrayList<Ft8Message> messages;
        final boolean hadPublishedBefore;

        SlotFilterResult(ArrayList<Ft8Message> messages, boolean hadPublishedBefore) {
            this.messages = messages;
            this.hadPublishedBefore = hadPublishedBefore;
        }
    }

    private static class SlotDedupeEntry {
        final Ft8Message message;
        boolean publishedAsStrong;

        SlotDedupeEntry(Ft8Message message, boolean publishedAsStrong) {
            this.message = message;
            this.publishedAsStrong = publishedAsStrong;
        }
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        this.onFt8Listen = onFt8Listen;
        this.db = db;
        buildUtcTimer();
    }

    /**
     * 把当前解码阶段同步到前端左上角，便于区分提前解码和完整解码。
     */
    private void postDecodeStageStatus(int decodeMode, int decodeStage) {
        final String modeName = FT8Common.modeToString(decodeMode);
        final String stageName = (decodeStage == DECODE_STAGE_EARLY)
                ? "\u63d0\u524d\u89e3\u7801\u4e2d"
                : "\u5b8c\u6574\u89e3\u7801\u4e2d";
        decodeStatusText.postValue(modeName + " " + stageName);
    }

    /**
     * 按当前模式重建 UTC 定时器。
     */
    private void buildUtcTimer() {
        utcTimer = new UtcTimer(FT8Common.getSlotTimeM(GeneralVariables.getSignalMode()), false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {
            }

            @Override
            public void doOnSecTimer(long utc) {
                Log.d(TAG, String.format("触发录音,utc=%d,mode=%s",
                        utc,
                        FT8Common.modeToString(GeneralVariables.getSignalMode())));
                runRecorde(utc);
            }
        });
    }

    /**
     * 模式切换后重建监听周期。
     */
    public void restartByCurrentMode() {
        boolean running = isListening();
        if (utcTimer != null) {
            utcTimer.destroy();
        }
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
        if (utcTimer != null) {
            utcTimer.destroy();
        }
    }

    public boolean isListening() {
        return utcTimer != null && utcTimer.isRunning();
    }

    /**
     * 返回当前时钟偏移，包含本地顺延和 NTP 修正。
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * 按当前模式拉取音频并启动本轮解码。
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "开始录音...");

        if (onWaveDataListener != null) {
            final int recordMode = GeneralVariables.getSignalMode();
            final int duration = FT8Common.getSlotTimeMillisecond(recordMode);
            final int expectedSamples = FT8Common.getSamplesPerSlot(recordMode);
            final int sourceSampleRate = normalizeInputSampleRate(onWaveDataListener.getCurrentSampleRate());

            resetSlotDedupe(utc, recordMode);
            if (onFt8Listen != null) {
                onFt8Listen.beforeListen(utc);
            }

            if (shouldRunEarlyDecodeStage(recordMode)) {
                final int earlyDuration = FT8Common.getEarlyDecodeDurationMs(recordMode);
                onWaveDataListener.getVoiceData(
                        earlyDuration,
                        true,
                        new OnGetVoiceDataDone() {
                            @Override
                            public void onGetDone(float[] data) {
                                Log.d(TAG, String.format("收到提前解码音频:samples=%d,mode=%s",
                                        data.length,
                                        FT8Common.modeToString(recordMode)));
                                decodeFt8(
                                        utc,
                                        data,
                                        sourceSampleRate,
                                        recordMode,
                                        DECODE_STAGE_EARLY,
                                        expectedSamples,
                                        false,
                                        false
                                );
                            }
                        });
            }

            onWaveDataListener.getVoiceData(
                    duration,
                    true,
                    new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            Log.d(TAG, String.format("收到完整解码音频:samples=%d,mode=%s",
                                    data.length,
                                    FT8Common.modeToString(recordMode)));
                            decodeFt8(
                                    utc,
                                    data,
                                    sourceSampleRate,
                                    recordMode,
                                    DECODE_STAGE_FULL,
                                    expectedSamples,
                                    false,
                                    true
                            );
                        }
                    });
        }
    }

    /**
     * 兼容旧调用：外部只给音频时，按当前模式走完整解码。
     */
    public void decodeFt8(long utc, float[] voiceData) {
        decodeFt8(utc, voiceData, FT8Common.SAMPLE_RATE, GeneralVariables.getSignalMode());
    }

    public void decodeFt8(long utc, float[] voiceData, int sourceSampleRate, int decodeMode) {
        decodeFt8(
                utc,
                voiceData,
                sourceSampleRate,
                decodeMode,
                DECODE_STAGE_FULL,
                FT8Common.getSamplesPerSlot(decodeMode),
                true,
                true
        );
    }

    private int normalizeInputSampleRate(int sampleRate) {
        if (sampleRate == 12000 || sampleRate == 24000 || sampleRate == 48000) {
            return sampleRate;
        }
        return FT8Common.SAMPLE_RATE;
    }

    private float[] resampleForDecoder(float[] voiceData,
                                       int sourceSampleRate,
                                       int decodeMode,
                                       int decodeStage) {
        if (voiceData == null) {
            return null;
        }

        final int normalizedSourceRate = normalizeInputSampleRate(sourceSampleRate);
        if (normalizedSourceRate == FT8Common.SAMPLE_RATE) {
            return voiceData;
        }

        float[] resampled = FT8Resample.resampleFloatToFloatSafe(
                voiceData,
                normalizedSourceRate,
                FT8Common.SAMPLE_RATE,
                1
        );
        if (resampled == null || resampled.length == 0) {
            Log.w(TAG, String.format(
                    "解码前重采样失败，回退原始输入: src=%d,target=%d,mode=%s,stage=%d,len=%d",
                    normalizedSourceRate,
                    FT8Common.SAMPLE_RATE,
                    FT8Common.modeToString(decodeMode),
                    decodeStage,
                    voiceData.length
            ));
            return voiceData;
        }

        Log.d(TAG, String.format(
                "解码前重采样: src=%d,target=%d,mode=%s,stage=%d,len=%d->%d",
                normalizedSourceRate,
                FT8Common.SAMPLE_RATE,
                FT8Common.modeToString(decodeMode),
                decodeStage,
                voiceData.length,
                resampled.length
        ));
        return resampled;
    }

    private boolean shouldRunEarlyDecodeStage(int decodeMode) {
        return GeneralVariables.wsjtxEnableEarlyDecode
                && FT8Common.supportsEarlyDecodeStage(decodeMode)
                && ReBuildSignal.supportSubtract(decodeMode)
                && !GeneralVariables.isExperimentalCodecEnabled();
    }

    /**
     * experimental codec 仍固定使用 12k 输入。
     * 在这里完成重采样，避免 FT8/FT4 多采样率接入的副作用扩散到实验调制解调链路。
     */
    private float[] prepareExperimentalInput(float[] voiceData, int sampleRate, int decodeMode, String sourceTag) {
        if (voiceData == null) {
            return null;
        }
        final int normalizedRate = normalizeInputSampleRate(sampleRate);
        if (normalizedRate == FT8Common.SAMPLE_RATE) {
            Log.d(TAG, String.format(
                    "EXP input keep native rate: src=%s, mode=%s, sr=%d, len=%d",
                    sourceTag,
                    FT8Common.modeToString(decodeMode),
                    normalizedRate,
                    voiceData.length
            ));
            return voiceData;
        }

        float[] resampled = FT8Resample.resampleFloatToFloatSafe(
                voiceData,
                normalizedRate,
                FT8Common.SAMPLE_RATE,
                1
        );
        if (resampled == null || resampled.length == 0) {
            Log.w(TAG, String.format(
                    "EXP input resample failed, fallback raw: src=%s, mode=%s, sr=%d, len=%d",
                    sourceTag,
                    FT8Common.modeToString(decodeMode),
                    normalizedRate,
                    voiceData.length
            ));
            return voiceData;
        }

        Log.d(TAG, String.format(
                "EXP input resampled: src=%s, mode=%s, sr=%d->%d, len=%d->%d",
                sourceTag,
                FT8Common.modeToString(decodeMode),
                normalizedRate,
                FT8Common.SAMPLE_RATE,
                voiceData.length,
                resampled.length
        ));
        return resampled;
    }

    private void resetSlotDedupe(long utc, int decodeMode) {
        synchronized (slotDedupeLock) {
            slotDedupeUtc = utc;
            slotDedupeMode = decodeMode;
            slotDedupeEntries.clear();
        }
    }

    private SlotFilterResult filterNewSlotMessages(long utc, int decodeMode, ArrayList<Ft8Message> messages) {
        ArrayList<Ft8Message> filtered = new ArrayList<>();
        synchronized (slotDedupeLock) {
            if (slotDedupeUtc != utc || slotDedupeMode != decodeMode) {
                slotDedupeUtc = utc;
                slotDedupeMode = decodeMode;
                slotDedupeEntries.clear();
            }

            boolean hadPublishedBefore = slotDedupeEntries.size() > 0;
            if (messages == null) {
                return new SlotFilterResult(filtered, hadPublishedBefore);
            }

            for (Ft8Message message : messages) {
                if (message == null) {
                    continue;
                }
                boolean messageIsStrong = !message.isWeakSignal;
                SlotDedupeEntry entry = findSlotDedupeEntry(message);
                if (entry == null) {
                    slotDedupeEntries.add(new SlotDedupeEntry(message, messageIsStrong));
                    filtered.add(message);
                    continue;
                }
                entry.message.mergeDecodeQualityFrom(message);
                if (!entry.publishedAsStrong && messageIsStrong) {
                    entry.publishedAsStrong = true;
                    filtered.add(message);
                }
            }
            return new SlotFilterResult(filtered, hadPublishedBefore);
        }
    }

    private SlotDedupeEntry findSlotDedupeEntry(Ft8Message message) {
        for (SlotDedupeEntry entry : slotDedupeEntries) {
            if (entry.message.isSameDecodedMessage(message)) {
                return entry;
            }
        }
        return null;
    }

    private void publishDecodeMessages(long utc,
                                       int slotTimeM,
                                       int decodeMode,
                                       ArrayList<Ft8Message> messages,
                                       ArrayList<Ft8Message> offsetMessages,
                                       boolean isDeep,
                                       boolean publishEmptyWhenSlotIsNew) {
        SlotFilterResult filtered = filterNewSlotMessages(utc, decodeMode, messages);
        if (filtered.messages.size() == 0 && (!publishEmptyWhenSlotIsNew || filtered.hadPublishedBefore)) {
            return;
        }

        if (onFt8Listen != null) {
            onFt8Listen.afterDecode(
                    utc,
                    averageOffset(offsetMessages == null ? filtered.messages : offsetMessages),
                    UtcTimer.sequential(utc, slotTimeM),
                    filtered.messages,
                    isDeep
            );
        }
    }

    /**
     * FT4 的 subtract 轮数比 FT8 更保守。
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
     * 判断消息是否允许加入 subtract 列表。
     * 普通解码可稍宽，深度解码后更严格，避免误码进一步扩散。
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
     * 当前轮结果里是否存在足够高质量的消息，可以继续执行 subtract。
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
     * 核心解码入口。
     *
     * @param utc        当前时隙 UTC
     * @param voiceData  输入音频数据
     * @param decodeMode 本轮固定模式，避免解码线程运行过程中混入另一种模式
     */
    public void decodeFt8(long utc, float[] voiceData, int decodeMode) {
        decodeFt8(utc, voiceData, FT8Common.SAMPLE_RATE, decodeMode);
    }

    private void decodeFt8(long utc,
                           float[] voiceData,
                           int sourceSampleRate,
                           int decodeMode,
                           int decodeStage,
                           int expectedSamples,
                           boolean notifyBefore,
                           boolean notifyFinished) {
        final boolean liveFullSessionRequest = isLiveFullSessionRequest(
                decodeStage,
                notifyBefore,
                notifyFinished
        );
        if (GeneralVariables.isExperimentalCodecEnabled()) {
            if (decodeStage == DECODE_STAGE_EARLY) {
                return;
            }
            // Experimental modem bypasses the FT8/FT4 native decoder but still
            // feeds results through the same UI callback path.
            decodeExperimentalAsync(
                    utc,
                    voiceData,
                    normalizeInputSampleRate(sourceSampleRate),
                    decodeMode,
                    "rx"
            );
            return;
        }

        if (liveFullSessionRequest && !tryBeginLiveFullDecode(decodeMode, utc)) {
            Log.w(TAG, String.format(
                    "skip overlapped live full decode request, mode=%s, utc=%d, previousUtc=%d",
                    FT8Common.modeToString(decodeMode),
                    utc,
                    getLiveFullDecodeUtc(decodeMode)
            ));
            return;
        }

        Thread decodeThread = new Thread(null, new Runnable() {
            @Override
            public void run() {
                long ft8Decoder = 0L;
                try {
                long time = System.currentTimeMillis();
                final int slotTimeM = FT8Common.getSlotTimeM(decodeMode);

                if (notifyBefore && onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }
                postDecodeStageStatus(decodeMode, decodeStage);

                boolean isFt8 = (decodeMode == FT8Common.FT8_MODE);
                final float[] decoderInput = resampleForDecoder(
                        voiceData,
                        sourceSampleRate,
                        decodeMode,
                        decodeStage
                );
                if (decoderInput == null || decoderInput.length == 0) {
                    Log.w(TAG, String.format(
                            "解码输入为空，跳过本轮: mode=%s, stage=%d, srcRate=%d",
                            FT8Common.modeToString(decodeMode),
                            decodeStage,
                            sourceSampleRate
                    ));
                    return;
                }

                // 记录真实送入 native decoder 的音频，便于复现 FT8 / FT4 前端链路。
                maybeDumpDecoderInput(decoderInput, utc, sourceSampleRate, decodeMode, decodeStage, expectedSamples);
                ft8Decoder = InitDecoder(
                        utc,
                        FT8Common.SAMPLE_RATE,
                        expectedSamples,
                        isFt8
                );

                // 设置 AP hints。
                String[][] apHints = buildDecoderApHints();
                DecoderSetApHints(
                        ft8Decoder,
                        GeneralVariables.getShortCallsign(GeneralVariables.myCallsign).toUpperCase().trim(),
                        apHints[0],
                        apHints[1]
                );
                DecoderSetWsjtOptions(
                        ft8Decoder,
                        GeneralVariables.wsjtxDecodePassCount,
                        GeneralVariables.wsjtxMultiDecodeRoundCount,
                        GeneralVariables.wsjtxQsoFreqSensitivity,
                        GeneralVariables.wsjtxDecodeSensitivity,
                        GeneralVariables.wsjtxEnableEarlyDecode,
                        GeneralVariables.wsjtxWidebandDxSearch
                );
                // AP-lite only receives my-call plus a few follow-call/grid hints before decode starts.
                DecoderMonitorPressFloat(decoderInput, ft8Decoder);
                boolean nativeOwnsSessionFlow = DecoderOwnsSessionFlow(ft8Decoder);

                ArrayList<Ft8Message> allMsg = new ArrayList<>();
                if (decodeStage == DECODE_STAGE_EARLY) {
                    long earlyDecodeDeadlineMs = System.currentTimeMillis()
                            + FT8Common.getEarlyDecodeTimeoutMs(decodeMode);
                    ArrayList<Ft8Message> earlyMsgs = runDecode(
                            ft8Decoder,
                            utc,
                            false,
                            false,
                            decodeMode,
                            earlyDecodeDeadlineMs
                    );
                    addMsgToList(allMsg, earlyMsgs);

                    timeSec = System.currentTimeMillis() - time;
                    publishDecodeMessages(
                            utc,
                            slotTimeM,
                            decodeMode,
                            earlyMsgs,
                            allMsg,
                            false,
                            false
                    );

                    DeleteDecoder(ft8Decoder);
                    ft8Decoder = 0L;
                    timeSec = System.currentTimeMillis() - time;
                    Log.d(TAG, String.format("提前解码耗时:%d毫秒,mode=%s",
                            timeSec,
                            FT8Common.modeToString(decodeMode)));
                    return;
                }

                if (nativeOwnsSessionFlow) {
                    /* 官方 backend 已在 native 内部管理整轮 session flow，Java 这里直接跑完整 session，避免外层重复 fast/deep。 */ ArrayList<Ft8Message> sessionMsgs = runDecode(
                            ft8Decoder,
                            utc,
                            GeneralVariables.deepDecodeMode,
                            false,
                            decodeMode,
                            0L
                    );
                    addMsgToList(allMsg, sessionMsgs);

                    timeSec = System.currentTimeMillis() - time;

                    publishDecodeMessages(
                            utc,
                            slotTimeM,
                            decodeMode,
                            sessionMsgs,
                            allMsg,
                            false,
                            true
                    );

                    DeleteDecoder(ft8Decoder);
                    ft8Decoder = 0L;
                    timeSec = System.currentTimeMillis() - time;
                    decodeTimeSec.postValue(timeSec);

                    if (notifyFinished && onFt8Listen != null) {
                        onFt8Listen.afterDecodeFinished(utc, timeSec);
                    }

                    Log.d(TAG, String.format("官方 session 解码耗时:%d毫秒,mode=%s",
                            timeSec,
                            FT8Common.modeToString(decodeMode)));
                    return;
                }

                ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false, false, decodeMode, 0L);
                addMsgToList(allMsg, msgs);

                timeSec = System.currentTimeMillis() - time;

                publishDecodeMessages(
                        utc,
                        slotTimeM,
                        decodeMode,
                        msgs,
                        allMsg,
                        false,
                        true
                );

                // 只有支持 subtract 的模式才进入深度重解流程。
                if (GeneralVariables.deepDecodeMode && ReBuildSignal.supportSubtract(decodeMode)) {
                    long deepDecodeDeadlineMs = System.currentTimeMillis() + FT8Common.DEEP_DECODE_TIMEOUT;
                    // The deep-decode timeout is enforced as a real deadline instead of only checking between rounds.

                    msgs = runDecode(ft8Decoder, utc, true, true, decodeMode, deepDecodeDeadlineMs);
                    addMsgToList(allMsg, msgs);

                    timeSec = System.currentTimeMillis() - time;

                    publishDecodeMessages(
                            utc,
                            slotTimeM,
                            decodeMode,
                            msgs,
                            allMsg,
                            true,
                            false
                    );

                    if (!nativeOwnsSessionFlow) {
                        int maxRounds = getMaxSubtractRounds(decodeMode);
                        int round = 0;

                        while (round < maxRounds) {
                            if (System.currentTimeMillis() >= deepDecodeDeadlineMs) {
                                break;
                            }

                            if (!hasQualifiedSubtractMsg(msgs, decodeMode)) {
                                break;
                            }

                            // 按本轮固定模式执行 subtract，避免中途切换模式。
                            ReBuildSignal.subtractSignal(ft8Decoder, a91List, decodeMode);

                            msgs = runDecode(ft8Decoder, utc, true, true, decodeMode, deepDecodeDeadlineMs);
                            if (msgs.size() == 0) {
                                break;
                            }

                            addMsgToList(allMsg, msgs);

                            timeSec = System.currentTimeMillis() - time;

                            publishDecodeMessages(
                                    utc,
                                    slotTimeM,
                                    decodeMode,
                                    msgs,
                                    allMsg,
                                    true,
                                    false
                            );

                            round++;
                        }
                    }
                }

                DeleteDecoder(ft8Decoder);
                ft8Decoder = 0L;
                timeSec = System.currentTimeMillis() - time;
                decodeTimeSec.postValue(timeSec);

                if (notifyFinished && onFt8Listen != null) {
                    onFt8Listen.afterDecodeFinished(utc, timeSec);
                }

                Log.d(TAG, String.format("解码耗时:%d毫秒,mode=%s",
                        timeSec,
                        FT8Common.modeToString(decodeMode)));
                } finally {
                    if (ft8Decoder != 0L) {
                        DeleteDecoder(ft8Decoder);
                    }
                    if (liveFullSessionRequest) {
                        finishLiveFullDecode(decodeMode, utc);
                    }
                }
            }
        }, "ft8-native-decode", NATIVE_DECODE_THREAD_STACK_BYTES);
        decodeThread.start();
    }

    private boolean isLiveFullSessionRequest(int decodeStage, boolean notifyBefore, boolean notifyFinished) {
        return decodeStage == DECODE_STAGE_FULL && !notifyBefore && notifyFinished;
    }

    private int getLiveDecodeModeIndex(int decodeMode) {
        return decodeMode == FT8Common.FT4_MODE ? 1 : 0;
    }

    private boolean isLiveFullDecodeRunning(int decodeMode) {
        synchronized (liveFullDecodeLock) {
            return liveFullDecodeRunning[getLiveDecodeModeIndex(decodeMode)];
        }
    }

    private long getLiveFullDecodeUtc(int decodeMode) {
        synchronized (liveFullDecodeLock) {
            return liveFullDecodeUtc[getLiveDecodeModeIndex(decodeMode)];
        }
    }

    private boolean tryBeginLiveFullDecode(int decodeMode, long utc) {
        synchronized (liveFullDecodeLock) {
            int modeIndex = getLiveDecodeModeIndex(decodeMode);
            if (liveFullDecodeRunning[modeIndex]) {
                return false;
            }
            liveFullDecodeRunning[modeIndex] = true;
            liveFullDecodeUtc[modeIndex] = utc;
            return true;
        }
    }

    private void finishLiveFullDecode(int decodeMode, long utc) {
        synchronized (liveFullDecodeLock) {
            int modeIndex = getLiveDecodeModeIndex(decodeMode);
            if (liveFullDecodeUtc[modeIndex] == utc) {
                liveFullDecodeRunning[modeIndex] = false;
                liveFullDecodeUtc[modeIndex] = Long.MIN_VALUE;
            }
        }
    }

    /**
     * debug 构建下保存真实送入 native decoder 的 12k 音频，
     * 便于复现前端实际解码链路。
     */
    private void maybeDumpDecoderInput(float[] decoderInput,
                                       long utc,
                                       int sourceSampleRate,
                                       int decodeMode,
                                       int decodeStage,
                                       int expectedSamples) {
        if (!BuildConfig.DEBUG || decoderInput == null || decoderInput.length == 0) {
            return;
        }

        Context context = GeneralVariables.getMainContext();
        if (context == null) {
            return;
        }

        File dir = new File(context.getFilesDir(), "diagnostics/live_decoder_input");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }

        final String modeName = (decodeMode == FT8Common.FT4_MODE) ? "ft4" : "ft8";
        final String stageName = (decodeStage == DECODE_STAGE_EARLY) ? "early" : "full";
        File wavFile = new File(dir, "last_" + modeName + "_" + stageName + ".wav");
        File metaFile = new File(dir, "last_" + modeName + "_" + stageName + ".txt");

        try {
            writeDebugWavFile(wavFile, decoderInput, FT8Common.SAMPLE_RATE);
            writeDebugMetadata(metaFile,
                    utc,
                    sourceSampleRate,
                    decodeMode,
                    decodeStage,
                    expectedSamples,
                    decoderInput.length,
                    wavFile.getAbsolutePath());
            Log.d(TAG, String.format(Locale.US,
                    "保存真实解码输入 mode=%s stage=%s path=%s samples=%d",
                    modeName,
                    stageName,
                    wavFile.getAbsolutePath(),
                    decoderInput.length));
        } catch (IOException exception) {
            Log.w(TAG, "保存解码输入失败: " + exception.getMessage());
        }
    }

    private void writeDebugWavFile(File file, float[] samples, int sampleRate) throws IOException {
        final int pcmBytes = samples.length * 2;
        try (DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(file))) {
            new WriteWavHeader(
                    pcmBytes,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            ).writeHeader(outputStream);
            for (float sample : samples) {
                int scaled = Math.round(sample * 32767.0f);
                if (scaled > 32767) {
                    scaled = 32767;
                } else if (scaled < -32768) {
                    scaled = -32768;
                }
                outputStream.writeByte(scaled & 0xFF);
                outputStream.writeByte((scaled >> 8) & 0xFF);
            }
            outputStream.flush();
        }
    }

    private void writeDebugMetadata(File file,
                                    long utc,
                                    int sourceSampleRate,
                                    int decodeMode,
                                    int decodeStage,
                                    int expectedSamples,
                                    int decoderSamples,
                                    String wavPath) throws IOException {
        String text = String.format(Locale.US,
                "utc=%d%nmode=%s%nstage=%s%nsourceSampleRate=%d%ndecoderSampleRate=%d%nexpectedSamples=%d%ndecoderSamples=%d%ndeepDecodeMode=%s%ndecodePassCount=%d%nmultiDecodeRoundCount=%d%nqsoFreqSensitivity=%d%ndecodeSensitivity=%d%nenableEarlyDecode=%s%nwidebandDxSearch=%s%nexperimentalCodecMode=%d%nmyCall=%s%nwavPath=%s%n",
                utc,
                FT8Common.modeToString(decodeMode),
                (decodeStage == DECODE_STAGE_EARLY) ? "early" : "full",
                sourceSampleRate,
                FT8Common.SAMPLE_RATE,
                expectedSamples,
                decoderSamples,
                GeneralVariables.deepDecodeMode ? "true" : "false",
                GeneralVariables.wsjtxDecodePassCount,
                GeneralVariables.wsjtxMultiDecodeRoundCount,
                GeneralVariables.wsjtxQsoFreqSensitivity,
                GeneralVariables.wsjtxDecodeSensitivity,
                GeneralVariables.wsjtxEnableEarlyDecode ? "true" : "false",
                GeneralVariables.wsjtxWidebandDxSearch ? "true" : "false",
                GeneralVariables.experimentalCodecMode,
                GeneralVariables.myCallsign == null ? "" : GeneralVariables.myCallsign,
                wavPath);

        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(text.getBytes("UTF-8"));
            outputStream.flush();
        }
    }

    public void decodeExperimentalLoopback(long utc, float[] voiceData, int sampleRate, int decodeMode) {
        if (!GeneralVariables.isExperimentalCodecEnabled()) {
            return;
        }
        decodeExperimentalAsync(utc, voiceData, sampleRate, decodeMode, "loopback");
    }

    private void decodeExperimentalAsync(long utc, float[] voiceData, int sampleRate, int decodeMode,
                                         String sourceTag) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();
                final int slotTimeM = FT8Common.getSlotTimeM(decodeMode);
                final float[] decodeInput = prepareExperimentalInput(voiceData, sampleRate, decodeMode, sourceTag);
                final int decodeSampleRate = (decodeInput == voiceData)
                        ? normalizeInputSampleRate(sampleRate)
                        : FT8Common.SAMPLE_RATE;

                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }
                decodeStatusText.postValue(
                        FT8Common.modeToString(decodeMode) + " "
                                + "\u5b9e\u9a8c\u89e3\u7801\u4e2d"
                );

                ArrayList<Ft8Message> messages = runExperimentalDecode(
                        utc,
                        decodeInput,
                        decodeSampleRate,
                        decodeMode
                );

                timeSec = System.currentTimeMillis() - time;
                if (onFt8Listen != null) {
                    onFt8Listen.afterDecode(
                            utc,
                            averageOffset(messages),
                            UtcTimer.sequential(utc, slotTimeM),
                            messages,
                            false
                    );
                    onFt8Listen.afterDecodeFinished(utc, timeSec);
                }

                decodeTimeSec.postValue(timeSec);
                Log.d(TAG, String.format(
                        "EXP decode source=%s time=%dms mode=%s count=%d sr=%d len=%d",
                        sourceTag,
                        timeSec,
                        GeneralVariables.getActiveModeLabel(),
                        messages.size(),
                        decodeSampleRate,
                        decodeInput == null ? 0 : decodeInput.length
                ));
            }
        }).start();
    }

    /**
     * 执行一轮 native 解码。
     *
     * @param ft8Decoder 解码器句柄
     * @param utc        当前 UTC
     * @param isDeep     是否深度解码
     * @param decodeMode 当前固定模式
     */
    private ArrayList<Ft8Message> runDecode(long ft8Decoder,
                                            long utc,
                                            boolean useDeepSession,
                                            boolean markWeakSignal,
                                            int decodeMode,
                                            long deadlineMs) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(decodeMode);

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        ft8Message.signalFormat = decodeMode;

        a91List.clear();

        // 设置当前解码模式
        setDecodeMode(ft8Decoder, useDeepSession);

        int num_candidates = DecoderFt8FindSync(ft8Decoder);
        Log.d(TAG, String.format(
                "解码轮开始: mode=%s deep=%s candidates=%d deadline=%d",
                FT8Common.modeToString(decodeMode),
                useDeepSession ? "Y" : "N",
                num_candidates,
                deadlineMs
        ));

        for (int idx = 0; idx < num_candidates; ++idx) {
            if (deadlineMs > 0L && System.currentTimeMillis() >= deadlineMs) {
                Log.d(TAG, String.format(
                        "解码轮超时结束: mode=%s deep=%s analyzed=%d/%d deadline=%d",
                        FT8Common.modeToString(decodeMode),
                        useDeepSession ? "Y" : "N",
                        idx,
                        num_candidates,
                        deadlineMs
                ));
                break;
            }
            // 提前解码和深度解码都要服从真实的墙钟超时，避免早解线程拖住完整时隙。

            try {
                ft8Message.signalFormat = decodeMode;

                if (DecoderFt8Analysis(idx, ft8Decoder, ft8Message)) {
                    if (ft8Message.isValid) {
                        Ft8Message msg = new Ft8Message(ft8Message);
                        msg.signalFormat = decodeMode;
                        msg.isWeakSignal = markWeakSignal;

                        if (checkMessageSame(ft8Messages, msg)) {
                            continue;
                        }

                        ft8Messages.add(msg);

                        if (shouldAddToSubtractList(msg, useDeepSession, decodeMode)) {
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
     * 计算本轮消息的平均时间偏移。
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
     * 将新增消息去重后并入列表。
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
     * 检查列表中是否已有相同消息。
     * FT8 / FT4 视为不同模式，不互相去重。
     */
    private boolean checkMessageSame(ArrayList<Ft8Message> ft8Messages, Ft8Message ft8Message) {
        for (Ft8Message msg : ft8Messages) {
            if (msg.signalFormat != ft8Message.signalFormat) {
                continue;
            }
            if (msg.isSameDecodedMessage(ft8Message)) {
                msg.mergeDecodeQualityFrom(ft8Message);
                return true;
            }
        }
        return false;
    }

    private ArrayList<Ft8Message> runExperimentalDecode(long utc, float[] voiceData, int sampleRate,
                                                        int decodeMode) {
        ArrayList<Ft8Message> messages = new ArrayList<>();
        try {
            if (voiceData == null || voiceData.length == 0) {
                Log.w(TAG, "EXP decode skipped: empty input");
                return messages;
            }
            float baseFreq = GeneralVariables.getBaseFrequency();
            int codecMode = GeneralVariables.experimentalCodecMode;
            int probeSymbolSamples = Math.max(
                    ExperimentalCodecBridge.PROBE_SYMBOL_SAMPLES,
                    Math.round(sampleRate / 31.25f)
            );

            float[] probe = ExperimentalCodecBridge.analyzeFirstSymbolEnergies(
                    voiceData,
                    sampleRate,
                    probeSymbolSamples,
                    ExperimentalCodecBridge.PROBE_TONES_HZ
            );
            if (probe != null && probe.length >= 5) {
                int bestTone = Math.round(probe[4]);
                Log.d(TAG, String.format(
                        "EXP probe best=%d e=[%.3f, %.3f, %.3f, %.3f] v=%s",
                        bestTone,
                        probe[0],
                        probe[1],
                        probe[2],
                        probe[3],
                        ExperimentalCodecBridge.getNativeVersion()
                ));
            } else {
                Log.d(TAG, String.format(
                        "EXP probe empty sr=%d len=%d mode=%s",
                        sampleRate,
                        voiceData.length,
                        GeneralVariables.getExperimentalCodecModeString()
                ));
            }

            ExperimentalCodecEngine.DecodeResult result = ExperimentalCodecEngine.decodeWave(
                    voiceData,
                    baseFreq,
                    sampleRate,
                    codecMode
            );
            if (result.frameFound) {
                Log.d(TAG, String.format(
                        "EXP decode mode=%d crc=%s len=%d preamble=%d offset=%d snr=%ddB text=%s",
                        result.codecMode,
                        result.crcOk ? "OK" : "FAIL",
                        result.payloadLength,
                        result.preambleScore,
                        result.symbolOffset,
                        result.estimatedSnrDb,
                        result.payloadText
                ));

                if (result.crcOk && result.payloadText != null && result.payloadText.trim().length() > 0) {
                    messages.add(buildExperimentalMessage(utc, decodeMode, sampleRate, baseFreq, result));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "EXP decode failed: " + t.getMessage());
        }
        return messages;
    }

    private Ft8Message buildExperimentalMessage(long utc, int decodeMode, int sampleRate,
                                                float baseFreq,
                                                ExperimentalCodecEngine.DecodeResult result) {
        Ft8Message message = new Ft8Message(decodeMode);
        message.utcTime = utc;
        message.band = GeneralVariables.band;
        message.signalFormat = decodeMode;
        message.isValid = result.crcOk;
        message.snr = result.estimatedSnrDb;
        message.time_sec = (float) result.symbolOffset / Math.max(1, sampleRate);
        message.freq_hz = baseFreq;
        message.score = result.preambleScore;
        String payloadText = result.payloadText == null ? "" : result.payloadText;
        message.messageHash = payloadText.hashCode();
        message.i3 = 0;
        message.n3 = 0;
        // Experimental frames are plain UTF-8 payloads, not FT8 structured
        // callsign exchanges, so keep the classic fields empty and render from
        // the raw-text path instead.
        message.callsignFrom = "";
        message.callsignTo = "";
        message.extraInfo = payloadText;
        // Keep the full recovered text visible instead of forcing FT8 free-text truncation.
        message.setTransmitRawText(payloadText);
        return message;
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
     * 初始化解码器并返回 native 句柄。
     *
     * @param utcTime     当前 UTC
     * @param sampleRat   采样率，固定为 12000
     * @param num_samples 本轮期望采样点数
     * @param isFt8       true 为 FT8，false 为 FT4
     * @return 解码器句柄
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * 向解码器喂入整段 PCM 数据。
     *
     * @param buffer  wav 数据
     * @param decoder 解码器句柄
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);

    /**
     * 执行同步搜索并返回候选数量。
     *
     * @param decoder 解码器句柄
     * @return 候选数量
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * 分析单个候选并输出消息结果。
     *
     * @param idx        候选索引
     * @param decoder    解码器句柄
     * @param ft8Message 输出消息对象
     * @return 是否成功完成分析
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * 释放解码器资源。
     *
     * @param decoder 解码器句柄
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder); // 获取当前消息的 A91 数据

    public native void setDecodeMode(long decoder, boolean isDeep); // true 为深度解码，false 为快速解码

    public native boolean DecoderOwnsSessionFlow(long decoder);
    public native void DecoderSetApHints(long decoder, String myCall, String[] hintCallsigns, String[] hintGrids);
    public native void DecoderSetWsjtOptions(long decoder,
                                             int decodePassCount,
                                             int multiDecodeRoundCount,
                                             int qsoFreqSensitivity,
                                             int decodeSensitivity,
                                             boolean enableEarlyDecode,
                                             boolean enableWidebandDxSearch);
    // Native only receives a tiny hint set here; the AP logic still lives in the deep fallback path.
}

