package com.bg7yoz.ft8cn.ft8transmit;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 等待 Q65 流式音频真正离开 AudioTrack；所有时间均使用单调时钟。
 */
final class Q65PlaybackDrain {
    private static final long POLL_INTERVAL_MILLIS = 10L;
    private static final long MIN_TIMEOUT_MILLIS = 1_500L;
    private static final long MAX_TIMEOUT_MILLIS = 10_000L;
    private static final long DRAIN_GUARD_MILLIS = 1_000L;

    enum Result {
        DRAINED("none"),
        CANCELLED("playback-drain-cancelled"),
        TIMED_OUT("playback-drain-timeout"),
        INTERRUPTED("playback-drain-interrupted"),
        INVALID_INPUT("playback-drain-invalid-input");

        final String failureReason;

        Result(String failureReason) {
            this.failureReason = failureReason;
        }
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private Q65PlaybackDrain() {
    }

    static Result await(long generatedSamples,
                        int sampleRate,
                        LongSupplier playedSamples,
                        BooleanSupplier cancelled,
                        LongSupplier monotonicMillis,
                        Sleeper sleeper) {
        if (generatedSamples <= 0L || sampleRate <= 0 || playedSamples == null
                || cancelled == null || monotonicMillis == null || sleeper == null) {
            return Result.INVALID_INPUT;
        }

        long initialPlayed = Math.max(0L, playedSamples.getAsLong());
        if (initialPlayed >= generatedSamples) {
            return Result.DRAINED;
        }
        long remainingSamples = generatedSamples - initialPlayed;
        long expectedDrainMillis = divideRoundUp(remainingSamples * 1_000L, sampleRate);
        long timeoutMillis = Math.max(
                MIN_TIMEOUT_MILLIS,
                Math.min(MAX_TIMEOUT_MILLIS, expectedDrainMillis + DRAIN_GUARD_MILLIS));
        long startedAt = monotonicMillis.getAsLong();

        while (true) {
            if (cancelled.getAsBoolean()) {
                return Result.CANCELLED;
            }
            if (playedSamples.getAsLong() >= generatedSamples) {
                return Result.DRAINED;
            }
            long elapsed = monotonicMillis.getAsLong() - startedAt;
            if (elapsed >= timeoutMillis) {
                return Result.TIMED_OUT;
            }
            try {
                sleeper.sleep(Math.min(POLL_INTERVAL_MILLIS, timeoutMillis - elapsed));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return Result.INTERRUPTED;
            }
        }
    }

    private static long divideRoundUp(long dividend, long divisor) {
        return dividend / divisor + (dividend % divisor == 0L ? 0L : 1L);
    }
}
