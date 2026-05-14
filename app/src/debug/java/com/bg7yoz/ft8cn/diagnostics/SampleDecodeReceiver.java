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

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        new Thread(() -> {
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

                if ("native".equalsIgnoreCase(engine) || "both".equalsIgnoreCase(engine)) {
                    Log.i(TAG, String.format(Locale.US, "[native] begin timeoutMs=%d", nativeTimeoutMs));
                    CountDownLatch nativeLatch = new CountDownLatch(1);
                    final String[] nativeResult = new String[1];
                    final Throwable[] nativeError = new Throwable[1];
                    new Thread(() -> {
                        try {
                            nativeResult[0] = NativeSampleDecode.decodeWavFile(
                                    path,
                                    isFt8,
                                    utcTime,
                                    finalMyCall,
                                    passCount,
                                    roundCount,
                                    qsoSensitivity,
                                    decodeSensitivity,
                                    wideband
                            );
                        } catch (Throwable throwable) {
                            nativeError[0] = throwable;
                        } finally {
                            nativeLatch.countDown();
                        }
                    }, "sample-decode-native").start();

                    if (!nativeLatch.await(nativeTimeoutMs, TimeUnit.MILLISECONDS)) {
                        Log.e(TAG, String.format(Locale.US,
                                "[native] timeout after %d ms", nativeTimeoutMs));
                    } else if (nativeError[0] != null) {
                        Log.e(TAG, "[native] failed", nativeError[0]);
                    } else if (resultHasText(nativeResult[0])) {
                        for (String line : nativeResult[0].split("\\n")) {
                            if (!line.trim().isEmpty()) {
                                Log.i(TAG, "[native] " + line);
                            }
                        }
                    } else {
                        Log.w(TAG, "[native] returned empty result");
                    }
                }

                if ("listener".equalsIgnoreCase(engine) || "both".equalsIgnoreCase(engine)) {
                    runFullListenerDecode(context, path, isFt8, utcTime, myCall,
                            passCount, roundCount, qsoSensitivity, decodeSensitivity, wideband,
                            deepDecodeEnabled);
                }
                Log.i(TAG, "sample decode end");
            } catch (Throwable throwable) {
                Log.e(TAG, "sample decode failed", throwable);
            } finally {
                pendingResult.finish();
            }
        }, "sample-decode-debug").start();
    }

    private static void runFullListenerDecode(Context context,
                                              String path,
                                              boolean isFt8,
                                              long utcTime,
                                              String myCall,
                                              int passCount,
                                              int roundCount,
                                              int qsoSensitivity,
                                              int decodeSensitivity,
                                              boolean wideband,
                                              boolean deepDecodeEnabled) throws InterruptedException {
        WaveFileReader reader = new WaveFileReader(path);
        if (!reader.isSuccess()) {
            Log.e(TAG, "[listener] failed to open wav: " + path);
            return;
        }
        if (reader.getNumChannels() < 1) {
            Log.e(TAG, "[listener] wav has no channel data");
            return;
        }

        int[] pcm = reader.getData()[0];
        if (pcm == null || pcm.length == 0) {
            Log.e(TAG, "[listener] wav has empty data");
            return;
        }

        float[] voice = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            // WaveFileReader 保存的是 16-bit little-endian 原始值，这里显式转回有符号 short。
            voice[i] = ((short) pcm[i]) / 32768.0f;
        }

        GeneralVariables.getInstance().setMainContext(context.getApplicationContext());
        GeneralVariables.myCallsign = myCall == null ? "" : myCall.trim().toUpperCase(Locale.US);
        GeneralVariables.deepDecodeMode = deepDecodeEnabled;
        GeneralVariables.signalMode = isFt8 ? FT8Common.FT8_MODE : FT8Common.FT4_MODE;
        GeneralVariables.wsjtxDecodePassCount = passCount;
        GeneralVariables.wsjtxMultiDecodeRoundCount = roundCount;
        GeneralVariables.wsjtxQsoFreqSensitivity = qsoSensitivity;
        GeneralVariables.wsjtxDecodeSensitivity = decodeSensitivity;
        GeneralVariables.wsjtxEnableEarlyDecode = false;
        GeneralVariables.wsjtxWidebandDxSearch = wideband;
        GeneralVariables.experimentalCodecMode = GeneralVariables.EXP_CODEC_MODE_OFF;
        GeneralVariables.followCallsign.clear();
        GeneralVariables.callsignAndGrids.clear();

        CountDownLatch finishedLatch = new CountDownLatch(1);
        LinkedHashMap<String, Ft8Message> collected = new LinkedHashMap<>();
        final long[] decodeDurationMs = new long[]{-1L};

        DatabaseOpr databaseOpr = DatabaseOpr.getInstance(context.getApplicationContext(), "data.db");
        FT8SignalListener signalListener = new FT8SignalListener(databaseOpr, new OnFt8Listen() {
            @Override
            public void beforeListen(long utc) {
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
                        "[listener] stage=%s got=%d total=%d",
                        isDeep ? "deep" : "fast",
                        messages.size(),
                        collected.size()));
            }

            @Override
            public void afterDecodeFinished(long utc, long decodeDuration) {
                decodeDurationMs[0] = decodeDuration;
                finishedLatch.countDown();
            }
        });

        try {
            signalListener.decodeFt8(
                    utcTime,
                    voice,
                    (int) reader.getSampleRate(),
                    isFt8 ? FT8Common.FT8_MODE : FT8Common.FT4_MODE
            );

            boolean finished = finishedLatch.await(20, TimeUnit.SECONDS);
            Log.i(TAG, String.format(Locale.US,
                    "[listener] wav=%s sampleRate=%d samples=%d deep=%s finished=%s decodeMs=%d unique=%d",
                    path,
                    reader.getSampleRate(),
                    voice.length,
                    deepDecodeEnabled ? "Y" : "N",
                    finished ? "yes" : "no",
                    decodeDurationMs[0],
                    collected.size()));

            int index = 0;
            for (Map.Entry<String, Ft8Message> entry : collected.entrySet()) {
                Ft8Message message = entry.getValue();
                Log.i(TAG, String.format(Locale.US,
                        "[listener] #%02d snr=%d dt=%.2f freq=%.1f text=%s",
                        index,
                        message.snr,
                        message.time_sec,
                        message.freq_hz,
                        message.getMessageText()));
                index++;
            }
        } finally {
            signalListener.release();
        }
    }

    private static boolean resultHasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
