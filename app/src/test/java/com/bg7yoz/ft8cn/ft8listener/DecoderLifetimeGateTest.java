package com.bg7yoz.ft8cn.ft8listener;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DecoderLifetimeGateTest {
    @Test
    public void closeWaitsForInFlightNativeCallAndBlocksCallbacks() throws Exception {
        DecoderLifetimeGate gate = new DecoderLifetimeGate();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            assertTrue(gate.beginNativeCall());
            entered.countDown();
            try {
                finish.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                gate.endNativeCall();
            }
        });
        worker.start();
        assertTrue(entered.await(1L, TimeUnit.SECONDS));

        assertTrue(gate.beginClosing());
        assertFalse(gate.callbacksAllowed());
        assertFalse(gate.beginNativeCall());
        assertFalse(gate.awaitNativeIdle(20L, TimeUnit.MILLISECONDS));

        finish.countDown();
        assertTrue(gate.awaitNativeIdle(1L, TimeUnit.SECONDS));
        gate.markReleased();
        assertTrue(gate.awaitReleased(1L, TimeUnit.SECONDS));
        worker.join(1000L);
    }

    @Test
    public void closingAndReleaseAreIdempotent() throws Exception {
        DecoderLifetimeGate gate = new DecoderLifetimeGate();
        assertTrue(gate.beginClosing());
        assertFalse(gate.beginClosing());
        gate.markReleased();
        gate.markReleased();
        assertTrue(gate.awaitReleased(1L, TimeUnit.MILLISECONDS));
    }
}
