package com.bg7yoz.ft8cn.ft8listener;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
            executor.execute(job);
            return true;
        }
    }

    int getPendingJobCount() {
        return executor.getQueue().size();
    }

    int getWorkerCount() {
        return workerConfig.workerCount;
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
            if (hasPendingStage(executor, DecodeStage.LIVE_FULL)) {
                return "pending-live-full";
            }
            return null;
        }
        if (job.stage == DecodeStage.DEEP_SUPPLEMENT) {
            if (queueSize > workerConfig.lowPriorityBacklogLimit) {
                return "deep-backlog";
            }
            if (activeCount >= workerConfig.workerCount) {
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

    private boolean hasPendingStage(ThreadPoolExecutor executor, DecodeStage stage) {
        for (Runnable runnable : executor.getQueue()) {
            if (!(runnable instanceof DecodeJob)) {
                continue;
            }
            DecodeJob pending = (DecodeJob) runnable;
            if (pending.stage == stage) {
                return true;
            }
        }
        return false;
    }
}
