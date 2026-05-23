package com.bg7yoz.ft8cn.ft8listener;
/**
 * 鐢ㄤ簬鐩戝惉闊抽骞堕┍鍔?FT8 / FT4 瑙ｇ爜銆傛椂闅欒妭濂忕敱 UtcTimer 鎺у埗锛?
 * 闊抽鏁版嵁閫氳繃 OnWaveDataListener 鎻愪緵銆?
 *
 * 1. 姣忎竴杞В鐮侀兘浼氬厛鍥哄畾 decodeMode锛岄伩鍏嶈В鐮佽繃绋嬩腑琚?UI 鍒囨崲 FT8 / FT4 鎵撴柇銆?
 * 2. FT4 涔熷厑璁歌繘鍏ユ繁搴﹁В鐮侊紝浣嗕粛鐒跺彈鏁翠綋瑙ｇ爜棰勭畻绾︽潫銆?
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    private static final int AP_HINT_CALL_LIMIT = 4;
    private static final int DECODE_STAGE_FULL = 0;
    private static final int DECODE_STAGE_EARLY = 1;
    private static final long NATIVE_DECODE_THREAD_STACK_BYTES = 16L * 1024L * 1024L;
    private static final AtomicInteger LISTENER_INSTANCE_COUNTER = new AtomicInteger(1);
    // AP-lite only keeps a few recent follow calls so the native fallback stays cheap.

    private UtcTimer utcTimer;
    private final int listenerInstanceId = LISTENER_INSTANCE_COUNTER.getAndIncrement();
    private final AtomicLong decodeTriggerSequence = new AtomicLong(1);
    private final AtomicLong decodeRequestSequence = new AtomicLong(1);
    private final OnFt8Listen onFt8Listen; // 鐩戝惉寮€濮嬨€佽В鐮佸畬鎴愬悗鐨勫洖璋?

    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>(); // 鏈€杩戜竴娆¤В鐮佽€楁椂
    public long timeSec = 0; // 鏈€杩戜竴娆¤В鐮佽€楁椂缂撳瓨

    private OnWaveDataListener onWaveDataListener;
    private DatabaseOpr db;

    private final A91List a91List = new A91List(); // subtract 鎵€闇€鐨?A91 缂撳瓨
    private final Object slotDedupeLock = new Object();
    private final ArrayList<SlotDedupeEntry> slotDedupeEntries = new ArrayList<>();
    private long slotDedupeUtc = Long.MIN_VALUE;
    private int slotDedupeMode = -1;
    private final Object liveFullDecodeLock = new Object();
    private final boolean[] liveFullDecodeRunning = new boolean[]{false, false};
    private final long[] liveFullDecodeUtc = new long[]{Long.MIN_VALUE, Long.MIN_VALUE};
    private final Object decodeScheduleLock = new Object();
    private final long[] latestScheduledFullDecodeUtc = new long[]{Long.MIN_VALUE, Long.MIN_VALUE};
    private final Object nativeDecoderHandleLock = new Object();
    private final long[] nativeDecoderHandles = new long[]{0L, 0L};
    private final int[] nativeDecoderExpectedSamples = new int[]{0, 0};
    private static final class NativeBatchDecodeResult {
        final ArrayList<Ft8Message> messages = new ArrayList<>();
        int bridgeRawCount;
        int mergedCount;
    }
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            return new Thread(null,
                    runnable,
                    "ft8-native-decode-worker",
                    NATIVE_DECODE_THREAD_STACK_BYTES);
        }
    });

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

    private static class DecodeProfile {
        final String stageName;
        final int decodePassCount;
        final int multiDecodeRoundCount;
        final int qsoFreqSensitivity;
        final int decodeSensitivity;
        final boolean enableEarlyDecode;
        final boolean enableWidebandDxSearch;
        final boolean useDeepSession;
        final boolean markWeakSignal;
        final boolean publishAsDeep;
        final boolean publishEmptyWhenSlotIsNew;
        final boolean scheduleDeepSupplement;

        DecodeProfile(String stageName,
                      int decodePassCount,
                      int multiDecodeRoundCount,
                      int qsoFreqSensitivity,
                      int decodeSensitivity,
                      boolean enableEarlyDecode,
                      boolean enableWidebandDxSearch,
                      boolean useDeepSession,
                      boolean markWeakSignal,
                      boolean publishAsDeep,
                      boolean publishEmptyWhenSlotIsNew,
                      boolean scheduleDeepSupplement) {
            this.stageName = stageName;
            this.decodePassCount = decodePassCount;
            this.multiDecodeRoundCount = multiDecodeRoundCount;
            this.qsoFreqSensitivity = qsoFreqSensitivity;
            this.decodeSensitivity = decodeSensitivity;
            this.enableEarlyDecode = enableEarlyDecode;
            this.enableWidebandDxSearch = enableWidebandDxSearch;
            this.useDeepSession = useDeepSession;
            this.markWeakSignal = markWeakSignal;
            this.publishAsDeep = publishAsDeep;
            this.publishEmptyWhenSlotIsNew = publishEmptyWhenSlotIsNew;
            this.scheduleDeepSupplement = scheduleDeepSupplement;
        }
    }

    private static class DecodeRequest {
        final long requestSequence;
        final long utc;
        final float[] voiceData;
        final int sourceSampleRate;
        final int decodeMode;
        final int decodeStage;
        final int expectedSamples;
        final boolean notifyBefore;
        final boolean notifyFinished;
        final boolean liveFullSessionRequest;
        final String sourceTag;
        final String enqueueReason;
        final long triggerSequence;
        final long enqueueWallClockMs;
        final DecodeProfile profile;

        DecodeRequest(long requestSequence,
                      long utc,
                      float[] voiceData,
                      int sourceSampleRate,
                      int decodeMode,
                      int decodeStage,
                      int expectedSamples,
                      boolean notifyBefore,
                      boolean notifyFinished,
                      boolean liveFullSessionRequest,
                      String sourceTag,
                      String enqueueReason,
                      long triggerSequence,
                      long enqueueWallClockMs,
                      DecodeProfile profile) {
            this.requestSequence = requestSequence;
            this.utc = utc;
            this.voiceData = voiceData;
            this.sourceSampleRate = sourceSampleRate;
            this.decodeMode = decodeMode;
            this.decodeStage = decodeStage;
            this.expectedSamples = expectedSamples;
            this.notifyBefore = notifyBefore;
            this.notifyFinished = notifyFinished;
            this.liveFullSessionRequest = liveFullSessionRequest;
            this.sourceTag = sourceTag;
            this.enqueueReason = enqueueReason;
            this.triggerSequence = triggerSequence;
            this.enqueueWallClockMs = enqueueWallClockMs;
            this.profile = profile;
        }
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        this.onFt8Listen = onFt8Listen;
        this.db = db;
        buildUtcTimer();
    }

    /**
     * 鎸夊綋鍓嶆ā寮忛噸寤?UTC 瀹氭椂鍣ㄣ€?
     */
    private void buildUtcTimer() {
        utcTimer = new UtcTimer(FT8Common.getSlotTimeM(GeneralVariables.getSignalMode()), false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {
            }

            @Override
            public void doOnSecTimer(long utc) {
                Log.d(TAG, String.format("瑙﹀彂褰曢煶,utc=%d,mode=%s",
                        utc,
                        FT8Common.modeToString(GeneralVariables.getSignalMode())));
                runRecorde(utc);
            }
        });
    }

    /**
     * 妯″紡鍒囨崲鍚庨噸寤虹洃鍚懆鏈熴€?
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
        releasePersistentNativeDecoders();
        decodeExecutor.shutdownNow();
    }

    public boolean isListening() {
        return utcTimer != null && utcTimer.isRunning();
    }

    /**
     * 杩斿洖褰撳墠鏃堕挓鍋忕Щ锛屽寘鍚湰鍦伴『寤跺拰 NTP 淇銆?
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * 鎸夊綋鍓嶆ā寮忔媺鍙栭煶棰戝苟鍚姩鏈疆瑙ｇ爜銆?
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "寮€濮嬪綍闊?..");

        if (onWaveDataListener != null) {
            final int recordMode = GeneralVariables.getSignalMode();
            final int duration = FT8Common.getSlotTimeMillisecond(recordMode);
            final int expectedSamples = FT8Common.getSamplesPerSlot(recordMode);
            final int sourceSampleRate = normalizeInputSampleRate(onWaveDataListener.getCurrentSampleRate());
            final long now = System.currentTimeMillis();
            final long triggerSequence = decodeTriggerSequence.getAndIncrement();
            final boolean requestEarly = shouldRunEarlyDecodeStage(recordMode);

            Log.d(TAG, String.format(Locale.US,
                    "decode trigger listener=%d mode=%s utc=%d now=%d slotTimeMs=%d sequence=%d source=real requestEarly=%s requestFull=Y expectedSamples=%d sourceSampleRate=%d",
                    listenerInstanceId,
                    FT8Common.modeToString(recordMode),
                    utc,
                    now,
                    duration,
                    triggerSequence,
                    requestEarly ? "Y" : "N",
                    expectedSamples,
                    sourceSampleRate));

            resetSlotDedupe(utc, recordMode);
            if (onFt8Listen != null) {
                onFt8Listen.beforeListen(utc);
            }

            if (requestEarly) {
                final int earlyDuration = FT8Common.getEarlyDecodeDurationMs(recordMode);
                onWaveDataListener.getVoiceData(
                        earlyDuration,
                        true,
                        new OnGetVoiceDataDone() {
                            @Override
                            public void onGetDone(float[] data) {
                                Log.d(TAG, String.format("鏀跺埌鎻愬墠瑙ｇ爜闊抽:samples=%d,mode=%s",
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
                                        false,
                                        "real",
                                        "timer-early",
                                        triggerSequence
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
                            Log.d(TAG, String.format("鏀跺埌瀹屾暣瑙ｇ爜闊抽:samples=%d,mode=%s",
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
                                    true,
                                    "real",
                                    "timer-full",
                                    triggerSequence
                            );
                        }
                    });
        }
    }

    /**
     * 鍏煎鏃ц皟鐢細澶栭儴鍙粰闊抽鏃讹紝鎸夊綋鍓嶆ā寮忚蛋瀹屾暣瑙ｇ爜銆?
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
                true,
                "direct",
                "direct-full",
                decodeTriggerSequence.getAndIncrement()
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
                    "瑙ｇ爜鍓嶉噸閲囨牱澶辫触锛屽洖閫€鍘熷杈撳叆: src=%d,target=%d,mode=%s,stage=%d,len=%d",
                    normalizedSourceRate,
                    FT8Common.SAMPLE_RATE,
                    FT8Common.modeToString(decodeMode),
                    decodeStage,
                    voiceData.length
            ));
            return voiceData;
        }

        Log.d(TAG, String.format(
                "瑙ｇ爜鍓嶉噸閲囨牱: src=%d,target=%d,mode=%s,stage=%d,len=%d->%d",
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
     * experimental codec 浠嶅浐瀹氫娇鐢?12k 杈撳叆銆?
     * 鍦ㄨ繖閲屽畬鎴愰噸閲囨牱锛岄伩鍏?FT8/FT4 澶氶噰鏍风巼鎺ュ叆鐨勫壇浣滅敤鎵╂暎鍒板疄楠岃皟鍒惰В璋冮摼璺€?
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

    private int publishDecodeMessages(long utc,
                                      int slotTimeM,
                                      int decodeMode,
                                      ArrayList<Ft8Message> messages,
                                      ArrayList<Ft8Message> offsetMessages,
                                      boolean isDeep,
                                      boolean publishEmptyWhenSlotIsNew) {
        SlotFilterResult filtered = filterNewSlotMessages(utc, decodeMode, messages);
        if (filtered.messages.size() == 0 && (!publishEmptyWhenSlotIsNew || filtered.hadPublishedBefore)) {
            return 0;
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
        return filtered.messages.size();
    }

    /**
     * FT4 鐨?subtract 杞暟姣?FT8 鏇翠繚瀹堛€?
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
     * 鍒ゆ柇娑堟伅鏄惁鍏佽鍔犲叆 subtract 鍒楄〃銆?
     * 鏅€氳В鐮佸彲绋嶅锛屾繁搴﹁В鐮佸悗鏇翠弗鏍硷紝閬垮厤璇爜杩涗竴姝ユ墿鏁ｃ€?
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
     * 褰撳墠杞粨鏋滈噷鏄惁瀛樺湪瓒冲楂樿川閲忕殑娑堟伅锛屽彲浠ョ户缁墽琛?subtract銆?
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
     * 鏍稿績瑙ｇ爜鍏ュ彛銆?
     *
     * @param utc        褰撳墠鏃堕殭 UTC
     * @param voiceData  杈撳叆闊抽鏁版嵁
     * @param decodeMode 鏈疆鍥哄畾妯″紡锛岄伩鍏嶈В鐮佺嚎绋嬭繍琛岃繃绋嬩腑娣峰叆鍙︿竴绉嶆ā寮?
     */
    public void decodeFt8(long utc, float[] voiceData, int decodeMode) {
        decodeFt8(utc, voiceData, FT8Common.SAMPLE_RATE, decodeMode);
    }

    private int clampInt(int value, int minValue, int maxValue) {
        if (value < minValue) {
            return minValue;
        }
        if (value > maxValue) {
            return maxValue;
        }
        return value;
    }

    private DecodeProfile buildDecodeProfile(int decodeMode, int decodeStage, boolean deepSupplement) {
        if (deepSupplement) {
            return new DecodeProfile(
                    "deep",
                    clampInt(GeneralVariables.wsjtxDecodePassCount, 2, 3),
                    clampInt(GeneralVariables.wsjtxMultiDecodeRoundCount, 2, 3),
                    clampInt(GeneralVariables.wsjtxQsoFreqSensitivity, 0, 2),
                    clampInt(Math.max(1, GeneralVariables.wsjtxDecodeSensitivity), 1, 2),
                    false,
                    GeneralVariables.wsjtxWidebandDxSearch,
                    true,
                    true,
                    true,
                    false,
                    false
            );
        }

        if (decodeStage == DECODE_STAGE_EARLY) {
            return new DecodeProfile(
                    "early",
                    1,
                    1,
                    0,
                    0,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false
            );
        }

        return new DecodeProfile(
                "live",
                clampInt(GeneralVariables.wsjtxDecodePassCount, 1, 2),
                1,
                clampInt(GeneralVariables.wsjtxQsoFreqSensitivity, 0, 1),
                1,
                false,
                false,
                false,
                false,
                false,
                true,
                GeneralVariables.deepDecodeMode && ReBuildSignal.supportSubtract(decodeMode)
        );
    }

    private void markScheduledLiveFullDecode(int decodeMode, long utc) {
        synchronized (decodeScheduleLock) {
            latestScheduledFullDecodeUtc[getLiveDecodeModeIndex(decodeMode)] = utc;
        }
    }

    private long getLatestScheduledLiveFullDecodeUtc(int decodeMode) {
        synchronized (decodeScheduleLock) {
            return latestScheduledFullDecodeUtc[getLiveDecodeModeIndex(decodeMode)];
        }
    }

    private boolean shouldSkipScheduledDecode(DecodeRequest request) {
        long latestLiveUtc = getLatestScheduledLiveFullDecodeUtc(request.decodeMode);
        boolean skip = false;
        String reason = "keep";
        if (request.liveFullSessionRequest) {
            skip = request.utc < latestLiveUtc;
            reason = skip ? "stale-live-full" : "latest-live-full";
        } else if (request.profile.publishAsDeep) {
            skip = request.utc < latestLiveUtc;
            reason = skip ? "stale-deep" : "deep-current";
        } else if (request.decodeStage == DECODE_STAGE_EARLY) {
            skip = request.utc < latestLiveUtc;
            reason = skip ? "stale-early" : "early-current";
        }
        Log.d(TAG, String.format(Locale.US,
                "decode skip-check listener=%d request=%d trigger=%d stage=%s mode=%s utc=%d latestLiveUtc=%d liveFullRunning=%s skip=%s reason=%s",
                listenerInstanceId,
                request.requestSequence,
                request.triggerSequence,
                request.profile.stageName,
                FT8Common.modeToString(request.decodeMode),
                request.utc,
                latestLiveUtc,
                isLiveFullDecodeRunning(request.decodeMode) ? "Y" : "N",
                skip ? "Y" : "N",
                reason));
        return skip;
    }

    private long getDecodeDeadlineMs(int decodeMode, DecodeProfile profile) {
        if ("early".equals(profile.stageName)) {
            return System.currentTimeMillis() + FT8Common.getEarlyDecodeTimeoutMs(decodeMode);
        }
        if ("deep".equals(profile.stageName)) {
            return System.currentTimeMillis() + FT8Common.DEEP_DECODE_TIMEOUT;
        }
        return 0L;
    }

    private int getEarlyStageSampleFloor(int expectedSamples) {
        if (expectedSamples <= 0) {
            return 0;
        }
        return expectedSamples * FT8Common.EARLY_DECODE_PHASE_TICKS / FT8Common.FULL_DECODE_PHASE_TICKS;
    }

    private String buildDecodeDiagnosticReason(DecodeRequest request,
                                              float[] decoderInput,
                                              NativeBatchDecodeResult nativeResult,
                                              int publishedCount) {
        if (request.decodeStage == DECODE_STAGE_EARLY) {
            int earlyFloorSamples = getEarlyStageSampleFloor(request.expectedSamples);
            if (decoderInput.length < earlyFloorSamples) {
                return String.format(Locale.US,
                        "insufficient-early-samples current=%d required=%d",
                        decoderInput.length,
                        earlyFloorSamples);
            }
        }

        if (nativeResult.bridgeRawCount == 0) {
            return "no-bridge-candidates";
        }
        if (nativeResult.mergedCount == 0) {
            return "no-merged-results";
        }
        if (nativeResult.messages.size() == 0) {
            return "empty-native-batch";
        }
        if (publishedCount == 0) {
            return "all-filtered-at-java-publish";
        }
        return "results-published";
    }

    private void maybeScheduleDeepSupplement(DecodeRequest request) {
        if (!request.profile.scheduleDeepSupplement) {
            return;
        }

        Log.d(TAG, String.format(Locale.US,
                "decode deep-schedule listener=%d request=%d trigger=%d mode=%s utc=%d expectedSamples=%d source=%s",
                listenerInstanceId,
                request.requestSequence,
                request.triggerSequence,
                FT8Common.modeToString(request.decodeMode),
                request.utc,
                request.expectedSamples,
                request.sourceTag));

        enqueueDecodeRequest(new DecodeRequest(
                decodeRequestSequence.getAndIncrement(),
                request.utc,
                request.voiceData,
                request.sourceSampleRate,
                request.decodeMode,
                DECODE_STAGE_FULL,
                request.expectedSamples,
                false,
                false,
                false,
                request.sourceTag,
                "deep-supplement",
                request.triggerSequence,
                System.currentTimeMillis(),
                buildDecodeProfile(request.decodeMode, DECODE_STAGE_FULL, true)
        ));
    }

    private long acquirePersistentNativeDecoder(int decodeMode, int expectedSamples) {
        synchronized (nativeDecoderHandleLock) {
            int modeIndex = getLiveDecodeModeIndex(decodeMode);
            long existingHandle = nativeDecoderHandles[modeIndex];
            if (existingHandle != 0L && nativeDecoderExpectedSamples[modeIndex] == expectedSamples) {
                return existingHandle;
            }
            if (existingHandle != 0L) {
                DeleteBatchDecoder(existingHandle);
                nativeDecoderHandles[modeIndex] = 0L;
                nativeDecoderExpectedSamples[modeIndex] = 0;
            }

            long handle = InitBatchDecoder(
                    FT8Common.SAMPLE_RATE,
                    expectedSamples,
                    decodeMode == FT8Common.FT8_MODE
            );
            if (handle != 0L) {
                nativeDecoderHandles[modeIndex] = handle;
                nativeDecoderExpectedSamples[modeIndex] = expectedSamples;
            }
            return handle;
        }
    }

    private void releasePersistentNativeDecoders() {
        synchronized (nativeDecoderHandleLock) {
            for (int index = 0; index < nativeDecoderHandles.length; ++index) {
                if (nativeDecoderHandles[index] != 0L) {
                    DeleteBatchDecoder(nativeDecoderHandles[index]);
                    nativeDecoderHandles[index] = 0L;
                }
                nativeDecoderExpectedSamples[index] = 0;
            }
        }
    }

    private NativeBatchDecodeResult batchDecodeMessages(DecodeRequest request, float[] decoderInput) {
        NativeBatchDecodeResult result = new NativeBatchDecodeResult();
        long nativeHandle = acquirePersistentNativeDecoder(request.decodeMode, request.expectedSamples);
        if (nativeHandle == 0L) {
            Log.e(TAG, String.format(Locale.US,
                    "init batch decoder failed mode=%s stage=%s expectedSamples=%d",
                    FT8Common.modeToString(request.decodeMode),
                    request.profile.stageName,
                    request.expectedSamples));
            return result;
        }

        String[][] apHints = buildDecoderApHints();
        Ft8Message[] nativeMessages = DecoderProcessBatch(
                nativeHandle,
                request.utc,
                request.expectedSamples,
                decoderInput,
                request.decodeMode,
                request.profile.decodePassCount,
                request.profile.multiDecodeRoundCount,
                request.profile.qsoFreqSensitivity,
                request.profile.decodeSensitivity,
                request.profile.enableEarlyDecode,
                request.profile.enableWidebandDxSearch,
                request.profile.useDeepSession,
                GeneralVariables.getShortCallsign(GeneralVariables.myCallsign).toUpperCase().trim(),
                apHints[0],
                apHints[1]
        );
        result.bridgeRawCount = DecoderGetLastBridgeRawCount(nativeHandle);
        result.mergedCount = DecoderGetLastMergedCount(nativeHandle);
        if (nativeMessages == null) {
            return result;
        }

        for (Ft8Message message : nativeMessages) {
            if (message == null) {
                continue;
            }
            message.signalFormat = request.decodeMode;
            message.utcTime = request.utc;
            message.band = GeneralVariables.band;
            message.isWeakSignal = request.profile.markWeakSignal;
            result.messages.add(message);
        }
        return result;
    }

    private void enqueueDecodeRequest(DecodeRequest request) {
        long latestLiveUtcBeforeEnqueue = getLatestScheduledLiveFullDecodeUtc(request.decodeMode);
        if (request.liveFullSessionRequest) {
            markScheduledLiveFullDecode(request.decodeMode, request.utc);
        }
        long latestLiveUtcAfterEnqueue = getLatestScheduledLiveFullDecodeUtc(request.decodeMode);

        Log.d(TAG, String.format(Locale.US,
                "decode enqueue listener=%d request=%d trigger=%d stage=%s mode=%s utc=%d expectedSamples=%d voiceSamples=%d sourceSampleRate=%d liveFull=%s latestLiveUtcBefore=%d latestLiveUtcAfter=%d liveFullRunning=%s source=%s reason=%s",
                listenerInstanceId,
                request.requestSequence,
                request.triggerSequence,
                request.profile.stageName,
                FT8Common.modeToString(request.decodeMode),
                request.utc,
                request.expectedSamples,
                request.voiceData == null ? 0 : request.voiceData.length,
                request.sourceSampleRate,
                request.liveFullSessionRequest ? "Y" : "N",
                latestLiveUtcBeforeEnqueue,
                latestLiveUtcAfterEnqueue,
                isLiveFullDecodeRunning(request.decodeMode) ? "Y" : "N",
                request.sourceTag,
                request.enqueueReason));

        decodeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (shouldSkipScheduledDecode(request)) {
                        Log.d(TAG, String.format(Locale.US,
                                "skip stale decode stage=%s mode=%s utc=%d latestLiveUtc=%d",
                                request.profile.stageName,
                                FT8Common.modeToString(request.decodeMode),
                                request.utc,
                                getLatestScheduledLiveFullDecodeUtc(request.decodeMode)));
                        return;
                    }

                    executeDecodeRequest(request);
                } finally {
                    if (request.liveFullSessionRequest) {
                        finishLiveFullDecode(request.decodeMode, request.utc);
                    }
                }
            }
        });
    }

    private void executeDecodeRequest(DecodeRequest request) {
        long time = System.currentTimeMillis();
        final int slotTimeM = FT8Common.getSlotTimeM(request.decodeMode);

        if (request.notifyBefore && onFt8Listen != null) {
            onFt8Listen.beforeListen(request.utc);
        }

        final float[] decoderInput = resampleForDecoder(
                request.voiceData,
                request.sourceSampleRate,
                request.decodeMode,
                request.decodeStage
        );
        if (decoderInput == null || decoderInput.length == 0) {
            Log.w(TAG, String.format(
                    "鐟欙絿鐖滄潏鎾冲弳娑撹櫣鈹栭敍宀冪儲鏉╁洦婀版潪? mode=%s, stage=%s, srcRate=%d",
                    FT8Common.modeToString(request.decodeMode),
                    request.profile.stageName,
                    request.sourceSampleRate
            ));
            return;
        }

        final long decoderInputDurationMs = Math.round(
                decoderInput.length * 1000.0 / Math.max(1, FT8Common.SAMPLE_RATE));
        final boolean earlyPhaseThresholdReached = request.decodeStage != DECODE_STAGE_EARLY
                || decoderInput.length >= getEarlyStageSampleFloor(request.expectedSamples);

        maybeDumpDecoderInput(decoderInput,
                request.utc,
                request.sourceSampleRate,
                request.decodeMode,
                request.decodeStage,
                request.expectedSamples);

        Log.d(TAG, String.format(Locale.US,
                "decode start listener=%d request=%d trigger=%d stage=%s mode=%s utc=%d expectedSamples=%d decoderSamples=%d sourceSampleRate=%d source=%s reason=%s early=%s full=%s deep=%s sampleDurationMs=%d earlyThresholdReached=%s profile[pass=%d round=%d qso=%d sens=%d early=%s wide=%s deep=%s]",
                listenerInstanceId,
                request.requestSequence,
                request.triggerSequence,
                request.profile.stageName,
                FT8Common.modeToString(request.decodeMode),
                request.utc,
                request.expectedSamples,
                decoderInput.length,
                request.sourceSampleRate,
                request.sourceTag,
                request.enqueueReason,
                request.decodeStage == DECODE_STAGE_EARLY ? "Y" : "N",
                request.liveFullSessionRequest ? "Y" : "N",
                request.profile.publishAsDeep ? "Y" : "N",
                decoderInputDurationMs,
                earlyPhaseThresholdReached ? "Y" : "N",
                request.profile.decodePassCount,
                request.profile.multiDecodeRoundCount,
                request.profile.qsoFreqSensitivity,
                request.profile.decodeSensitivity,
                request.profile.enableEarlyDecode ? "Y" : "N",
                request.profile.enableWidebandDxSearch ? "Y" : "N",
                request.profile.useDeepSession ? "Y" : "N"));

        NativeBatchDecodeResult nativeResult;
        if (request.decodeStage == DECODE_STAGE_EARLY && !earlyPhaseThresholdReached) {
            Log.d(TAG, String.format(Locale.US,
                    "decode early-guard listener=%d request=%d trigger=%d mode=%s utc=%d inputSamples=%d expectedSamples=%d sampleDurationMs=%d reason=insufficient-early-samples",
                    listenerInstanceId,
                    request.requestSequence,
                    request.triggerSequence,
                    FT8Common.modeToString(request.decodeMode),
                    request.utc,
                    decoderInput.length,
                    request.expectedSamples,
                    decoderInputDurationMs));
            nativeResult = new NativeBatchDecodeResult();
        } else {
            nativeResult = batchDecodeMessages(request, decoderInput);
        }
        ArrayList<Ft8Message> msgs = nativeResult.messages;
        ArrayList<Ft8Message> allMsg = new ArrayList<>(msgs);

        timeSec = System.currentTimeMillis() - time;
        int publishedCount = publishDecodeMessages(
                request.utc,
                slotTimeM,
                request.decodeMode,
                msgs,
                allMsg,
                request.profile.publishAsDeep,
                request.profile.publishEmptyWhenSlotIsNew
        );

        if (msgs.size() == 0 || publishedCount == 0) {
            Log.d(TAG, String.format(Locale.US,
                    "decode diagnostic listener=%d request=%d trigger=%d stage=%s mode=%s utc=%d reason=%s inputSamples=%d expectedSamples=%d sampleDurationMs=%d earlyThresholdReached=%s bridgeRawCount=%d mergedCount=%d nativeBatchCount=%d javaPublishedCount=%d source=%s enqueueReason=%s",
                    listenerInstanceId,
                    request.requestSequence,
                    request.triggerSequence,
                    request.profile.stageName,
                    FT8Common.modeToString(request.decodeMode),
                    request.utc,
                    buildDecodeDiagnosticReason(request, decoderInput, nativeResult, publishedCount),
                    decoderInput.length,
                    request.expectedSamples,
                    decoderInputDurationMs,
                    earlyPhaseThresholdReached ? "Y" : "N",
                    nativeResult.bridgeRawCount,
                    nativeResult.mergedCount,
                    msgs.size(),
                    publishedCount,
                    request.sourceTag,
                    request.enqueueReason));
        }

        if (request.notifyFinished) {
            decodeTimeSec.postValue(timeSec);
            if (onFt8Listen != null) {
                onFt8Listen.afterDecodeFinished(request.utc, timeSec);
            }
        }

        Log.d(TAG, String.format(Locale.US,
                "decode done listener=%d request=%d trigger=%d stage=%s mode=%s utc=%d bridgeRawCount=%d mergedCount=%d nativeBatchCount=%d javaPublishedCount=%d durationMs=%d source=%s reason=%s",
                listenerInstanceId,
                request.requestSequence,
                request.triggerSequence,
                request.profile.stageName,
                FT8Common.modeToString(request.decodeMode),
                request.utc,
                nativeResult.bridgeRawCount,
                nativeResult.mergedCount,
                msgs.size(),
                publishedCount,
                timeSec,
                request.sourceTag,
                request.enqueueReason));

        maybeScheduleDeepSupplement(request);
    }

    private void decodeFt8(long utc,
                           float[] voiceData,
                           int sourceSampleRate,
                           int decodeMode,
                           int decodeStage,
                           int expectedSamples,
                           boolean notifyBefore,
                           boolean notifyFinished,
                           String sourceTag,
                           String enqueueReason,
                           long triggerSequence) {
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
        enqueueDecodeRequest(new DecodeRequest(
                decodeRequestSequence.getAndIncrement(),
                utc,
                voiceData,
                sourceSampleRate,
                decodeMode,
                decodeStage,
                expectedSamples,
                notifyBefore,
                notifyFinished,
                liveFullSessionRequest,
                sourceTag,
                enqueueReason,
                triggerSequence,
                System.currentTimeMillis(),
                buildDecodeProfile(decodeMode, decodeStage, false)
        ));
        return;

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
                if (utc <= liveFullDecodeUtc[modeIndex]) {
                    return false;
                }
                liveFullDecodeUtc[modeIndex] = utc;
                return true;
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
     * debug 鏋勫缓涓嬩繚瀛樼湡瀹為€佸叆 native decoder 鐨?12k 闊抽锛?
     * 渚夸簬澶嶇幇鍓嶇瀹為檯瑙ｇ爜閾捐矾銆?
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
                    "淇濆瓨鐪熷疄瑙ｇ爜杈撳叆 mode=%s stage=%s path=%s samples=%d",
                    modeName,
                    stageName,
                    wavFile.getAbsolutePath(),
                    decoderInput.length));
        } catch (IOException exception) {
            Log.w(TAG, "淇濆瓨瑙ｇ爜杈撳叆澶辫触: " + exception.getMessage());
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
     * 鎵ц涓€杞?native 瑙ｇ爜銆?
     *
     * @param ft8Decoder 瑙ｇ爜鍣ㄥ彞鏌?
     * @param utc        褰撳墠 UTC
     * @param isDeep     鏄惁娣卞害瑙ｇ爜
     * @param decodeMode 褰撳墠鍥哄畾妯″紡
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

        // 璁剧疆褰撳墠瑙ｇ爜妯″紡
        setDecodeMode(ft8Decoder, useDeepSession);

        int num_candidates = DecoderFt8FindSync(ft8Decoder);
        Log.d(TAG, String.format(
                "瑙ｇ爜杞紑濮? mode=%s deep=%s candidates=%d deadline=%d",
                FT8Common.modeToString(decodeMode),
                useDeepSession ? "Y" : "N",
                num_candidates,
                deadlineMs
        ));

        for (int idx = 0; idx < num_candidates; ++idx) {
            if (useDeepSession && deadlineMs > 0L && System.currentTimeMillis() >= deadlineMs) {
                break;
            }
            // Deep decode uses a hard wall-clock cutoff so one slow round cannot run far past the UI budget.

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
     * 璁＄畻鏈疆娑堟伅鐨勫钩鍧囨椂闂村亸绉汇€?
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
     * 灏嗘柊澧炴秷鎭幓閲嶅悗骞跺叆鍒楄〃銆?
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
     * 妫€鏌ュ垪琛ㄤ腑鏄惁宸叉湁鐩稿悓娑堟伅銆?
     * FT8 / FT4 瑙嗕负涓嶅悓妯″紡锛屼笉浜掔浉鍘婚噸銆?
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
     * 鍒濆鍖栬В鐮佸櫒骞惰繑鍥?native 鍙ユ焺銆?
     *
     * @param utcTime     褰撳墠 UTC
     * @param sampleRat   閲囨牱鐜囷紝鍥哄畾涓?12000
     * @param num_samples 鏈疆鏈熸湜閲囨牱鐐规暟
     * @param isFt8       true 涓?FT8锛宖alse 涓?FT4
     * @return 瑙ｇ爜鍣ㄥ彞鏌?
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * 鍚戣В鐮佸櫒鍠傚叆鏁存 PCM 鏁版嵁銆?
     *
     * @param buffer  wav 鏁版嵁
     * @param decoder 瑙ｇ爜鍣ㄥ彞鏌?
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);

    /**
     * 鎵ц鍚屾鎼滅储骞惰繑鍥炲€欓€夋暟閲忋€?
     *
     * @param decoder 瑙ｇ爜鍣ㄥ彞鏌?
     * @return 鍊欓€夋暟閲?
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * 鍒嗘瀽鍗曚釜鍊欓€夊苟杈撳嚭娑堟伅缁撴灉銆?
     *
     * @param idx        鍊欓€夌储寮?
     * @param decoder    瑙ｇ爜鍣ㄥ彞鏌?
     * @param ft8Message 杈撳嚭娑堟伅瀵硅薄
     * @return 鏄惁鎴愬姛瀹屾垚鍒嗘瀽
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * 閲婃斁瑙ｇ爜鍣ㄨ祫婧愩€?
     *
     * @param decoder 瑙ｇ爜鍣ㄥ彞鏌?
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder); // 鑾峰彇褰撳墠娑堟伅鐨?A91 鏁版嵁

    public native void setDecodeMode(long decoder, boolean isDeep); // true 涓烘繁搴﹁В鐮侊紝false 涓哄揩閫熻В鐮?

    public native boolean DecoderOwnsSessionFlow(long decoder);
    public native void DecoderSetApHints(long decoder, String myCall, String[] hintCallsigns, String[] hintGrids);
    public native void DecoderSetWsjtOptions(long decoder,
                                             int decodePassCount,
                                             int multiDecodeRoundCount,
                                             int qsoFreqSensitivity,
                                             int decodeSensitivity,
                                             boolean enableEarlyDecode,
                                             boolean enableWidebandDxSearch);
    public native long InitBatchDecoder(int sampleRate, int numSamples, boolean isFt8);
    public native void DeleteBatchDecoder(long decoderHandle);
    public native int DecoderGetLastBridgeRawCount(long decoderHandle);
    public native int DecoderGetLastMergedCount(long decoderHandle);
    public native Ft8Message[] DecoderProcessBatch(long decoderHandle,
                                                   long utcTime,
                                                   int expectedSamples,
                                                   float[] buffer,
                                                   int decodeMode,
                                                   int decodePassCount,
                                                   int multiDecodeRoundCount,
                                                   int qsoFreqSensitivity,
                                                   int decodeSensitivity,
                                                   boolean enableEarlyDecode,
                                                   boolean enableWidebandDxSearch,
                                                   boolean deepDecodeEnabled,
                                                   String myCall,
                                                   String[] hintCallsigns,
                                                   String[] hintGrids);
    // Native only receives a tiny hint set here; the AP logic still lives in the deep fallback path.
}

