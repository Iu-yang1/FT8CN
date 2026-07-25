package com.bg7yoz.ft8cn.ft8listener;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

final class DecodeScheduler {
    interface Logger {
        void debug(String text);
    }

    private final String threadNamePrefix;
    private final long threadStackBytes;
    private final Logger logger;
    private final Object executorLock = new Object();
    private volatile DecodeWorkerConfig workerConfig;
    private volatile DecodeConcurrencyPolicy concurrencyPolicy;
    private volatile ThreadPoolExecutor executor;
    private volatile String lastDropReason = "none";
    private volatile String lastExecutedStage = "none";
    private final AtomicIntegerArray activePriorities =
            new AtomicIntegerArray(DecodePriority.values().length);

    DecodeScheduler(String threadNamePrefix,
                    long threadStackBytes,
                    DecodeWorkerConfig workerConfig,
                    DecodeConcurrencyPolicy concurrencyPolicy,
                    Logger logger) {
        this.threadNamePrefix = threadNamePrefix;
        this.threadStackBytes = threadStackBytes;
        this.logger = logger;
        this.workerConfig = workerConfig;
        this.concurrencyPolicy = concurrencyPolicy;
        this.executor = createExecutor(workerConfig.workerCount);
    }

    DecodeWorkerConfig getWorkerConfig() {
        return workerConfig;
    }

    DecodeConcurrencyPolicy getConcurrencyPolicy() {
        return concurrencyPolicy;
    }

    void setConcurrencyPolicy(DecodeConcurrencyPolicy concurrencyPolicy) {
        this.concurrencyPolicy = concurrencyPolicy;
    }

    void setWorkerConfig(DecodeWorkerConfig workerConfig) {
        if (workerConfig == null || workerConfig.equals(this.workerConfig)) {
            return;
        }
        synchronized (executorLock) {
            ThreadPoolExecutor oldExecutor = this.executor;
            this.workerConfig = workerConfig;
            this.executor = createExecutor(workerConfig.workerCount);
            oldExecutor.shutdownNow();
        }
    }

    boolean enqueue(DecodeJob job) {
        synchronized (executorLock) {
            String dropReason = getDropReason(job, executor, workerConfig);
            if (dropReason != null) {
                lastDropReason = dropReason;
                logger.debug(String.format(
                        "decode scheduler drop request=%d trigger=%d stage=%s priority=%s utc=%d source=%s reason=%s queue=%d active=%d config=%s policy=%s",
                        job.requestSequence,
                        job.triggerSequence,
                        job.stage,
                        job.priority,
                        job.utc,
                        job.sourceTag,
                        dropReason,
                        executor.getQueue().size(),
                        executor.getActiveCount(),
                        workerConfig,
                        concurrencyPolicy));
                return false;
            }
            job.setBeforeRun(new Runnable() {
                @Override
                public void run() {
                    lastExecutedStage = job.stage.name();
                    activePriorities.incrementAndGet(job.priority.ordinal());
                }
            });
            job.setAfterRun(new Runnable() {
                @Override
                public void run() {
                    activePriorities.decrementAndGet(job.priority.ordinal());
                    if (job.afterSchedulerRelease != null) {
                        job.afterSchedulerRelease.run();
                    }
                }
            });
            executor.execute(job);
            return true;
        }
    }

    int getPendingJobCount() {
        return executor.getQueue().size();
    }

    int getActiveJobCount() {
        return executor.getActiveCount();
    }

    int getWorkerCount() {
        return workerConfig.workerCount;
    }

    String getStatusSummary() {
        ThreadPoolExecutor currentExecutor = executor;
        return "scheduler"
                + " workerConfig=" + workerConfig
                + " concurrencyPolicy=" + concurrencyPolicy
                + " workerCount=" + workerConfig.workerCount
                + " activeCount=" + currentExecutor.getActiveCount()
                + " pendingCount=" + currentExecutor.getQueue().size()
                + " activePriorities=" + getActivePrioritySummary()
                + " lastDropReason=" + lastDropReason
                + " lastExecutedStage=" + lastExecutedStage;
    }

