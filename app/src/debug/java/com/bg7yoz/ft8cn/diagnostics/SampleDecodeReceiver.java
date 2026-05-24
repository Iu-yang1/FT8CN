package com.bg7yoz.ft8cn.diagnostics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener;
import com.bg7yoz.ft8cn.ft8listener.OnFt8Listen;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFTx;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.wave.WaveFileReader;
import com.bg7yoz.ft8cn.wave.WriteWavHeader;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
    static final long DECODE_THREAD_STACK_BYTES = 16L * 1024L * 1024L;
    private static final int ENTRY_DECODE_TIMEOUT_FT8_MS = 40000;
    private static final int ENTRY_DECODE_TIMEOUT_FT4_MS = 25000;
    private static final float DEFAULT_GENERATED_BASE_FREQUENCY_HZ = 1500.0f;
    private static final int DEFAULT_GENERATED_SAMPLE_RATE = 12000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        try {
            ContextCompat.startForegroundService(
                    context.getApplicationContext(),
                    SampleDecodeForegroundService.buildStartIntent(context.getApplicationContext(), intent)
            );
            Log.i(TAG, "sample decode request forwarded to foreground service");
        } catch (Throwable throwable) {
            Log.e(TAG, "failed to start sample decode foreground service", throwable);
        }
    }

    static void runDecodeRequest(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        try {
            String path = intent.getStringExtra("path");
            String mode = intent.getStringExtra("mode");
            String messageText = normalizeMessageExtra(intent.getStringExtra("message"));
            String myCall = intent.getStringExtra("my_call");
            String engine = intent.getStringExtra("engine");
            if (myCall == null || myCall.trim().isEmpty()) {
                myCall = "K1JT";
            }
            if (engine == null || engine.trim().isEmpty()) {
                engine = "listener";
            }

            int decodeMode = parseDecodeMode(mode);
            int q65Submode = intent.getIntExtra("q65_submode", FT8Common.Q65_SUBMODE_A);
            int q65TrPeriodSeconds = intent.getIntExtra("q65_tr_period", FT8Common.Q65_DEFAULT_TR_PERIOD_SECONDS);
            long utcTime = intent.getLongExtra("utc", 1800000000000L);
            int passCount = intent.getIntExtra("passes", 3);
            int roundCount = intent.getIntExtra("rounds", 3);
            int qsoSensitivity = intent.getIntExtra("qso_sensitivity", 1);
            int decodeSensitivity = intent.getIntExtra("decode_sensitivity", 1);
            boolean earlyDecodeEnabled = intent.getBooleanExtra(
                    "early_decode",
                    FT8Common.supportsEarlyDecodeStage(decodeMode)
            );
            boolean wideband = intent.getBooleanExtra("wideband", true);
            boolean deepDecodeEnabled = intent.getBooleanExtra("deep", true);
            int nativeTimeoutMs = intent.getIntExtra("native_timeout_ms", 15000);
            float baseFrequencyHz = intent.getFloatExtra("base_frequency_hz", DEFAULT_GENERATED_BASE_FREQUENCY_HZ);
            int generatedSampleRate = intent.getIntExtra("sample_rate", DEFAULT_GENERATED_SAMPLE_RATE);
            final String finalMyCall = myCall;

            applyDecoderConfig(context, myCall, decodeMode, passCount, roundCount,
                    qsoSensitivity, decodeSensitivity, earlyDecodeEnabled, wideband,
                    deepDecodeEnabled, q65Submode, q65TrPeriodSeconds);
            path = resolveSamplePath(context, path, messageText, decodeMode, baseFrequencyHz, generatedSampleRate);
            String stagedPath = stageSampleForNative(context, path);
            configureNativeRuntime(context);

            Log.i(TAG, "sample decode begin");
            Log.i(TAG, String.format(Locale.US,
                    "request engine=%s mode=%s q65Submode=%s q65TrPeriod=%d path=%s stagedPath=%s myCall=%s passes=%d rounds=%d deep=%s",
                    engine,
                    FT8Common.modeToString(decodeMode),
                    FT8Common.getQ65SubmodeLabel(q65Submode),
                    q65TrPeriodSeconds,
                    path,
                    stagedPath,
                    myCall,
                    passCount,
                    roundCount,
                    deepDecodeEnabled ? "Y" : "N"));
            String inspect = NativeSampleDecode.inspectWavFile(stagedPath, decodeMode, utcTime);
            if (resultHasText(inspect)) {
                for (String line : inspect.split("\\n")) {
                    if (!line.trim().isEmpty()) {
                        Log.i(TAG, "[inspect] " + line);
                    }
                }
            }

            if ("native".equalsIgnoreCase(engine) || "both".equalsIgnoreCase(engine)) {
                runNativeDecode(stagedPath, decodeMode, utcTime, finalMyCall, passCount, roundCount,
                        qsoSensitivity, decodeSensitivity, earlyDecodeEnabled, wideband,
                        deepDecodeEnabled, q65Submode, q65TrPeriodSeconds, nativeTimeoutMs);
            }

            if ("listener".equalsIgnoreCase(engine) || "both".equalsIgnoreCase(engine)) {
                runDirectListenerDecode(context, path, decodeMode, utcTime, myCall,
                        passCount, roundCount, qsoSensitivity, decodeSensitivity,
                        earlyDecodeEnabled, wideband, deepDecodeEnabled,
                        q65Submode, q65TrPeriodSeconds);
            }

            if ("entry".equalsIgnoreCase(engine) || "all".equalsIgnoreCase(engine)) {
                runEntryListenerDecode(context, path, decodeMode, myCall,
                        passCount, roundCount, qsoSensitivity, decodeSensitivity,
                        earlyDecodeEnabled, wideband, deepDecodeEnabled,
                        q65Submode, q65TrPeriodSeconds);
            }

            Log.i(TAG, "sample decode end");
        } catch (Throwable throwable) {
            Log.e(TAG, "sample decode failed", throwable);
        }
    }

    private static void runNativeDecode(String path,
                                        int decodeMode,
                                        long utcTime,
                                        String myCall,
                                        int passCount,
                                        int roundCount,
                                        int qsoSensitivity,
                                        int decodeSensitivity,
                                        boolean earlyDecodeEnabled,
                                        boolean wideband,
                                        boolean deepDecodeEnabled,
                                        int q65Submode,
                                        int q65TrPeriodSeconds,
                                        int nativeTimeoutMs) throws InterruptedException {
        Log.i(TAG, String.format(Locale.US, "[native] begin timeoutMs=%d", nativeTimeoutMs));
        CountDownLatch nativeLatch = new CountDownLatch(1);
        final String[] nativeResult = new String[1];
        final Throwable[] nativeError = new Throwable[1];
        new Thread(null, () -> {
            try {
                nativeResult[0] = NativeSampleDecode.decodeWavFile(
                        path,
                        decodeMode,
                        utcTime,
                        myCall,
                        passCount,
                        roundCount,
                        qsoSensitivity,
                        decodeSensitivity,
                        earlyDecodeEnabled,
                        wideband,
                        deepDecodeEnabled,
                        q65Submode,
                        q65TrPeriodSeconds
                );
            } catch (Throwable throwable) {
                nativeError[0] = throwable;
            } finally {
                nativeLatch.countDown();
            }
        }, "sample-decode-native", DECODE_THREAD_STACK_BYTES).start();

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

    private static void configureNativeRuntime(Context context) {
        File tempDir = new File(context.getCacheDir(), "wsjtx3");
        File dataDir = new File(context.getFilesDir(), "wsjtx3");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            Log.w(TAG, "native runtime temp dir mkdirs failed: " + tempDir.getAbsolutePath());
        }
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            Log.w(TAG, "native runtime data dir mkdirs failed: " + dataDir.getAbsolutePath());
        }
        NativeSampleDecode.configureRuntimeDirectories(
                tempDir.getAbsolutePath(),
                dataDir.getAbsolutePath()
        );
        Log.i(TAG, String.format(Locale.US,
                "native runtime dirs temp=%s data=%s",
                tempDir.getAbsolutePath(),
                dataDir.getAbsolutePath()));
    }

    private static void runDirectListenerDecode(Context context,
                                                String path,
                                                int decodeMode,
                                                long utcTime,
                                                String myCall,
                                                int passCount,
                                                int roundCount,
                                                int qsoSensitivity,
                                                int decodeSensitivity,
                                                boolean earlyDecodeEnabled,
                                                boolean wideband,
                                                boolean deepDecodeEnabled,
                                                int q65Submode,
                                                int q65TrPeriodSeconds) throws InterruptedException {
        SampleAudio sampleAudio = loadSampleAudio(path, "[listener]");
        if (sampleAudio == null) {
            return;
        }

        applyDecoderConfig(context, myCall, decodeMode, passCount, roundCount,
                qsoSensitivity, decodeSensitivity, earlyDecodeEnabled, wideband,
                deepDecodeEnabled, q65Submode, q65TrPeriodSeconds);

        CountDownLatch finishedLatch = new CountDownLatch(1);
        LinkedHashMap<String, Ft8Message> collected = new LinkedHashMap<>();
        final long[] decodeDurationMs = new long[]{-1L};

        DatabaseOpr databaseOpr = DatabaseOpr.getInstance(context.getApplicationContext(), "data.db");
        FT8SignalListener signalListener = new FT8SignalListener(databaseOpr, buildCollector("[listener]",
                decodeMode, collected, decodeDurationMs, finishedLatch));

        try {
            signalListener.decodeFt8(
                    utcTime,
                    sampleAudio.voice,
                    sampleAudio.sampleRate,
                    decodeMode
            );

            boolean finished = finishedLatch.await(
                    getDirectListenerDecodeTimeoutMs(decodeMode),
                    TimeUnit.MILLISECONDS
            );
            logCollectedSummary("[listener]", path, sampleAudio.sampleRate, sampleAudio.voice.length,
                    earlyDecodeEnabled, deepDecodeEnabled, finished, decodeDurationMs[0], collected);
        } finally {
            signalListener.release();
        }
    }

    private static void runEntryListenerDecode(Context context,
                                               String path,
                                               int decodeMode,
                                               String myCall,
                                               int passCount,
                                               int roundCount,
                                               int qsoSensitivity,
                                               int decodeSensitivity,
                                               boolean earlyDecodeEnabled,
                                               boolean wideband,
                                               boolean deepDecodeEnabled,
                                               int q65Submode,
                                               int q65TrPeriodSeconds) throws InterruptedException {
        SampleAudio sampleAudio = loadSampleAudio(path, "[entry]");
        if (sampleAudio == null) {
            return;
        }

        applyDecoderConfig(context, myCall, decodeMode, passCount, roundCount,
                qsoSensitivity, decodeSensitivity, earlyDecodeEnabled, wideband,
                deepDecodeEnabled, q65Submode, q65TrPeriodSeconds);

        CountDownLatch finishedLatch = new CountDownLatch(1);
        LinkedHashMap<String, Ft8Message> collected = new LinkedHashMap<>();
        final long[] decodeDurationMs = new long[]{-1L};

        DatabaseOpr databaseOpr = DatabaseOpr.getInstance(context.getApplicationContext(), "data.db");
        FT8SignalListener signalListener = new FT8SignalListener(databaseOpr, buildCollector(
                "[entry]", decodeMode, collected, decodeDurationMs, finishedLatch));

        signalListener.setOnWaveDataListener(new FT8SignalListener.OnWaveDataListener() {
            @Override
            public void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone) {
                int sampleCount = resolveSampleCount(duration, sampleAudio.sampleRate);
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
                    getEntryDecodeTimeoutMs(decodeMode),
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
                                           int decodeMode,
                                           int passCount,
                                           int roundCount,
                                           int qsoSensitivity,
                                           int decodeSensitivity,
                                           boolean earlyDecodeEnabled,
                                           boolean wideband,
                                           boolean deepDecodeEnabled,
                                           int q65Submode,
                                           int q65TrPeriodSeconds) {
        GeneralVariables.getInstance().setMainContext(context.getApplicationContext());
        GeneralVariables.myCallsign = myCall == null ? "" : myCall.trim().toUpperCase(Locale.US);
        GeneralVariables.deepDecodeMode = deepDecodeEnabled;
        GeneralVariables.signalMode = decodeMode;
        GeneralVariables.setQ65Configuration(q65Submode, q65TrPeriodSeconds);
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

    private static String resolveSamplePath(Context context,
                                            String path,
                                            String messageText,
                                            int decodeMode,
                                            float baseFrequencyHz,
                                            int sampleRate) throws IOException {
        if (path != null && !path.trim().isEmpty()) {
            return path;
        }
        if (messageText == null || messageText.trim().isEmpty()) {
            return path;
        }
        return generateSampleWav(context, decodeMode, messageText.trim(), baseFrequencyHz, sampleRate);
    }

    private static String generateSampleWav(Context context,
                                            int decodeMode,
                                            String messageText,
                                            float baseFrequencyHz,
                                            int sampleRate) throws IOException {
        Ft8Message message = new Ft8Message(decodeMode);
        message.setTransmitRawText(messageText);
        message.setSignalFormat(decodeMode);

        float[] wave = GenerateFTx.generateFtX(
                message,
                baseFrequencyHz,
                sampleRate,
                decodeMode
        );
        if (wave == null || wave.length == 0) {
            throw new IOException("generated wave is empty");
        }
        wave = padWaveToSlotDuration(wave, decodeMode, sampleRate);

        File dir = new File(context.getCacheDir(), "sample_decode/generated");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdirs failed: " + dir.getAbsolutePath());
        }

        String fileName = String.format(Locale.US,
                "%s_%s_%ds_%d.wav",
                FT8Common.modeToString(decodeMode).replace('/', '_'),
                FT8Common.getQ65SubmodeLabel(GeneralVariables.getQ65Submode()),
                GeneralVariables.getQ65TrPeriodSeconds(),
                sampleRate);
        File target = new File(dir, fileName);
        writeFloatWaveFile(target, wave, sampleRate);
        Log.i(TAG, String.format(Locale.US,
                "generated sample wav mode=%s q65Submode=%s q65TrPeriod=%d sampleRate=%d freq=%.1f path=%s text=%s",
                FT8Common.modeToString(decodeMode),
                FT8Common.getQ65SubmodeLabel(GeneralVariables.getQ65Submode()),
                GeneralVariables.getQ65TrPeriodSeconds(),
                sampleRate,
                baseFrequencyHz,
                target.getAbsolutePath(),
                messageText));
        return target.getAbsolutePath();
    }

    private static void writeFloatWaveFile(File file, float[] samples, int sampleRate) throws IOException {
        int pcmBytes = samples.length * 2;
        try (DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(file, false))) {
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

    private static float[] padWaveToSlotDuration(float[] wave, int decodeMode, int sampleRate) {
        if (wave == null || wave.length == 0 || sampleRate <= 0) {
            return wave;
        }
        int targetSamples = Math.max(1,
                (int) Math.round(FT8Common.getSlotTimeSecond(decodeMode) * sampleRate));
        if (wave.length >= targetSamples) {
            return wave;
        }
        float[] padded = new float[targetSamples];
        System.arraycopy(wave, 0, padded, 0, wave.length);
        Log.i(TAG, String.format(Locale.US,
                "pad generated wave to slot mode=%s sampleRate=%d sourceSamples=%d paddedSamples=%d",
                FT8Common.modeToString(decodeMode),
                sampleRate,
                wave.length,
                targetSamples));
        return padded;
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

    private static String stageSampleForNative(Context context, String originalPath) throws IOException {
        if (context == null || originalPath == null || originalPath.trim().isEmpty()) {
            return originalPath;
        }

        File source = new File(originalPath);
        if (!source.exists() || !source.isFile()) {
            return originalPath;
        }

        File dir = new File(context.getCacheDir(), "sample_decode");
        if (!dir.exists() && !dir.mkdirs()) {
            return originalPath;
        }

        File target = new File(dir, source.getName());
        copyFile(source, target);
        return target.getAbsolutePath();
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        }
    }

    private static int parseDecodeMode(String modeText) {
        if (modeText == null) {
            return FT8Common.FT8_MODE;
        }
        String normalized = modeText.trim().toLowerCase(Locale.US);
        if ("ft4".equals(normalized)) {
            return FT8Common.FT4_MODE;
        }
        if ("q65".equals(normalized)) {
            return FT8Common.Q65_MODE;
        }
        return FT8Common.FT8_MODE;
    }

    private static int resolveSampleCount(int durationMs, int sampleRate) {
        long requested = (long) Math.max(0, durationMs) * (long) Math.max(1, sampleRate) / 1000L;
        if (requested <= 0L) {
            return 1;
        }
        if (requested > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) requested;
    }

    private static String normalizeMessageExtra(String value) {
        if (value == null) {
            return null;
        }
        return value.replace('_', ' ').trim();
    }

    private static int getEntryDecodeTimeoutMs(int decodeMode) {
        if (decodeMode == FT8Common.FT4_MODE) {
            return ENTRY_DECODE_TIMEOUT_FT4_MS;
        }
        if (decodeMode == FT8Common.Q65_MODE) {
            return 90000;
        }
        return ENTRY_DECODE_TIMEOUT_FT8_MS;
    }

    private static int getDirectListenerDecodeTimeoutMs(int decodeMode) {
        if (decodeMode != FT8Common.Q65_MODE) {
            return 20000;
        }
        int slotMs = FT8Common.getSlotTimeM(decodeMode);
        long timeoutMs = Math.max(30000L, Math.round(slotMs / 4.0) + 15000L);
        if (timeoutMs > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) timeoutMs;
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
