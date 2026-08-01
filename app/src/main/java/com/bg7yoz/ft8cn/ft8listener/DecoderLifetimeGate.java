package com.bg7yoz.ft8cn.ft8listener;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理 Java 调度器与 JNI decoder 句柄之间的关闭边界。
 * closing 一旦置位，新调用和 UI 回调都会被拒绝；已有 JNI 调用则允许安全退出。
 */
final class DecoderLifetimeGate {
    private final Object idleLock = new Object();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);
    private int activeNativeCalls;

    boolean beginNativeCall() {
        synchronized (idleLock) {
            if (closing.get()) {
                return false;
            }
            activeNativeCalls++;
            return true;
        }
    }

    void endNativeCall() {
        synchronized (idleLock) {
            if (activeNativeCalls <= 0) {
                throw new IllegalStateException("native decode lifetime counter underflow");
            }
            activeNativeCalls--;
            if (activeNativeCalls == 0) {
                idleLock.notifyAll();
            }
        }
    }

    boolean beginClosing() {
        return closing.compareAndSet(false, true);
    }

    boolean callbacksAllowed() {
        return !closing.get() && !released.get();
    }

    boolean awaitNativeIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (idleLock) {
            while (activeNativeCalls > 0 && remainingNanos > 0L) {
                TimeUnit.NANOSECONDS.timedWait(idleLock, remainingNanos);
                remainingNanos = deadline - System.nanoTime();
            }
            return activeNativeCalls == 0;
        }
    }

    void markReleased() {
        synchronized (idleLock) {
            released.set(true);
            idleLock.notifyAll();
        }
    }

    boolean awaitReleased(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (idleLock) {
            while (!released.get() && remainingNanos > 0L) {
                TimeUnit.NANOSECONDS.timedWait(idleLock, remainingNanos);
                remainingNanos = deadline - System.nanoTime();
            }
            return released.get();
        }
    }
}
