package com.bg7yoz.ft8cn.ft8listener;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
}
