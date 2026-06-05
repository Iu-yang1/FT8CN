package com.bg7yoz.ft8cn.diagnostics;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 纯内存样本基准入口。调用方显式提供样本路径，结果只返回到内存和 Logcat 调用链。
 */
public final class FtxSampleDecodeBenchmarkMatrix {
    public static final class BenchmarkCase {
        public final String name;
        public final String wavPath;
        public final int mode;
        public final int q65Submode;
        public final int q65TrPeriodSeconds;

        public BenchmarkCase(String name,
                             String wavPath,
                             int mode,
                             int q65Submode,
                             int q65TrPeriodSeconds) {
            this.name = name == null ? "unnamed" : name;
            this.wavPath = wavPath == null ? "" : wavPath;
            this.mode = mode;
            this.q65Submode = q65Submode;
            this.q65TrPeriodSeconds = q65TrPeriodSeconds;
        }
    }

    private FtxSampleDecodeBenchmarkMatrix() {
    }

    public static List<String> run(List<BenchmarkCase> cases,
                                   long utcTime,
                                   String myCall,
                                   int decodePassCount,
                                   int multiDecodeRoundCount,
                                   int qsoFreqSensitivity,
                                   int decodeSensitivity,
                                   boolean enableWidebandDxSearch,
                                   boolean deepDecodeEnabled) {
        if (cases == null || cases.isEmpty()) {
            return Collections.singletonList("sampleBenchmark matrix=no-cases failureReason=no-sample");
        }
        List<String> reports = new ArrayList<>(cases.size());
        for (BenchmarkCase benchmarkCase : cases) {
            if (benchmarkCase == null
                    || benchmarkCase.wavPath.isEmpty()
                    || !new File(benchmarkCase.wavPath).isFile()) {
                reports.add(String.format(Locale.US,
                        "sampleBenchmark case=%s path=%s mode=%d q65Submode=%d q65TrPeriod=%d "
                                + "failureReason=no-sample",
                        benchmarkCase == null ? "null" : benchmarkCase.name,
                        benchmarkCase == null ? "-" : benchmarkCase.wavPath,
                        benchmarkCase == null ? -1 : benchmarkCase.mode,
                        benchmarkCase == null ? -1 : benchmarkCase.q65Submode,
                        benchmarkCase == null ? 0 : benchmarkCase.q65TrPeriodSeconds));
                continue;
            }
            String nativeReport = NativeSampleDecode.decodeWavFile(
                    benchmarkCase.wavPath,
                    benchmarkCase.mode,
                    utcTime,
                    myCall,
                    decodePassCount,
                    multiDecodeRoundCount,
                    qsoFreqSensitivity,
                    decodeSensitivity,
                    false,
                    enableWidebandDxSearch,
                    deepDecodeEnabled,
                    benchmarkCase.q65Submode,
                    benchmarkCase.q65TrPeriodSeconds);
            reports.add(String.format(Locale.US,
                    "sampleBenchmark case=%s path=%s%n%s",
                    benchmarkCase.name,
                    benchmarkCase.wavPath,
                    nativeReport == null ? "failureReason=native-null-report" : nativeReport));
        }
        return reports;
    }
}
