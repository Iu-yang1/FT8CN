package com.bg7yoz.ft8cn.ft8transmit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Q65PlaybackDrainTest {
    @Test
    public void reportsDrainedOnlyAfterPlaybackReachesGeneratedSamples() {
        AtomicLong clock = new AtomicLong();
        AtomicLong played = new AtomicLong(8_000L);

        Q65PlaybackDrain.Result result = Q65PlaybackDrain.await(
                12_000L,
                12_000,
                played::get,
                () -> false,
                clock::get,
                millis -> {
                    clock.addAndGet(millis);
                    played.addAndGet(1_000L);
                });

        assertEquals(Q65PlaybackDrain.Result.DRAINED, result);
    }

    @Test
    public void timeoutIsNotReportedAsSuccess() {
        AtomicLong clock = new AtomicLong();

        Q65PlaybackDrain.Result result = Q65PlaybackDrain.await(
                12_000L,
                12_000,
                () -> 0L,
                () -> false,
                clock::get,
                clock::addAndGet);

        assertEquals(Q65PlaybackDrain.Result.TIMED_OUT, result);
    }

    @Test
    public void cancellationStopsWaitingImmediately() {
        AtomicBoolean cancelled = new AtomicBoolean(true);

        Q65PlaybackDrain.Result result = Q65PlaybackDrain.await(
                12_000L,
                12_000,
                () -> 0L,
                cancelled::get,
                () -> 0L,
                millis -> { });

        assertEquals(Q65PlaybackDrain.Result.CANCELLED, result);
    }

    @Test
    public void invalidInputIsRejected() {
        assertEquals(
                Q65PlaybackDrain.Result.INVALID_INPUT,
                Q65PlaybackDrain.await(0L, 12_000, () -> 0L, () -> false, () -> 0L, millis -> { }));
    }
}