    void shutdownNow() {
        synchronized (executorLock) {
            executor.shutdownNow();
        }
    }

    private ThreadPoolExecutor createExecutor(int workerCount) {
        ThreadFactory threadFactory = new ThreadFactory() {
            private int threadIndex = 1;

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(
                        null,
                        runnable,
                        threadNamePrefix + "-" + threadIndex++,
                        threadStackBytes);
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor nextExecutor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                30L,
                TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(),
                threadFactory);
        nextExecutor.prestartAllCoreThreads();
        return nextExecutor;
    }

    private String getDropReason(DecodeJob job,
                                 ThreadPoolExecutor executor,
                                 DecodeWorkerConfig workerConfig) {
        int queueSize = executor.getQueue().size();
        int activeCount = executor.getActiveCount();
        if (job.stage == DecodeStage.LIVE_FULL) {
            return null;
        }
        if (job.stage == DecodeStage.EARLY) {
            if (queueSize > workerConfig.earlyBacklogLimit) {
                return "early-backlog";
            }
            if (hasPendingPriorityAtLeast(executor, DecodePriority.Q65_FULL)
                    || hasActivePriorityAtLeast(DecodePriority.Q65_FULL)) {
                return "higher-priority-live-active-or-pending";
            }
            return null;
        }
        if (job.stage == DecodeStage.DEEP_SUPPLEMENT) {
            if (hasPendingPriorityAtLeast(executor, DecodePriority.Q65_FULL)) {
                return "pending-live-or-q65-full";
            }
            if (concurrencyPolicy != DecodeConcurrencyPolicy.PARALLEL_NATIVE
                    && hasActivePriorityAtLeast(DecodePriority.Q65_FULL)) {
                return "active-live-or-q65-full";
            }
            if (queueSize > workerConfig.lowPriorityBacklogLimit) {
                return "deep-backlog";
            }
            // ThreadPoolExecutor 在任务 run() 返回前仍把当前 worker 计为 active。
            // 父 LIVE_FULL 已释放 priority 后排入的同 trigger deep follow-up 可以等待
            // 当前 worker 返回；其他活跃任务仍会阻止 deep 插队。
            if (activeCount >= workerConfig.workerCount && hasAnyActivePriority()) {
                return "deep-workers-busy";
            }
            if (hasPendingPriorityAtLeast(executor, DecodePriority.EARLY)) {
                return "pending-higher-priority";
            }
            return null;
        }
        if (job.stage == DecodeStage.DIAGNOSTIC_SAMPLE) {
            if (queueSize > 0 || activeCount > 0) {
                return "diagnostic-queue-busy";
            }
        }
        return null;
    }

    private boolean hasActivePriorityAtLeast(DecodePriority priority) {
        for (DecodePriority candidate : DecodePriority.values()) {
            if (candidate.sortOrder >= priority.sortOrder
                    && activePriorities.get(candidate.ordinal()) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyActivePriority() {
        for (DecodePriority priority : DecodePriority.values()) {
            if (activePriorities.get(priority.ordinal()) > 0) {
                return true;
            }
        }
        return false;
    }

    private String getActivePrioritySummary() {
        StringBuilder summary = new StringBuilder();
        for (DecodePriority priority : DecodePriority.values()) {
            int count = activePriorities.get(priority.ordinal());
            if (count <= 0) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(',');
            }
            summary.append(priority.name()).append(':').append(count);
        }
        return summary.length() == 0 ? "none" : summary.toString();
    }

    private boolean hasPendingPriorityAtLeast(ThreadPoolExecutor executor, DecodePriority priority) {
        for (Runnable runnable : executor.getQueue()) {
            if (!(runnable instanceof DecodeJob)) {
                continue;
            }
            DecodeJob pending = (DecodeJob) runnable;
            if (pending.priority.sortOrder >= priority.sortOrder) {
                return true;
            }
        }
        return false;
    }

}
