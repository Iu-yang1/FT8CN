package com.bg7yoz.ft8cn.ft8listener;
/**
 * Listen to audio and drive FT8 / FT4 / Q65 decoding.
 * Slot timing is controlled by UtcTimer, and audio comes from OnWaveDataListener.
 *
 * 1. Each decode round freezes decodeMode first so UI mode changes cannot interrupt it.
 * 2. FT4 may still use deep decode, but it remains under the overall decode budget.
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
import com.bg7yoz.ft8cn.FtxModeSpec;
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
    private final OnFt8Listen onFt8Listen; // callbacks before listen and after decode finishes

    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>(); // last decode duration
    public long timeSec = 0; // cached last decode duration

    private OnWaveDataListener onWaveDataListener;
    private DatabaseOpr db;

    private final A91List a91List = new A91List(); // cached A91 payloads used by subtract
    private final Object slotDedupeLock = new Object();
    private final ArrayList<SlotDedupeEntry> slotDedupeEntries = new ArrayList<>();
    private long slotDedupeUtc = Long.MIN_VALUE;
    private int slotDedupeMode = -1;
    private final Object liveFullDecodeLock = new Object();
    private final boolean[] liveFullDecodeRunning = new boolean[]{false, false, false};
    private final long[] liveFullDecodeUtc = new long[]{Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
    private final Object decodeScheduleLock = new Object();
    private final long[] latestScheduledFullDecodeUtc = new long[]{Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
    private final Object nativeDecoderHandleLock = new Object();
    private final long[] nativeDecoderHandles = new long[]{0L, 0L, 0L};
    private final int[] nativeDecoderExpectedSamples = new int[]{0, 0, 0};
    private final Object nativeRuntimeDirLock = new Object();
    private boolean nativeRuntimeDirsConfigured = false;
    private static final class NativeBatchDecodeResult {
        final ArrayList<Ft8Message> messages = new ArrayList<>();
        int bridgeRawCount;
        int mergedCount;
        long nativeDurationMs;
        long decoderHandleMs;
        long nativeLockWaitMs;
        long decoderProcessMs;
        long resultGetterMs;
        long javaMessagePostProcessMs;
    }
    private static final class PublishDecodeResult {
        int publishedCount;
        long dedupeDurationMs;
        long listenerCallbackDurationMs;
    }
    // The current WSJT-X bridge still routes callbacks through global active-context state.
    // Keep the batch decoder path serialized on the Java side until native callback routing
    // is made context-safe end to end.
    private final Object nativeBatchDecodeLock = new Object();
    private volatile String lastDecodeStatusSummary = "";
    private final DecodeScheduler decodeScheduler = new DecodeScheduler(
            "ft8-native-decode-worker",
            NATIVE_DECODE_THREAD_STACK_BYTES,
            DecodeWorkerConfig.conservative(),
            DecodeConcurrencyPolicy.PARALLEL_PREPARE_SERIAL_NATIVE,
            new DecodeScheduler.Logger() {
                @Override
                public void debug(String text) {
                    Log.d(TAG, text);
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
        final int q65Submode;
        final int q65TrPeriodSeconds;
        final boolean notifyBefore;
        final boolean notifyFinished;
        final boolean liveFullSessionRequest;
        final String sourceTag;
        final String enqueueReason;
        final long triggerSequence;
        final long enqueueWallClockMs;
        final DecodeProfile profile;
        long deadlineMs;

        DecodeRequest(long requestSequence,
                      long utc,
                      float[] voiceData,
                      int sourceSampleRate,
                      int decodeMode,
                      int decodeStage,
                      int expectedSamples,
                      int q65Submode,
                      int q65TrPeriodSeconds,
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
            this.q65Submode = q65Submode;
            this.q65TrPeriodSeconds = q65TrPeriodSeconds;
            this.notifyBefore = notifyBefore;
            this.notifyFinished = notifyFinished;
            this.liveFullSessionRequest = liveFullSessionRequest;
            this.sourceTag = sourceTag;
            this.enqueueReason = enqueueReason;
            this.triggerSequence = triggerSequence;
            this.enqueueWallClockMs = enqueueWallClockMs;
            this.profile = profile;
            this.deadlineMs = 0L;
        }
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        this.onFt8Listen = onFt8Listen;
        this.db = db;
        buildUtcTimer();
    }

    /**
     * Rebuild the UTC timer for the current mode.
     */
    private void buildUtcTimer() {
        utcTimer = new UtcTimer(FT8Common.getSlotTimeM(GeneralVariables.getSignalMode()), false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {
            }

            @Override
            public void doOnSecTimer(long utc) {
                Log.d(TAG, String.format("record trigger, utc=%d, mode=%s",
                        utc,
                        FT8Common.modeToString(GeneralVariables.getSignalMode())));
                runRecorde(utc);
            }
        });
    }

    /**
     * Rebuild the listening cycle after a mode switch.
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
        decodeScheduler.shutdownNow();
    }

    public boolean isListening() {
        return utcTimer != null && utcTimer.isRunning();
    }

    /**
     * Return the current clock offset including local delay and NTP correction.
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * Pull audio for the current mode and start the decode round.
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "start capture...");

        if (onWaveDataListener != null) {
            final int recordMode = GeneralVariables.getSignalMode();
            final FtxModeSpec modeSpec = requireSupportedModeSpec(recordMode, "runRecorde");
            if (modeSpec == null) {
                return;
            }
            final int duration = modeSpec.slotDurationMs;
            final int expectedSamples = modeSpec.samplesPerSlot();
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
                                Log.d(TAG, String.format("received early-decode audio: samples=%d, mode=%s",
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
                            Log.d(TAG, String.format("received full-slot audio: samples=%d, mode=%s",
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
     * Compatibility path: if the caller only gives audio, run a full decode for the current mode.
     */
    public void decodeFt8(long utc, float[] voiceData) {
        decodeFt8(utc, voiceData, FT8Common.SAMPLE_RATE, GeneralVariables.getSignalMode());
    }

    public void decodeFt8(long utc, float[] voiceData, int sourceSampleRate, int decodeMode) {
        FtxModeSpec modeSpec = requireSupportedModeSpec(decodeMode, "directDecode");
        if (modeSpec == null) {
            return;
        }
        decodeFt8(
                utc,
                voiceData,
                sourceSampleRate,
                decodeMode,
                DECODE_STAGE_FULL,
                modeSpec.samplesPerSlot(),
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
                    "decode resample failed, fallback raw input: src=%d,target=%d,mode=%s,stage=%d,len=%d",
                    normalizedSourceRate,
                    FT8Common.SAMPLE_RATE,
                    FT8Common.modeToString(decodeMode),
                    decodeStage,
                    voiceData.length
            ));
            return voiceData;
        }

        Log.d(TAG, String.format(
                "decode resample: src=%d,target=%d,mode=%s,stage=%d,len=%d->%d",
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
        FtxModeSpec modeSpec = FtxModeSpec.forMode(decodeMode);
        return GeneralVariables.wsjtxEnableEarlyDecode
                && modeSpec != null
                && modeSpec.supportsEarlyDecode
                && ReBuildSignal.supportSubtract(decodeMode)
                && !GeneralVariables.isExperimentalCodecEnabled();
    }

    private FtxModeSpec requireSupportedModeSpec(int decodeMode, String entryPoint) {
        FtxModeSpec modeSpec = FtxModeSpec.forMode(decodeMode);
        if (modeSpec == null || !modeSpec.supportsRx) {
            Log.w(TAG, String.format(Locale.US,
                    "unsupported decode mode listener=%d entry=%s mode=%d name=%s supportsRx=%s buildSupported=%s",
                    listenerInstanceId,
                    entryPoint,
                    decodeMode,
                    modeSpec == null ? "unknown" : modeSpec.name,
                    modeSpec != null && modeSpec.supportsRx ? "Y" : "N",
                    modeSpec != null && modeSpec.supportedInCurrentBuild ? "Y" : "N"));
            return null;
        }
        return modeSpec;
    }

    /**
     * The experimental codec still uses fixed 12k input.
     * Resample here so multi-rate FT8/FT4 input handling does not leak into the experimental chain.
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

    private PublishDecodeResult publishDecodeMessages(long utc,
                                                      int slotTimeM,
                                                      int decodeMode,
                                                      ArrayList<Ft8Message> messages,
                                                      ArrayList<Ft8Message> offsetMessages,
                                                      boolean isDeep,
                                                      boolean publishEmptyWhenSlotIsNew) {
        PublishDecodeResult result = new PublishDecodeResult();
        long dedupeStartedAtMs = System.currentTimeMillis();
        SlotFilterResult filtered = filterNewSlotMessages(utc, decodeMode, messages);
        result.dedupeDurationMs = System.currentTimeMillis() - dedupeStartedAtMs;
        if (filtered.messages.size() == 0 && (!publishEmptyWhenSlotIsNew || filtered.hadPublishedBefore)) {
            return result;
        }

        if (onFt8Listen != null) {
            long callbackStartedAtMs = System.currentTimeMillis();
            onFt8Listen.afterDecode(
                    utc,
                    averageOffset(offsetMessages == null ? filtered.messages : offsetMessages),
                    UtcTimer.sequential(utc, slotTimeM),
                    filtered.messages,
                    isDeep
            );
            result.listenerCallbackDurationMs = System.currentTimeMillis() - callbackStartedAtMs;
        }
        result.publishedCount = filtered.messages.size();
        return result;
    }

    /**
     * FT4 keeps fewer subtract rounds than FT8.
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
     * Decide whether a message can enter the subtract list.
     * Normal decode can be looser; deep decode is stricter to avoid amplifying false decodes.
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
     * Whether the current round already contains messages strong enough to keep subtract going.
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
     * Core decode entry.
     *
     * @param utc        current slot UTC
     * @param voiceData  input audio samples
     * @param decodeMode fixed mode for this round so another mode cannot leak into the worker
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

    private int getQ65SubmodeForRequest(int decodeMode) {
        if (decodeMode != FT8Common.Q65_MODE) {
            return FT8Common.Q65_SUBMODE_A;
        }
        return GeneralVariables.getQ65Submode();
    }

    private int getQ65TrPeriodForRequest(int decodeMode) {
        if (decodeMode != FT8Common.Q65_MODE) {
            return FT8Common.Q65_DEFAULT_TR_PERIOD_SECONDS;
        }
        return GeneralVariables.getQ65TrPeriodSeconds();
    }

    private DecodeProfile buildDecodeProfile(int decodeMode, int decodeStage, boolean deepSupplement) {
        if (decodeMode == FT8Common.Q65_MODE) {
            return new DecodeProfile(
                    "live",
                    1,
                    1,
                    clampInt(GeneralVariables.wsjtxQsoFreqSensitivity, 0, 1),
                    1,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false
            );
        }
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

    private String getScheduledDecodeSkipReason(DecodeRequest request) {
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
        return skip ? reason : null;
    }

    private long getDecodeDeadlineMs(DecodeRequest request, DecodeStage stage) {
        if (stage == DecodeStage.DIAGNOSTIC_SAMPLE) {
            return request.enqueueWallClockMs + 2000L;
        }
        if ("early".equals(request.profile.stageName)) {
            return request.enqueueWallClockMs + FT8Common.getEarlyDecodeTimeoutMs(request.decodeMode);
        }
        if ("deep".equals(request.profile.stageName)) {
            return request.enqueueWallClockMs + FT8Common.DEEP_DECODE_TIMEOUT;
        }
        long slotDurationMs = request.decodeMode == FT8Common.Q65_MODE
                ? Math.max(1, request.q65TrPeriodSeconds) * 1000L
                : FT8Common.getSlotTimeMillisecond(request.decodeMode);
        return request.enqueueWallClockMs + Math.max(1L, slotDurationMs);
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

        long latestLiveUtc = getLatestScheduledLiveFullDecodeUtc(request.decodeMode);
        if (request.utc < latestLiveUtc) {
            Log.d(TAG, String.format(Locale.US,
                    "decode deep-skip listener=%d request=%d trigger=%d mode=%s utc=%d latestLiveUtc=%d reason=stale-deep-before-enqueue",
                    listenerInstanceId,
                    request.requestSequence,
                    request.triggerSequence,
                    FT8Common.modeToString(request.decodeMode),
                    request.utc,
                    latestLiveUtc));
            return;
        }
        if (decodeScheduler.getPendingJobCount() >= Math.max(1, decodeScheduler.getWorkerCount())) {
            Log.d(TAG, String.format(Locale.US,
                    "decode deep-skip listener=%d request=%d trigger=%d mode=%s utc=%d pending=%d workers=%d reason=deep-backlog-before-enqueue",
                    listenerInstanceId,
                    request.requestSequence,
                    request.triggerSequence,
                    FT8Common.modeToString(request.decodeMode),
                    request.utc,
                    decodeScheduler.getPendingJobCount(),
                    decodeScheduler.getWorkerCount()));
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
                request.q65Submode,
                request.q65TrPeriodSeconds,
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
            ensureNativeRuntimeDirectoriesConfigured();
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
                    decodeMode
            );
            if (handle != 0L) {
                nativeDecoderHandles[modeIndex] = handle;
                nativeDecoderExpectedSamples[modeIndex] = expectedSamples;
            }
            return handle;
        }
    }

    private void ensureNativeRuntimeDirectoriesConfigured() {
        synchronized (nativeRuntimeDirLock) {
            if (nativeRuntimeDirsConfigured) {
                return;
            }

            Context context = GeneralVariables.getMainContext();
            if (context == null) {
                Log.w(TAG, "native runtime dirs skipped: main context is null");
                return;
            }

            File tempDir = new File(context.getCacheDir(), "wsjtx3");
            File dataDir = new File(context.getFilesDir(), "wsjtx3");
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                Log.w(TAG, "native runtime temp dir mkdirs failed: " + tempDir.getAbsolutePath());
            }
            if (!dataDir.exists() && !dataDir.mkdirs()) {
                Log.w(TAG, "native runtime data dir mkdirs failed: " + dataDir.getAbsolutePath());
            }

            ConfigureNativeRuntimeDirectories(
                    tempDir.getAbsolutePath(),
                    dataDir.getAbsolutePath()
            );
            nativeRuntimeDirsConfigured = true;
            Log.i(TAG, String.format(Locale.US,
                    "native runtime dirs configured listener=%d temp=%s data=%s",
                    listenerInstanceId,
                    tempDir.getAbsolutePath(),
                    dataDir.getAbsolutePath()));
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
        long nativeStartedAtMs = System.currentTimeMillis();
        long decoderHandleStartedAtMs = nativeStartedAtMs;
        long nativeHandle = acquirePersistentNativeDecoder(request.decodeMode, request.expectedSamples);
        result.decoderHandleMs = System.currentTimeMillis() - decoderHandleStartedAtMs;
        if (nativeHandle == 0L) {
            Log.e(TAG, String.format(Locale.US,
                    "init batch decoder failed mode=%s stage=%s expectedSamples=%d",
                    FT8Common.modeToString(request.decodeMode),
                    request.profile.stageName,
                    request.expectedSamples));
            result.nativeDurationMs = System.currentTimeMillis() - nativeStartedAtMs;
            return result;
        }

        Ft8Message[] nativeMessages;
        long nativeLockRequestedAtMs = System.currentTimeMillis();
        synchronized (nativeBatchDecodeLock) {
            result.nativeLockWaitMs = System.currentTimeMillis() - nativeLockRequestedAtMs;
            String[][] apHints = buildDecoderApHints();
            long decoderProcessStartedAtMs = System.currentTimeMillis();
            nativeMessages = DecoderProcessBatch(
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
                    request.q65Submode,
                    request.q65TrPeriodSeconds,
                    GeneralVariables.getShortCallsign(GeneralVariables.myCallsign).toUpperCase().trim(),
                    apHints[0],
                    apHints[1]
            );
            result.decoderProcessMs = System.currentTimeMillis() - decoderProcessStartedAtMs;
            long resultGetterStartedAtMs = System.currentTimeMillis();
            result.bridgeRawCount = DecoderGetLastBridgeRawCount(nativeHandle);
            result.mergedCount = DecoderGetLastMergedCount(nativeHandle);
            result.resultGetterMs = System.currentTimeMillis() - resultGetterStartedAtMs;
        }
        result.nativeDurationMs = System.currentTimeMillis() - nativeStartedAtMs;
        if (nativeMessages == null) {
            return result;
        }

        long javaMessagePostProcessStartedAtMs = System.currentTimeMillis();
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
        result.javaMessagePostProcessMs = System.currentTimeMillis() - javaMessagePostProcessStartedAtMs;
        return result;
    }

    private DecodeStage resolveDecodeStage(DecodeRequest request) {
        if (request.profile.publishAsDeep) {
            return DecodeStage.DEEP_SUPPLEMENT;
        }
        if (request.decodeStage == DECODE_STAGE_EARLY) {
            return DecodeStage.EARLY;
        }
        if ("direct".equals(request.sourceTag) || "sample".equals(request.sourceTag)) {
            return DecodeStage.DIAGNOSTIC_SAMPLE;
        }
        return DecodeStage.LIVE_FULL;
    }

    private DecodePriority resolveDecodePriority(DecodeRequest request, DecodeStage stage) {
        if (stage == DecodeStage.DEEP_SUPPLEMENT) {
            return DecodePriority.DEEP_SUPPLEMENT;
        }
        if (stage == DecodeStage.EARLY) {
            return DecodePriority.EARLY;
        }
        if (stage == DecodeStage.DIAGNOSTIC_SAMPLE) {
            return DecodePriority.DIAGNOSTIC_SAMPLE;
        }
        if (request.decodeMode == FT8Common.Q65_MODE) {
            return DecodePriority.Q65_FULL;
        }
        return DecodePriority.LIVE_FULL;
    }

    private DecodeJob buildDecodeJob(DecodeRequest request) {
        DecodeStage stage = resolveDecodeStage(request);
        DecodePriority priority = resolveDecodePriority(request, stage);
        return new DecodeJob(
                request.requestSequence,
                request.triggerSequence,
                stage,
                priority,
                request.decodeMode,
                request.utc,
                request.sourceTag,
                request.enqueueReason,
                request.enqueueWallClockMs,
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            long startedAtMs = System.currentTimeMillis();
                            boolean deadlineMissed = request.deadlineMs > 0L
                                    && startedAtMs >= request.deadlineMs;
                            if (deadlineMissed && stage.droppable) {
                                recordSkippedDecodeBenchmark(request, stage, "deadline-missed", startedAtMs);
                                return;
                            }
                            String scheduledSkipReason = getScheduledDecodeSkipReason(request);
                            if (scheduledSkipReason != null) {
                                Log.d(TAG, String.format(Locale.US,
                                        "skip stale decode stage=%s mode=%s utc=%d latestLiveUtc=%d",
                                        request.profile.stageName,
                                        FT8Common.modeToString(request.decodeMode),
                                        request.utc,
                                        getLatestScheduledLiveFullDecodeUtc(request.decodeMode)));
                                recordSkippedDecodeBenchmark(
                                        request,
                                        stage,
                                        scheduledSkipReason,
                                        startedAtMs);
                                return;
                            }

                            executeDecodeRequest(request);
                        } finally {
                            if (request.liveFullSessionRequest) {
                                finishLiveFullDecode(request.decodeMode, request.utc);
                            }
                        }
                    }
                }
        );
    }

    private void enqueueDecodeRequest(DecodeRequest request) {
        long latestLiveUtcBeforeEnqueue = getLatestScheduledLiveFullDecodeUtc(request.decodeMode);
        if (request.liveFullSessionRequest) {
            markScheduledLiveFullDecode(request.decodeMode, request.utc);
        }
        long latestLiveUtcAfterEnqueue = getLatestScheduledLiveFullDecodeUtc(request.decodeMode);
        DecodeJob job = buildDecodeJob(request);
        request.deadlineMs = getDecodeDeadlineMs(request, job.stage);

        Log.d(TAG, String.format(Locale.US,
                "decode enqueue listener=%d request=%d trigger=%d stage=%s jobStage=%s priority=%s mode=%s utc=%d expectedSamples=%d voiceSamples=%d sourceSampleRate=%d liveFull=%s latestLiveUtcBefore=%d latestLiveUtcAfter=%d liveFullRunning=%s source=%s reason=%s schedulerWorkers=%d schedulerPending=%d policy=%s",
                listenerInstanceId,
                request.requestSequence,
                request.triggerSequence,
                request.profile.stageName,
                job.stage,
                job.priority,
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
                request.enqueueReason,
                decodeScheduler.getWorkerCount(),
                decodeScheduler.getPendingJobCount(),
                decodeScheduler.getConcurrencyPolicy()));

        if (!decodeScheduler.enqueue(job) && request.liveFullSessionRequest) {
            finishLiveFullDecode(request.decodeMode, request.utc);
        }
    }

    public void setDecodeWorkerConfig(DecodeWorkerConfig workerConfig) {
        if (workerConfig == null) {
            return;
        }
        Log.i(TAG, "update decode worker config: " + workerConfig);
        decodeScheduler.setWorkerConfig(workerConfig);
    }

    public void setDecodeWorkerPreset(DecodeWorkerConfig.Preset preset) {
        if (preset == null) {
            return;
        }
        setDecodeWorkerConfig(DecodeWorkerConfig.fromPreset(preset));
    }

    public DecodeWorkerConfig getDecodeWorkerConfig() {
        return decodeScheduler.getWorkerConfig();
    }

    public DecodeConcurrencyPolicy getDecodeConcurrencyPolicy() {
        return decodeScheduler.getConcurrencyPolicy();
    }

    public String getDecodeSchedulerStatusSummary() {
        return decodeScheduler.getStatusSummary();
    }

    public String getLastDecodeStatusSummary() {
        return lastDecodeStatusSummary;
    }

    private String buildDecodeBenchmarkSummary(DecodeRequest request,
                                               float[] decoderInput,
                                               NativeBatchDecodeResult nativeResult,
                                               int publishedCount,
                                               long startedAtMs,
                                               long finishedAtMs,
                                               long prepareDurationMs,
                                               PublishDecodeResult publishResult,
                                               String failureReason) {
        String modeLabel = request.decodeMode == FT8Common.Q65_MODE
                ? FT8Common.getQ65ModeLabel(request.q65Submode, request.q65TrPeriodSeconds)
                : FT8Common.modeToString(request.decodeMode);
        long queueDurationMs = Math.max(0L, startedAtMs - request.enqueueWallClockMs);
        boolean deadlineMissed = request.deadlineMs > 0L && finishedAtMs >= request.deadlineMs;
        return String.format(Locale.US,
                "decodeBenchmark mode=%s stage=%s profile[pass=%d round=%d qso=%d sens=%d wide=%s deep=%s] "
                        + "input[sourceRate=%d expected=%d actual=%d] scheduler[%s] "
                        + "result[raw=%d merged=%d nativeBatch=%d published=%d] "
                        + "timing[queuedMs=%d prepareMs=%d nativeMs=%d nativeHandleMs=%d nativeLockWaitMs=%d "
                        + "decoderProcessMs=%d resultGetterMs=%d javaMessagePostMs=%d dedupeMs=%d callbackMs=%d "
                        + "publishMs=%d totalMs=%d startedAtMs=%d finishedAtMs=%d deadlineMs=%d deadlineMissed=%s] "
                        + "reason=%s source=%s enqueueReason=%s utc=%d",
                modeLabel,
                request.profile.stageName,
                request.profile.decodePassCount,
                request.profile.multiDecodeRoundCount,
                request.profile.qsoFreqSensitivity,
                request.profile.decodeSensitivity,
                request.profile.enableWidebandDxSearch ? "Y" : "N",
                request.profile.useDeepSession ? "Y" : "N",
                request.sourceSampleRate,
                request.expectedSamples,
                decoderInput == null ? 0 : decoderInput.length,
                decodeScheduler.getStatusSummary(),
                nativeResult.bridgeRawCount,
                nativeResult.mergedCount,
                nativeResult.messages.size(),
                publishedCount,
                queueDurationMs,
                prepareDurationMs,
                nativeResult.nativeDurationMs,
                nativeResult.decoderHandleMs,
                nativeResult.nativeLockWaitMs,
                nativeResult.decoderProcessMs,
                nativeResult.resultGetterMs,
                nativeResult.javaMessagePostProcessMs,
                publishResult.dedupeDurationMs,
                publishResult.listenerCallbackDurationMs,
                publishResult.dedupeDurationMs + publishResult.listenerCallbackDurationMs,
                Math.max(0L, finishedAtMs - startedAtMs),
                startedAtMs,
                finishedAtMs,
                request.deadlineMs,
                deadlineMissed ? "Y" : "N",
                failureReason,
                request.sourceTag,
                request.enqueueReason,
                request.utc);
    }

    private void recordSkippedDecodeBenchmark(DecodeRequest request,
                                              DecodeStage stage,
                                              String reason,
                                              long startedAtMs) {
        lastDecodeStatusSummary = String.format(Locale.US,
                "decodeBenchmark mode=%s stage=%s profile[pass=%d round=%d] "
                        + "input[sourceRate=%d expected=%d actual=%d] scheduler[%s] "
                        + "result[raw=0 merged=0 nativeBatch=0 published=0] "
                        + "timing[queuedMs=%d prepareMs=0 nativeMs=0 nativeHandleMs=0 nativeLockWaitMs=0 "
                        + "decoderProcessMs=0 resultGetterMs=0 javaMessagePostMs=0 dedupeMs=0 callbackMs=0 "
                        + "publishMs=0 totalMs=0 startedAtMs=%d finishedAtMs=%d deadlineMs=%d deadlineMissed=Y] "
                        + "reason=%s source=%s enqueueReason=%s utc=%d",
                request.decodeMode == FT8Common.Q65_MODE
                        ? FT8Common.getQ65ModeLabel(request.q65Submode, request.q65TrPeriodSeconds)
                        : FT8Common.modeToString(request.decodeMode),
                stage,
                request.profile.decodePassCount,
                request.profile.multiDecodeRoundCount,
                request.sourceSampleRate,
                request.expectedSamples,
                request.voiceData == null ? 0 : request.voiceData.length,
                decodeScheduler.getStatusSummary(),
                Math.max(0L, startedAtMs - request.enqueueWallClockMs),
                startedAtMs,
                startedAtMs,
                request.deadlineMs,
                reason,
                request.sourceTag,
                request.enqueueReason,
                request.utc);
        Log.i(TAG, lastDecodeStatusSummary);
    }

    public void setDecodeConcurrencyPolicy(DecodeConcurrencyPolicy concurrencyPolicy) {
        if (concurrencyPolicy == null) {
            return;
        }
        if (concurrencyPolicy == DecodeConcurrencyPolicy.PARALLEL_NATIVE) {
            Log.w(TAG,
                    "reject PARALLEL_NATIVE decode policy: "
                            + "reason=native-bridge-global-context, "
                            + "bridge still uses global active context; "
                            + "forcing PARALLEL_PREPARE_SERIAL_NATIVE");
            concurrencyPolicy = DecodeConcurrencyPolicy.PARALLEL_PREPARE_SERIAL_NATIVE;
        }
        decodeScheduler.setConcurrencyPolicy(concurrencyPolicy);
        Log.i(TAG, "update decode concurrency policy: " + concurrencyPolicy);
    }

    private void executeDecodeRequest(DecodeRequest request) {
        long time = System.currentTimeMillis();
        final int slotTimeM = FT8Common.getSlotTimeM(request.decodeMode);

        if (request.notifyBefore && onFt8Listen != null) {
            onFt8Listen.beforeListen(request.utc);
        }

        long prepareStartedAtMs = System.currentTimeMillis();
        final float[] decoderInput = resampleForDecoder(
                request.voiceData,
                request.sourceSampleRate,
                request.decodeMode,
                request.decodeStage
        );
        final long prepareDurationMs = System.currentTimeMillis() - prepareStartedAtMs;
        if (decoderInput == null || decoderInput.length == 0) {
            Log.w(TAG, String.format(
                    "decode prepare failed: no usable decoder input, mode=%s, stage=%s, srcRate=%d",
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

        PublishDecodeResult publishResult = publishDecodeMessages(
                request.utc,
                slotTimeM,
                request.decodeMode,
                msgs,
                allMsg,
                request.profile.publishAsDeep,
                request.profile.publishEmptyWhenSlotIsNew
        );
        long finishedAtMs = System.currentTimeMillis();
        int publishedCount = publishResult.publishedCount;
        timeSec = finishedAtMs - time;

        String diagnosticReason = buildDecodeDiagnosticReason(request, decoderInput, nativeResult, publishedCount);
        if (msgs.size() == 0 || publishedCount == 0) {
            Log.d(TAG, String.format(Locale.US,
                    "decode diagnostic listener=%d request=%d trigger=%d stage=%s mode=%s utc=%d reason=%s inputSamples=%d expectedSamples=%d sampleDurationMs=%d earlyThresholdReached=%s bridgeRawCount=%d mergedCount=%d nativeBatchCount=%d javaPublishedCount=%d source=%s enqueueReason=%s",
                    listenerInstanceId,
                    request.requestSequence,
                    request.triggerSequence,
                    request.profile.stageName,
                    FT8Common.modeToString(request.decodeMode),
                    request.utc,
                    diagnosticReason,
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

        lastDecodeStatusSummary = buildDecodeBenchmarkSummary(
                request,
                decoderInput,
                nativeResult,
                publishedCount,
                time,
                finishedAtMs,
                prepareDurationMs,
                publishResult,
                "results-published".equals(diagnosticReason) ? "none" : diagnosticReason);
        Log.i(TAG, lastDecodeStatusSummary);

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
                getQ65SubmodeForRequest(decodeMode),
                getQ65TrPeriodForRequest(decodeMode),
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
        if (decodeMode == FT8Common.FT4_MODE) {
            return 1;
        }
        if (decodeMode == FT8Common.Q65_MODE) {
            return 2;
        }
        return 0;
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
     * In debug builds, save the real 12k audio that goes into the native decoder.
     * This makes it easier to reproduce the exact frontend decode path.
     */
    private void maybeDumpDecoderInput(float[] decoderInput,
                                       long utc,
                                       int sourceSampleRate,
                                       int decodeMode,
                                       int decodeStage,
                                       int expectedSamples) {
        if (!BuildConfig.DEBUG
                || !GeneralVariables.enableLiveDecoderInputDump
                || decoderInput == null
                || decoderInput.length == 0) {
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

        final String modeName;
        if (decodeMode == FT8Common.FT4_MODE) {
            modeName = "ft4";
        } else if (decodeMode == FT8Common.Q65_MODE) {
            modeName = "q65";
        } else {
            modeName = "ft8";
        }
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
                    "saved real decoder input mode=%s stage=%s path=%s samples=%d",
                    modeName,
                    stageName,
                    wavFile.getAbsolutePath(),
                    decoderInput.length));
        } catch (IOException exception) {
            Log.w(TAG, "save decoder input failed: " + exception.getMessage());
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
     * Execute one native decode round.
     *
     * @param ft8Decoder native decoder handle
     * @param utc        current UTC
     * @param isDeep     whether to use deep decode mode
     * @param decodeMode fixed decode mode
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

        // Set the active decode mode before pulling sync candidates.
        setDecodeMode(ft8Decoder, useDeepSession);

        int num_candidates = DecoderFt8FindSync(ft8Decoder);
        Log.d(TAG, String.format(
                "decode round start: mode=%s deep=%s candidates=%d deadline=%d",
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
     * Compute the average time offset for messages from this round.
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
     * Merge new messages into the list after de-duplication.
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
     * Check whether the list already contains the same decoded message.
     * FT8 / FT4 / Q65 are treated as different modes and never cross-dedupe.
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
     * Initialize the decoder and return the native handle.
     *
     * @param utcTime     current UTC
     * @param sampleRat sample rate, fixed at 12000
     * @param num_samples decoder sample count
     * @param isFt8 true for FT8, false for FT4
     * @return native decoder handle
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * Feed a whole PCM buffer into the decoder.
     *
     * @param buffer  wav PCM data
     * @param decoder native decoder handle
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);

    /**
     * Run synchronous sync-search and return the candidate count.
     *
     * @param decoder native decoder handle
     * @return candidate count
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * Analyze one candidate and write the decoded message fields.
     *
     * @param idx candidate index
     * @param decoder native decoder handle
     * @param ft8Message output message object
     * @return whether analysis completed successfully
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * Release decoder resources.
     *
     * @param decoder native decoder handle
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder); // fetch A91 data for the current message

    public native void setDecodeMode(long decoder, boolean isDeep); // true for deep decode, false for fast decode

    public native boolean DecoderOwnsSessionFlow(long decoder);
    public native void DecoderSetApHints(long decoder, String myCall, String[] hintCallsigns, String[] hintGrids);
    public native void DecoderSetWsjtOptions(long decoder,
                                             int decodePassCount,
                                             int multiDecodeRoundCount,
                                             int qsoFreqSensitivity,
                                             int decodeSensitivity,
                                             boolean enableEarlyDecode,
                                             boolean enableWidebandDxSearch);
    public native long InitBatchDecoder(int sampleRate, int numSamples, int decodeMode);
    public native void ConfigureNativeRuntimeDirectories(String tempDir, String dataDir);
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
                                                   int q65Submode,
                                                   int q65TrPeriodSeconds,
                                                   String myCall,
                                                   String[] hintCallsigns,
                                                   String[] hintGrids);
    // Native only receives a tiny hint set here; the AP logic still lives in the deep fallback path.
}

