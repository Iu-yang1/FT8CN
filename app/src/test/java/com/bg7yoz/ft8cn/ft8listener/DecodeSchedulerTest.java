package com.bg7yoz.ft8cn.ft8listener;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

public class DecodeSchedulerTest {
    @Test
    public void deepFollowUpRunsAfterParentPriorityIsReleased() throws Exception {
        AtomicReference<DecodeScheduler> schedulerRef = new AtomicReference<>();
        CountDownLatch parentRan = new CountDownLatch(1);
        CountDownLatch deepRan = new CountDownLatch(1);
        DecodeScheduler scheduler = new DecodeScheduler(
                "decode-scheduler-test",
                0L,
                DecodeWorkerConfig.conservative(),
                DecodeConcurrencyPolicy.PARALLEL_PREPARE_SERIAL_NATIVE,
                text -> { });
        schedulerRef.set(scheduler);

        DecodeJob deep = new DecodeJob(
                2L, 1L, DecodeStage.DEEP_SUPPLEMENT, DecodePriority.DEEP_SUPPLEMENT,
                0, 1000L, "test", "follow-up", System.currentTimeMillis(),
                deepRan::countDown, null);
        DecodeJob parent = new DecodeJob(
                1L, 1L, DecodeStage.LIVE_FULL, DecodePriority.LIVE_FULL,
                0, 1000L, "test", "parent", System.currentTimeMillis(),
                parentRan::countDown,
                () -> assertTrue(schedulerRef.get().enqueue(deep)));

        try {
            assertTrue(scheduler.enqueue(parent));
            assertTrue(parentRan.await(2L, TimeUnit.SECONDS));
            assertTrue(deepRan.await(2L, TimeUnit.SECONDS));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void shutdownRejectsNewJobsAndReleasesQueuedMarkers() throws Exception {
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch activeRelease = new CountDownLatch(1);
        AtomicInteger queuedReleaseCount = new AtomicInteger();
        DecodeScheduler scheduler = new DecodeScheduler(
                "decode-scheduler-close-test",
                0L,
                DecodeWorkerConfig.conservative(),
                DecodeConcurrencyPolicy.PARALLEL_PREPARE_SERIAL_NATIVE,
                text -> { });
        DecodeJob active = new DecodeJob(
                1L, 1L, DecodeStage.LIVE_FULL, DecodePriority.LIVE_FULL,
                0, 1000L, "test", "active", System.currentTimeMillis(),
                () -> {
                    activeStarted.countDown();
                    try {
                        activeRelease.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, null);
        DecodeJob queued = new DecodeJob(
                2L, 2L, DecodeStage.LIVE_FULL, DecodePriority.LIVE_FULL,
                0, 2000L, "test", "queued", System.currentTimeMillis(),
                () -> { }, queuedReleaseCount::incrementAndGet);

        assertTrue(scheduler.enqueue(active));
        assertTrue(activeStarted.await(2L, TimeUnit.SECONDS));
        assertTrue(scheduler.enqueue(queued));
        scheduler.shutdownNow();
        activeRelease.countDown();

        assertTrue(scheduler.awaitTermination(2L, TimeUnit.SECONDS));
        assertTrue(scheduler.isShutdown());
        assertEquals(1, queuedReleaseCount.get());
        assertFalse(scheduler.enqueue(queued));
    }
}
