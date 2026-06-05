package com.bg7yoz.ft8cn.ft8listener;

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
    private Runnable beforeRun;
    private Runnable afterRun;

    DecodeJob(long requestSequence,
              long triggerSequence,
              DecodeStage stage,
              DecodePriority priority,
              int mode,
              long utc,
              String sourceTag,
              String enqueueReason,
              long enqueueWallClockMs,
              Runnable delegate) {
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
    }

    void setBeforeRun(Runnable beforeRun) {
        this.beforeRun = beforeRun;
    }

    void setAfterRun(Runnable afterRun) {
        this.afterRun = afterRun;
    }

    @Override
    public void run() {
        if (beforeRun != null) {
            beforeRun.run();
        }
        try {
            delegate.run();
        } finally {
            if (afterRun != null) {
                afterRun.run();
            }
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
