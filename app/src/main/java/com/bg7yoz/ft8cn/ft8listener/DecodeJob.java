package com.bg7yoz.ft8cn.ft8listener;

import java.util.concurrent.atomic.AtomicBoolean;

final class DecodeJob implements Runnable, Comparable<DecodeJob> {
    final long requestSequence;
    final long triggerSequence;
    final DecodeStage stage;
    final DecodePriority priority;
    final int mode;
    final long utc;
    final String sourceTag;
    final String enqueueReason;
    final long enqueueWallClockMs;
    final Runnable delegate;
    final Runnable afterSchedulerRelease;
    private Runnable beforeRun;
    private Runnable afterRun;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    DecodeJob(long requestSequence,
              long triggerSequence,
              DecodeStage stage,
              DecodePriority priority,
              int mode,
              long utc,
              String sourceTag,
              String enqueueReason,
              long enqueueWallClockMs,
              Runnable delegate,
              Runnable afterSchedulerRelease) {
        this.requestSequence = requestSequence;
        this.triggerSequence = triggerSequence;
        this.stage = stage;
        this.priority = priority;
        this.mode = mode;
        this.utc = utc;
        this.sourceTag = sourceTag;
        this.enqueueReason = enqueueReason;
        this.enqueueWallClockMs = enqueueWallClockMs;
        this.delegate = delegate;
        this.afterSchedulerRelease = afterSchedulerRelease;
    }

    void setBeforeRun(Runnable beforeRun) {
        this.beforeRun = beforeRun;
    }

    void setAfterRun(Runnable afterRun) {
        this.afterRun = afterRun;
    }

    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (beforeRun != null) {
            beforeRun.run();
        }
        try {
            delegate.run();
        } finally {
            completeOnce();
        }
    }

    /** 关闭调度器时，尚未运行的任务也必须释放 live marker 等外层状态。 */
    void cancelBeforeRun() {
        if (started.compareAndSet(false, true)) {
            completeOnce();
        }
    }

    private void completeOnce() {
        if (completed.compareAndSet(false, true) && afterRun != null) {
            afterRun.run();
        }
    }

    @Override
    public int compareTo(DecodeJob other) {
        if (other == null) {
            return -1;
        }
        int byPriority = Integer.compare(other.priority.sortOrder, priority.sortOrder);
        if (byPriority != 0) {
            return byPriority;
        }
        return Long.compare(requestSequence, other.requestSequence);
    }
}
