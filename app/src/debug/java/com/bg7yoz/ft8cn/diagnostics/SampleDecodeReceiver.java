package com.bg7yoz.ft8cn.diagnostics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener;
import com.bg7yoz.ft8cn.ft8listener.OnFt8Listen;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.wave.WaveFileReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 仅供 debug 构建通过 adb 触发样本解码。
 * 使用方式：
 * adb shell am broadcast -a com.bg7yoz.ft8cn.ft4.DEBUG_DECODE_SAMPLE --es path <wav> --es mode ft8
 */
public class SampleDecodeReceiver extends BroadcastReceiver {
    private static final String TAG = "SampleDecodeReceiver";
    private static final long NATIVE_DECODE_THREAD_STACK_BYTES = 16L * 1024L * 1024L;
    private static final int ENTRY_DECODE_TIMEOUT_FT8_MS = 40000;
    private static final int ENTRY_DECODE_TIMEOUT_FT4_MS = 25000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        new Thread(null, () -> {
            try {
                String path = intent.getStringExtra("path");
                String mode = intent.getStringExtra("mode");
                String myCall = intent.getStringExtra("my_call");
                String engine = intent.getStringExtra("engine");
                if (myCall == null || myCall.trim().isEmpty()) {
                    myCall = "K1JT";
                }
                if (engine == null || engine.trim().isEmpty()) {
                    engine = "listener";
                }

                boolean isFt8 = mode == null || !"ft4".equalsIgnoreCase(mode.trim());
                long utcTime = intent.getLongExtra("utc", 1800000000000L);
                int passCount = intent.getIntExtra("passes", 3);
                int roundCount = intent.getIntExtra("rounds", 3);
                int qsoSensitivity = intent.getIntExtra("qso_sensitivity", 1);
                int decodeSensitivity = intent.getIntExtra("decode_sensitivity", 1);
                boolean earlyDecodeEnabled = intent.getBooleanExtra("early_decode", true);
                boolean wideband = intent.getBooleanExtra("wideband", true);
                boolean deepDecodeEnabled = intent.getBooleanExtra("deep", true);
                int nativeTimeoutMs = intent.getIntExtra("native_timeout_ms", 15000);
                final String finalMyCall = myCall;

                Log.i(TAG, "sample decode begin");
                Log.i(TAG, String.format(Locale.US,
                        "request engine=%s mode=%s path=%s myCall=%s passes=%d rounds=%d deep=%s",
                        engine,
                        isFt8 ? "FT8" : "FT4",
                        path,
                        myCall,
                        passCount,
                        roundCount,
                        deepDecodeEnabled ? "Y" : "N"));
                String inspect = NativeSampleDecode.inspectWavFile(path, isFt8, utcTime);
                if (resultHasText(inspect)) {
                    for (String line : inspect.split("\\n")) {
                        if (!line.trim().isEmpty()) {
                            Log.i(TAG, "[inspect] " + line);
                        }
                    }
                }

                if ("native".equalsIgnoreCase(engine) || "both".equalsIgnoreCase(engine)) {
                    runNativeDecode(path, isFt8, utcTime, finalMyCall, passCount, roundCount,
                            qsoSensitivity, decodeSensitivity, earlyDecodeEnabled, wideband,
                            deepDecodeEnabled, nativeTimeoutMs);
                }

                if ("listener".equalsIgnoreCase(engine) || "both".equalsIgnoreCase(engine)) {
                    runDirectListenerDecode(context, path, isFt8, utcTime, myCall,
                            passCount, roundCount, qsoSensitivity, decodeSensitivity,
                            earlyDecodeEnabled, wideband, deepDecodeEnabled);
                }

                if ("entry".equalsIgnoreCase(engine) || "all".equalsIgnoreCase(engine)) {
                    runEntryListenerDecode(context, path, isFt8, myCall,
                            passCount, roundCount, qsoSensitivity, decodeSensitivity,
                            earlyDecodeEnabled, wideband, deepDecodeEnabled);
                }

                Log.i(TAG, "sample decode end");
            } catch (Throwable throwable) {
                Log.e(TAG, "sample decode failed", throwable);
            } finally {
                pendingResult.finish();
            }
        }, "sample-decode-debug", NATIVE_DECODE_THREAD_STACK_BYTES).start();
    }

    private static void runNativeDecode(String path,
                                        boolean isFt8,
                                        long utcTime,
                                        String myCall,
                                        int passCount,
                                        int roundCount,
                                        int qsoSensitivity,
                                        int decodeSensitivity,
                                        boolean earlyDecodeEnabled,
                                        boolean wideband,
                                        boolean deepDecodeEnabled,
                                        int nativeTimeoutMs) throws InterruptedException {
        Log.i(TAG, String.format(Locale.US, "[native] begin timeoutMs=%d", nativeTimeoutMs));
        CountDownLatch nativeLatch = new CountDownLatch(1);
        final String[] nativeResult = new String[1];
        final Throwable[] nativeError = new Throwable[1];
        new Thread(null, () -> {
            try {
                nativeResult[0] = NativeSampleDecode.decodeWavFile(
                        path,
                        isFt8,
                        utcTime,
                        myCall,
                        passCount,
                        roundCount,
                        qsoSensitivity,
                        decodeSensitivity,
                        earlyDecodeEnabled,
                        wideband,
                        deepDecodeEnabled
                );
            } catch (Throwable throwable) {
                nativeError[0] = throwable;
            } finally {
                nativeLatch.countDown();
            }
        }, "sample-decode-native", NATIVE_DECODE_THREAD_STACK_BYTES).start();

        if (!nativeLatch.await(nativeTimeoutMs, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, String.format(Locale.US, "[native] timeout after %d ms", nativeTimeoutMs));
            return;
        }
        if (nativeError[0] != null) {
            Log.e(TAG, "[native] failed", nativeError[0]);
            return;
        }
        if (!resultHasText(nativeResult[0])) {
            Log.w(TAG, "[native] returned empty result");
            return;
        }
        for (String line : nativeResult[0].split("\\n")) {
            if (!line.trim().isEmpty()) {
                Log.i(TAG, "[native] " + line);
            }
        }
    }

    private static void runDirectListenerDecode(Context context,
                                                String path,
                                                boolean isFt8,
                                                long utcTime,
                                                String myCall,
                                                int passCount,
                                                int roundCount,
                                                int qsoSensitivity,
                                                int decodeSensitivity,
                                                boolean earlyDecodeEnabled,
                                                boolean wideband,
                                                boolean deepDecodeEnabled) throws InterruptedException {
        SampleAudio sampleAudio = loadSampleAudio(path, "[listener]");
        if (sampleAudio == null) {
            return;
        }

        applyDecoderConfig(context, myCall, isFt8, passCount, roundCount,
                qsoSensitivity, decodeSensitivity, earlyDecodeEnabled, wideband, deepDecodeEnabled);

        CountDownLatch finishedLatch = new CountDownLatch(1);
        LinkedHashMap<String, Ft8Message> collected = new LinkedHashMap<>();
        final long[] decodeDurationMs = new long[]{-1L};

        DatabaseOpr databaseOpr = DatabaseOpr.getInstance(context.getApplicationContext(), "data.db");
        FT8SignalListener signalListener = new FT8SignalListener(databaseOpr, buildCollector("[listener]",
                isFt8 ? FT8Common.FT8_MODE : FT8Common.FT4_MODE, collected, decodeDurationMs, finishedLatch));

        try {
            signalListener.decodeFt8(
                    utcTime,
                    sampleAudio.voice,
                    sampleAudio.sampleRate,
                    isFt8 ? FT8Common.FT8_MODE : FT8Common.FT4_MODE
            );

            boolean finished = finishedLatch.await(20, TimeUnit.SECONDS);
            logCollectedSummary("[listener]", path, sampleAudio.sampleRate, sampleAudio.voice.length,
                    earlyDecodeEnabled, deepDecodeEnabled, finished, decodeDurationMs[0], collected);
        } finally {
            signalListener.release();
        }
    }

    private static void runEntryListenerDecode(Context context,
                                               String path,
                                               boolean isFt8,
                                               String myCall,
                                               int passCount,
                                               int roundCount,
                                               int qsoSensitivity,
                                               int decodeSensitivity,
                                               boolean earlyDecodeEnabled,
                                               boolean wideband,
                                               boolean deepDecodeEnabled) throws InterruptedException {
        SampleAudio sampleAudio = loadSampleAudio(path, "[entry]");
        if (sampleAudio == null) {
            return;
        }

        final int decodeMode = isFt8 ? FT8Common.FT8_MODE : FT8Common.FT4_MODE;
        applyDecoderConfig(context, myCall, isFt8, passCount, roundCount,
                qsoSensitivity, decodeSensitivity, earlyDecodeEnabled, wideband, deepDecodeEnabled);

        CountDownLatch finishedLatch = new CountDownLatch(1);
        LinkedHashMap<String, Ft8Message> collected = new LinkedHashMap<>();
        final long[] decodeDurationMs = new long[]{-1L};

        DatabaseOpr databaseOpr = DatabaseOpr.getInstance(context.getApplicationContext(), "data.db");
        FT8SignalListener signalListener = new FT8SignalListener(databaseOpr, buildCollector(
                "[entry]", decodeMode, collected, decodeDurationMs, finishedLatch));

        signalListener.setOnWaveDataListener(new FT8SignalListener.OnWaveDataListener() {
            @Override
            public void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone) {
                int sampleCount = Math.max(1, duration * sampleAudio.sampleRate / 1000);
                int copyLength = Math.min(sampleCount, sampleAudio.voice.length);
                float[] chunk = new float[copyLength];
                System.arraycopy(sampleAudio.voice, 0, chunk, 0, copyLength);
                new Thread(() -> getVoiceDataDone.onGetDone(chunk),
                        "sample-entry-chunk-" + duration).start();
            }

            @Override
            public int getCurrentSampleRate() {
                return sampleAudio.sampleRate;
            }
        });

        try {
            signalListener.startListen();
            boolean finished = finishedLatch.await(
                    isFt8 ? ENTRY_DECODE_TIMEOUT_FT8_MS : ENTRY_DECODE_TIMEOUT_FT4_MS,
                    TimeUnit.MILLISECONDS
            );
            signalListener.stopListen();
            logCollectedSummary("[entry]", path, sampleAudio.sampleRate, sampleAudio.voice.length,
                    earlyDecodeEnabled, deepDecodeEnabled, finished, decodeDurationMs[0], collected);
        } finally {
            signalListener.stopListen();
            signalListener.release();
        }
    }

    private static OnFt8Listen buildCollector(String prefix,
                                              int decodeMode,
                                              LinkedHashMap<String, Ft8Message> collected,
                                              long[] decodeDurationMs,
                                              CountDownLatch finishedLatch) {
        return new OnFt8Listen() {
            @Override
            public void beforeListen(long utc) {
                Log.i(TAG, String.format(Locale.US,
                        "%s beforeListen utc=%d mode=%s",
                        prefix,
                        utc,
                        FT8Common.modeToString(decodeMode)));
            }

            @Override
            public void afterDecode(long utc, float timeSec, int sequential,
                                    ArrayList<Ft8Message> messages, boolean isDeep) {
                if (messages == null) {
                    return;
                }
                for (Ft8Message message : messages) {
                    if (message == null || !message.isValid) {
                        continue;
                    }
                    String text = message.getMessageText();
                    if (text == null || text.trim().isEmpty()) {
                        continue;
                    }
                    String key = String.format(Locale.US, "%s|%d",
                            text.trim(),
                            Math.round(message.freq_hz));
                    Ft8Message existing = collected.get(key);
                    if (existing == null) {
                        collected.put(key, new Ft8Message(message));
                    } else {
                        existing.mergeDecodeQualityFrom(message);
                    }
                }
                Log.i(TAG, String.format(Locale.US,
                        "%s stage=%s got=%d total=%d utc=%d",
                        prefix,
                        isDeep ? "deep" : "fast",
                        messages.size(),
                        collected.size(),
                        utc));
            }

            @Override
            public void afterDecodeFinished(long utc, long decodeDuration) {
                decodeDurationMs[0] = decodeDuration;
                finishedLatch.countDown();
            }
        };
    }

    private static void logCollectedSummary(String prefix,
                                            String path,
                                            int sampleRate,
                                            int sampleCount,
                                            boolean earlyDecodeEnabled,
                                            boolean deepDecodeEnabled,
                                            boolean finished,
                                            long decodeDurationMs,
                                            LinkedHashMap<String, Ft8Message> collected) {
        Log.i(TAG, String.format(Locale.US,
                "%s wav=%s sampleRate=%d samples=%d early=%s deep=%s finished=%s decodeMs=%d unique=%d",
                prefix,
                path,
                sampleRate,
                sampleCount,
                earlyDecodeEnabled ? "Y" : "N",
                deepDecodeEnabled ? "Y" : "N",
                finished ? "yes" : "no",
                decodeDurationMs,
                collected.size()));

        int index = 0;
        for (Map.Entry<String, Ft8Message> entry : collected.entrySet()) {
            Ft8Message message = entry.getValue();
            Log.i(TAG, String.format(Locale.US,
                    "%s #%02d snr=%d dt=%.2f freq=%.1f text=%s",
                    prefix,
                    index,
                    message.snr,
                    message.time_sec,
                    message.freq_hz,
                    message.getMessageText()));
            index++;
        }
    }

    private static void applyDecoderConfig(Context context,
                                           String myCall,
                                           boolean isFt8,
                                           int passCount,
                                           int roundCount,
                                           int qsoSensitivity,
                                           int decodeSensitivity,
                                           boolean earlyDecodeEnabled,
                                           boolean wideband,
                                           boolean deepDecodeEnabled) {
        GeneralVariables.getInstance().setMainContext(context.getApplicationContext());
        GeneralVariables.myCallsign = myCall == null ? "" : myCall.trim().toUpperCase(Locale.US);
        GeneralVariables.deepDecodeMode = deepDecodeEnabled;
        GeneralVariables.signalMode = isFt8 ? FT8Common.FT8_MODE : FT8Common.FT4_MODE;
        GeneralVariables.wsjtxDecodePassCount = passCount;
        GeneralVariables.wsjtxMultiDecodeRoundCount = roundCount;
        GeneralVariables.wsjtxQsoFreqSensitivity = qsoSensitivity;
        GeneralVariables.wsjtxDecodeSensitivity = decodeSensitivity;
        GeneralVariables.wsjtxEnableEarlyDecode = earlyDecodeEnabled;
        GeneralVariables.wsjtxWidebandDxSearch = wideband;
        GeneralVariables.experimentalCodecMode = GeneralVariables.EXP_CODEC_MODE_OFF;
        GeneralVariables.followCallsign.clear();
        GeneralVariables.callsignAndGrids.clear();
    }

    private static SampleAudio loadSampleAudio(String path, String prefix) {
        WaveFileReader reader = new WaveFileReader(path);
        if (!reader.isSuccess()) {
            Log.e(TAG, prefix + " failed to open wav: " + path);
            return null;
        }
        if (reader.getNumChannels() < 1) {
            Log.e(TAG, prefix + " wav has no channel data");
            return null;
        }
        int[] pcm = reader.getData()[0];
        if (pcm == null || pcm.length == 0) {
            Log.e(TAG, prefix + " wav has empty data");
            return null;
        }
        return new SampleAudio(convertPcmToFloat(pcm), (int) reader.getSampleRate());
    }

    private static float[] convertPcmToFloat(int[] pcm) {
        float[] voice = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            voice[i] = ((short) pcm[i]) / 32768.0f;
        }
        return voice;
    }

    private static boolean resultHasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class SampleAudio {
        final float[] voice;
        final int sampleRate;

        SampleAudio(float[] voice, int sampleRate) {
            this.voice = voice;
            this.sampleRate = sampleRate;
        }
    }
}
