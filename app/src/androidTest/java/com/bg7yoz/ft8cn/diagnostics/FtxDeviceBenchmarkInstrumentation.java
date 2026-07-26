package com.bg7yoz.ft8cn.diagnostics;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.wave.FT8Resample;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅由测试 APK 内部调用的 native 解码、CPU 和内存基准入口。 */
@RunWith(AndroidJUnit4.class)
public final class FtxDeviceBenchmarkInstrumentation {
    private static final int TARGET_SAMPLE_RATE = 12000;
    private static final long DECODE_STACK_BYTES = 24L * 1024L * 1024L;
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "^#\\d+\\s+snr=(-?\\d+)\\s+dt=([-+\\d.]+)\\s+freq=([-+\\d.]+)"
                    + "\\s+score=(-?\\d+)\\s+text=(.*\\S)\\s*$");
    private Instrumentation instrumentation;
    private Context targetContext;
    private Context testContext;

    @Test
    public void runDeviceBenchmark() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        targetContext = instrumentation.getTargetContext();
        testContext = instrumentation.getContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        Intent keepAliveIntent = DeviceBenchmarkKeepAliveService.buildStartIntent(targetContext);
        Activity benchmarkActivity = null;
        boolean keepAliveStarted = false;
        try {
            benchmarkActivity = instrumentation.startActivitySync(
                    DeviceBenchmarkActivity.buildStartIntent(targetContext));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                targetContext.startForegroundService(keepAliveIntent);
            } else {
                targetContext.startService(keepAliveIntent);
            }
            keepAliveStarted = true;
            JSONObject report = runBenchmark(arguments);
            byte[] json = report.toString().getBytes(StandardCharsets.UTF_8);
            File reportDirectory = targetContext.getExternalFilesDir(null);
            if (reportDirectory == null) {
                throw new IllegalStateException("target external files directory is unavailable");
            }
            requireDirectory(reportDirectory);
            File reportFile = new File(reportDirectory,
                    "ft8cn-device-benchmark-" + report.optString("build_variant", "unknown") + ".json");
            try (FileOutputStream output = new FileOutputStream(reportFile, false)) {
                output.write(json);
            }
            Bundle status = new Bundle();
            status.putString("report_path", reportFile.getAbsolutePath());
            status.putString("stream", "FT8CN_DEVICE_BENCHMARK=PASS\n");
            instrumentation.sendStatus(2, status);
        } finally {
            if (keepAliveStarted) {
                targetContext.stopService(keepAliveIntent);
            }
            if (benchmarkActivity != null) {
                Activity activityToFinish = benchmarkActivity;
                instrumentation.runOnMainSync(activityToFinish::finishAndRemoveTask);
            }
        }
    }

    private JSONObject runBenchmark(Bundle arguments) throws Exception {
        int warmups = Math.max(1, parseInt(arguments.getString("warmups"), 1));
        int iterations = Math.max(10, parseInt(arguments.getString("iterations"), 10));
        String buildVariant = valueOrDefault(arguments.getString("build_variant"), "unknown");
        String caseFilter = valueOrDefault(arguments.getString("case_filter"), "ALL")
                .toUpperCase(Locale.US);

        File tempDir = new File(targetContext.getCacheDir(), "wsjtx3-benchmark");
        File dataDir = new File(targetContext.getFilesDir(), "wsjtx3-benchmark");
        requireDirectory(tempDir);
        requireDirectory(dataDir);
        NativeSampleDecode.configureRuntimeDirectories(tempDir.getAbsolutePath(), dataDir.getAbsolutePath());

        List<BenchmarkCase> cases = Arrays.asList(
                new BenchmarkCase("FT8", "corpus/ft8.wav", FT8Common.FT8_MODE,
                        parseInt(arguments.getString("ft8_expected"), 20),
                        FT8Common.Q65_SUBMODE_A, 60, 48_870_000L),
                new BenchmarkCase("FT4", "corpus/ft4.wav", FT8Common.FT4_MODE,
                        parseInt(arguments.getString("ft4_expected"), 16),
                        FT8Common.Q65_SUBMODE_A, 60, 2000L),
                new BenchmarkCase("Q65A-60", "corpus/q65.wav", FT8Common.Q65_MODE,
                        parseInt(arguments.getString("q65_expected"), 4),
                        FT8Common.Q65_SUBMODE_A, 60, 981_000L)
        );

        JSONObject report = new JSONObject();
        report.put("schema_version", 1);
        report.put("passed", true);
        report.put("build_variant", buildVariant);
        report.put("case_filter", caseFilter);
        report.put("warmup_count", warmups);
        report.put("iteration_count", iterations);
        report.put("device", deviceInfo());

        JSONArray caseReports = new JSONArray();
        for (BenchmarkCase benchmarkCase : cases) {
            if (!"ALL".equals(caseFilter)
                    && !benchmarkCase.name.toUpperCase(Locale.US).startsWith(caseFilter)) {
                continue;
            }
            File assetFile = copyAssetToCache(benchmarkCase.assetPath, benchmarkCase.name + "-source.wav");
            PcmWave source = readPcm16Mono(assetFile);
            if (source.sampleRate != TARGET_SAMPLE_RATE) {
                throw new IllegalStateException(benchmarkCase.name + " source must be 12000 Hz");
            }
            for (int sourceRate : new int[]{12000, 24000, 48000}) {
                PreparedSample prepared = prepareSample(source, sourceRate,
                        new File(tempDir, benchmarkCase.name + "-" + sourceRate + ".wav"));
                caseReports.put(runCase(benchmarkCase, prepared, sourceRate, warmups, iterations));
            }
        }
        report.put("cases", caseReports);
        return report;
    }

    private JSONObject runCase(BenchmarkCase benchmarkCase,
                               PreparedSample prepared,
                               int sourceRate,
                               int warmups,
                               int iterations) throws Exception {
        for (int index = 0; index < warmups; index++) {
            DecodeMeasurement warmup = decodeOnce(benchmarkCase, prepared.wavFile);
            assertDecode(benchmarkCase, warmup);
        }

        JSONArray runs = new JSONArray();
        List<Double> elapsedValues = new ArrayList<>();
        List<Double> processCpuValues = new ArrayList<>();
        List<Double> decodeCpuValues = new ArrayList<>();
        List<Double> cpuUtilizationValues = new ArrayList<>();
        String expectedHash = null;
        long peakJavaHeap = 0L;
        long peakNativeHeap = 0L;
        long peakPss = 0L;
        long peakRss = 0L;
        for (int index = 0; index < iterations; index++) {
            DecodeMeasurement measurement = decodeOnce(benchmarkCase, prepared.wavFile);
            assertDecode(benchmarkCase, measurement);
            if (expectedHash == null) {
                expectedHash = measurement.resultSha256;
            } else if (!expectedHash.equals(measurement.resultSha256)) {
                throw new IllegalStateException(benchmarkCase.name + " unstable result hash");
            }
            elapsedValues.add(measurement.elapsedMs);
            processCpuValues.add(measurement.processCpuMs);
            decodeCpuValues.add(measurement.decodeCpuMs);
            cpuUtilizationValues.add(measurement.cpuUtilizationPercent);
            peakJavaHeap = Math.max(peakJavaHeap, measurement.peakJavaHeapBytes);
            peakNativeHeap = Math.max(peakNativeHeap, measurement.peakNativeHeapBytes);
            peakPss = Math.max(peakPss, measurement.peakPssBytes);
            peakRss = Math.max(peakRss, measurement.peakRssBytes);
            runs.put(measurement.toJson(index));
        }

        JSONObject result = new JSONObject();
        result.put("name", benchmarkCase.name);
        result.put("mode", benchmarkCase.mode);
        result.put("source_sample_rate", sourceRate);
        result.put("decoder_sample_rate", TARGET_SAMPLE_RATE);
        result.put("input_samples", prepared.inputSamples);
        result.put("output_samples", prepared.outputSamples);
        result.put("resample_ms", prepared.resampleMs);
        result.put("resample_max_abs_error", prepared.maxAbsoluteError);
        result.put("result_count", benchmarkCase.expectedCount);
        result.put("result_sha256", expectedHash);
        result.put("p50_ms", percentile(elapsedValues, 0.50));
        result.put("p95_ms", percentile(elapsedValues, 0.95));
        result.put("process_cpu_p50_ms", percentile(processCpuValues, 0.50));
        result.put("process_cpu_p95_ms", percentile(processCpuValues, 0.95));
        result.put("decode_cpu_p50_ms", percentile(decodeCpuValues, 0.50));
        result.put("decode_cpu_p95_ms", percentile(decodeCpuValues, 0.95));
        result.put("cpu_utilization_p50_percent", percentile(cpuUtilizationValues, 0.50));
        result.put("cpu_utilization_p95_percent", percentile(cpuUtilizationValues, 0.95));
        result.put("peak_java_heap_bytes", peakJavaHeap);
        result.put("peak_native_heap_bytes", peakNativeHeap);
        result.put("peak_total_pss_bytes", peakPss);
        result.put("peak_rss_bytes", peakRss);
        result.put("runs", runs);
        return result;
    }

    private DecodeMeasurement decodeOnce(BenchmarkCase benchmarkCase, File wavFile) throws Exception {
        AtomicReference<String> report = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong decodeThreadCpuNanos = new AtomicLong();
        Thread decodeThread = new Thread(null, () -> {
            long cpuStarted = Debug.threadCpuTimeNanos();
            try {
                report.set(NativeSampleDecode.decodeWavFile(
                        wavFile.getAbsolutePath(),
                        benchmarkCase.mode,
                        benchmarkCase.utcTimeMillis,
                        "BG5JSU",
                        3,
                        3,
                        2,
                        2,
                        true,
                        true,
                        true,
                        benchmarkCase.q65Submode,
                        benchmarkCase.q65PeriodSeconds));
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                decodeThreadCpuNanos.set(Math.max(0L,
                        Debug.threadCpuTimeNanos() - cpuStarted));
            }
        }, "ft8cn-native-decode", DECODE_STACK_BYTES);

        long peakJavaHeap = 0L;
        long peakNativeHeap = 0L;
        long peakPss = 0L;
        long peakRss = 0L;
        long benchmarkCpuStarted = Debug.threadCpuTimeNanos();
        long processCpuStarted = android.os.Process.getElapsedCpuTime();
        long started = SystemClock.elapsedRealtimeNanos();
        decodeThread.start();
        while (decodeThread.isAlive()) {
            MemorySnapshot memory = readMemory();
            peakJavaHeap = Math.max(peakJavaHeap, memory.javaHeapBytes);
            peakNativeHeap = Math.max(peakNativeHeap, memory.nativeHeapBytes);
            peakPss = Math.max(peakPss, memory.totalPssBytes);
            peakRss = Math.max(peakRss, memory.rssBytes);
            decodeThread.join(10L);
        }
        double elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0;
        double processCpuMs = Math.max(0L,
                android.os.Process.getElapsedCpuTime() - processCpuStarted);
        double benchmarkCpuMs = Math.max(0L,
                Debug.threadCpuTimeNanos() - benchmarkCpuStarted) / 1_000_000.0;
        double decodeThreadCpuMs = decodeThreadCpuNanos.get() / 1_000_000.0;
        // 扣除基准采样线程自身开销后，剩余值覆盖解码线程及其内部 worker。
        double decodeCpuMs = Math.max(decodeThreadCpuMs, processCpuMs - benchmarkCpuMs);
        double nativeWorkerCpuMs = Math.max(0.0, decodeCpuMs - decodeThreadCpuMs);
        double cpuUtilizationPercent = elapsedMs <= 0.0
                ? 0.0
                : decodeCpuMs * 100.0 / elapsedMs;
        if (failure.get() != null) { throw new RuntimeException(failure.get()); }
        ParsedReport parsed = parseNativeReport(report.get());
        return new DecodeMeasurement(elapsedMs, processCpuMs, benchmarkCpuMs,
                decodeCpuMs, decodeThreadCpuMs, nativeWorkerCpuMs, cpuUtilizationPercent,
                parsed.count, parsed.sha256,
                peakJavaHeap, peakNativeHeap, peakPss, peakRss, report.get());
    }

    private static void assertDecode(BenchmarkCase benchmarkCase, DecodeMeasurement measurement) {
        if (measurement.resultCount != benchmarkCase.expectedCount) {
            throw new IllegalStateException(String.format(Locale.US,
                    "%s count mismatch: expected=%d actual=%d%n%s",
                    benchmarkCase.name, benchmarkCase.expectedCount, measurement.resultCount,
                    measurement.nativeReport));
        }
        if (measurement.resultSha256 == null || measurement.resultSha256.isEmpty()) {
            throw new IllegalStateException(benchmarkCase.name + " empty result hash");
        }
    }

    private PreparedSample prepareSample(PcmWave source, int sourceRate, File outputFile) throws Exception {
        if (sourceRate == TARGET_SAMPLE_RATE) {
            writePcm16Mono(outputFile, source.samples, TARGET_SAMPLE_RATE);
            return new PreparedSample(outputFile, source.samples.length, source.samples.length, 0.0, 0.0);
        }
        int factor = sourceRate / TARGET_SAMPLE_RATE;
        long highRateCount = (long) source.samples.length * factor;
        if (factor != 2 && factor != 4 || highRateCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("unsupported benchmark sample rate: " + sourceRate);
        }
        float[] highRate = new float[(int) highRateCount];
        for (int index = 0; index < source.samples.length; index++) {
            Arrays.fill(highRate, index * factor, index * factor + factor, source.samples[index]);
        }
        long started = SystemClock.elapsedRealtimeNanos();
        float[] downsampled = FT8Resample.get32Resample32(
                highRate, sourceRate, TARGET_SAMPLE_RATE, 1);
        double resampleMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0;
        if (downsampled == null || downsampled.length != source.samples.length) {
            throw new IllegalStateException("resampler length mismatch for " + sourceRate);
        }
        double maxError = 0.0;
        for (int index = 0; index < downsampled.length; index++) {
            maxError = Math.max(maxError, Math.abs(downsampled[index] - source.samples[index]));
        }
        writePcm16Mono(outputFile, downsampled, TARGET_SAMPLE_RATE);
        return new PreparedSample(outputFile, highRate.length, downsampled.length, resampleMs, maxError);
    }

    private ParsedReport parseNativeReport(String nativeReport) throws Exception {
        if (nativeReport == null || nativeReport.contains("failureReason=unsupported")
                || nativeReport.contains("failureReason=wav-load-failed")) {
            throw new IllegalStateException("native sample decode failed: " + nativeReport);
        }
        List<String> normalized = new ArrayList<>();
        for (String line : nativeReport.split("\\r?\\n")) {
            Matcher matcher = RESULT_PATTERN.matcher(line.trim());
            if (!matcher.matches()) { continue; }
            String text = matcher.group(5).trim().replaceAll("\\s+", " ");
            normalized.add(String.format(Locale.US, "%s|freq=%s|dt=%s",
                    text, matcher.group(3), matcher.group(2)));
        }
        Collections.sort(normalized);
        return new ParsedReport(normalized.size(), sha256(String.join("\n", normalized)));
    }

    private MemorySnapshot readMemory() {
        Runtime runtime = Runtime.getRuntime();
        long javaHeap = runtime.totalMemory() - runtime.freeMemory();
        long nativeHeap = Debug.getNativeHeapAllocatedSize();
        Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memoryInfo);
        long pss = (long) memoryInfo.getTotalPss() * 1024L;
        return new MemorySnapshot(javaHeap, nativeHeap, pss, readRssBytes());
    }

    private long readRssBytes() {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String value = line.substring(6).trim().split("\\s+")[0];
                    return Long.parseLong(value) * 1024L;
                }
            }
        } catch (Exception ignored) {
            return -1L;
        }
        return -1L;
    }

    private JSONObject deviceInfo() throws Exception {
        JSONObject result = new JSONObject();
        result.put("manufacturer", Build.MANUFACTURER);
        result.put("model", Build.MODEL);
        result.put("android_release", Build.VERSION.RELEASE);
        result.put("sdk_int", Build.VERSION.SDK_INT);
        result.put("abi", Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]);
        result.put("available_processors", Runtime.getRuntime().availableProcessors());
        result.put("ft8_sync_threads", NativeSampleDecode.getFt8SyncThreadCount());
        return result;
    }

    private File copyAssetToCache(String assetPath, String fileName) throws Exception {
        File destination = new File(targetContext.getCacheDir(), fileName);
        try (InputStream input = new BufferedInputStream(testContext.getAssets().open(assetPath));
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) { output.write(buffer, 0, count); }
            }
        }
        return destination;
    }

    private static PcmWave readPcm16Mono(File file) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (!"RIFF".equals(readFourCc(input)) || input.length() < 44) {
                throw new IllegalArgumentException("invalid RIFF WAV: " + file);
            }
            readUnsignedIntLe(input);
            if (!"WAVE".equals(readFourCc(input))) {
                throw new IllegalArgumentException("invalid WAVE header: " + file);
            }
            int sampleRate = 0;
            int channels = 0;
            int bits = 0;
            long dataOffset = -1;
            long dataBytes = -1;
            while (input.getFilePointer() + 8 <= input.length()) {
                String chunk = readFourCc(input);
                long chunkSize = readUnsignedIntLe(input);
                long next = input.getFilePointer() + chunkSize + (chunkSize & 1L);
                if ("fmt ".equals(chunk)) {
                    int format = readUnsignedShortLe(input);
                    channels = readUnsignedShortLe(input);
                    sampleRate = (int) readUnsignedIntLe(input);
                    input.skipBytes(6);
                    bits = readUnsignedShortLe(input);
                    if (format != 1) { throw new IllegalArgumentException("WAV is not PCM"); }
                } else if ("data".equals(chunk)) {
                    dataOffset = input.getFilePointer();
                    dataBytes = chunkSize;
                }
                input.seek(Math.min(next, input.length()));
            }
            if (sampleRate <= 0 || channels != 1 || bits != 16 || dataOffset < 0) {
                throw new IllegalArgumentException("unsupported WAV format: " + file);
            }
            long count = dataBytes / 2L;
            if (count <= 0 || count > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid WAV sample count: " + count);
            }
            float[] samples = new float[(int) count];
            input.seek(dataOffset);
            for (int index = 0; index < samples.length; index++) {
                int low = input.readUnsignedByte();
                int high = input.readUnsignedByte();
                samples[index] = (short) (low | high << 8) / 32768.0f;
            }
            return new PcmWave(sampleRate, samples);
        }
    }

    private static void writePcm16Mono(File file, float[] samples, int sampleRate) throws Exception {
        long dataBytes = (long) samples.length * 2L;
        if (dataBytes > 0xffffffffL - 36L) { throw new IllegalArgumentException("WAV is too large"); }
        try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
            writeAscii(output, "RIFF");
            writeIntLe(output, (int) (36L + dataBytes));
            writeAscii(output, "WAVEfmt ");
            writeIntLe(output, 16);
            writeShortLe(output, 1);
            writeShortLe(output, 1);
            writeIntLe(output, sampleRate);
            writeIntLe(output, sampleRate * 2);
            writeShortLe(output, 2);
            writeShortLe(output, 16);
            writeAscii(output, "data");
            writeIntLe(output, (int) dataBytes);
            for (float sample : samples) {
                int value = Math.round(Math.max(-1.0f, Math.min(1.0f, sample)) * 32767.0f);
                writeShortLe(output, value);
            }
        }
    }

    private static double percentile(List<Double> values, double percentile) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return Math.round(sorted.get(index) * 1000.0) / 1000.0;
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte item : digest) { output.append(String.format(Locale.US, "%02x", item & 0xff)); }
        return output.toString();
    }

    private static void requireDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("cannot create directory: " + directory);
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String readFourCc(RandomAccessFile input) throws Exception {
        byte[] bytes = new byte[4];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static int readUnsignedShortLe(RandomAccessFile input) throws Exception {
        int low = input.readUnsignedByte();
        return low | input.readUnsignedByte() << 8;
    }

    private static long readUnsignedIntLe(RandomAccessFile input) throws Exception {
        return (long) input.readUnsignedByte()
                | (long) input.readUnsignedByte() << 8
                | (long) input.readUnsignedByte() << 16
                | (long) input.readUnsignedByte() << 24;
    }

    private static void writeAscii(BufferedOutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeIntLe(BufferedOutputStream output, int value) throws Exception {
        output.write(value & 0xff);
        output.write(value >>> 8 & 0xff);
        output.write(value >>> 16 & 0xff);
        output.write(value >>> 24 & 0xff);
    }

    private static void writeShortLe(BufferedOutputStream output, int value) throws Exception {
        output.write(value & 0xff);
        output.write(value >>> 8 & 0xff);
    }

    private static final class BenchmarkCase {
        final String name;
        final String assetPath;
        final int mode;
        final int expectedCount;
        final int q65Submode;
        final int q65PeriodSeconds;
        final long utcTimeMillis;

        BenchmarkCase(String name, String assetPath, int mode, int expectedCount,
                      int q65Submode, int q65PeriodSeconds, long utcTimeMillis) {
            this.name = name;
            this.assetPath = assetPath;
            this.mode = mode;
            this.expectedCount = expectedCount;
            this.q65Submode = q65Submode;
            this.q65PeriodSeconds = q65PeriodSeconds;
            this.utcTimeMillis = utcTimeMillis;
        }
    }

    private static final class PcmWave {
        final int sampleRate;
        final float[] samples;

        PcmWave(int sampleRate, float[] samples) {
            this.sampleRate = sampleRate;
            this.samples = samples;
        }
    }

    private static final class PreparedSample {
        final File wavFile;
        final int inputSamples;
        final int outputSamples;
        final double resampleMs;
        final double maxAbsoluteError;

        PreparedSample(File wavFile, int inputSamples, int outputSamples,
                       double resampleMs, double maxAbsoluteError) {
            this.wavFile = wavFile;
            this.inputSamples = inputSamples;
            this.outputSamples = outputSamples;
            this.resampleMs = resampleMs;
            this.maxAbsoluteError = maxAbsoluteError;
        }
    }

    private static final class ParsedReport {
        final int count;
        final String sha256;

        ParsedReport(int count, String sha256) {
            this.count = count;
            this.sha256 = sha256;
        }
    }

    private static final class MemorySnapshot {
        final long javaHeapBytes;
        final long nativeHeapBytes;
        final long totalPssBytes;
        final long rssBytes;

        MemorySnapshot(long javaHeapBytes, long nativeHeapBytes, long totalPssBytes, long rssBytes) {
            this.javaHeapBytes = javaHeapBytes;
            this.nativeHeapBytes = nativeHeapBytes;
            this.totalPssBytes = totalPssBytes;
            this.rssBytes = rssBytes;
        }
    }

    private static final class DecodeMeasurement {
        final double elapsedMs;
        final double processCpuMs;
        final double benchmarkCpuMs;
        final double decodeCpuMs;
        final double decodeThreadCpuMs;
        final double nativeWorkerCpuMs;
        final double cpuUtilizationPercent;
        final int resultCount;
        final String resultSha256;
        final long peakJavaHeapBytes;
        final long peakNativeHeapBytes;
        final long peakPssBytes;
        final long peakRssBytes;
        final String nativeReport;

        DecodeMeasurement(double elapsedMs, double processCpuMs, double benchmarkCpuMs,
                           double decodeCpuMs, double decodeThreadCpuMs,
                           double nativeWorkerCpuMs, double cpuUtilizationPercent,
                           int resultCount, String resultSha256,
                           long peakJavaHeapBytes, long peakNativeHeapBytes,
                           long peakPssBytes, long peakRssBytes, String nativeReport) {
            this.elapsedMs = elapsedMs;
            this.processCpuMs = processCpuMs;
            this.benchmarkCpuMs = benchmarkCpuMs;
            this.decodeCpuMs = decodeCpuMs;
            this.decodeThreadCpuMs = decodeThreadCpuMs;
            this.nativeWorkerCpuMs = nativeWorkerCpuMs;
            this.cpuUtilizationPercent = cpuUtilizationPercent;
            this.resultCount = resultCount;
            this.resultSha256 = resultSha256;
            this.peakJavaHeapBytes = peakJavaHeapBytes;
            this.peakNativeHeapBytes = peakNativeHeapBytes;
            this.peakPssBytes = peakPssBytes;
            this.peakRssBytes = peakRssBytes;
            this.nativeReport = nativeReport;
        }

        JSONObject toJson(int index) throws Exception {
            JSONObject result = new JSONObject();
            result.put("index", index);
            result.put("elapsed_ms", Math.round(elapsedMs * 1000.0) / 1000.0);
            result.put("process_cpu_ms", Math.round(processCpuMs * 1000.0) / 1000.0);
            result.put("benchmark_cpu_ms", Math.round(benchmarkCpuMs * 1000.0) / 1000.0);
            result.put("decode_cpu_ms", Math.round(decodeCpuMs * 1000.0) / 1000.0);
            result.put("decode_thread_cpu_ms",
                    Math.round(decodeThreadCpuMs * 1000.0) / 1000.0);
            result.put("native_worker_cpu_ms",
                    Math.round(nativeWorkerCpuMs * 1000.0) / 1000.0);
            result.put("cpu_utilization_percent",
                    Math.round(cpuUtilizationPercent * 1000.0) / 1000.0);
            result.put("result_count", resultCount);
            result.put("result_sha256", resultSha256);
            result.put("peak_java_heap_bytes", peakJavaHeapBytes);
            result.put("peak_native_heap_bytes", peakNativeHeapBytes);
            result.put("peak_total_pss_bytes", peakPssBytes);
            result.put("peak_rss_bytes", peakRssBytes);
            return result;
        }
    }
}
