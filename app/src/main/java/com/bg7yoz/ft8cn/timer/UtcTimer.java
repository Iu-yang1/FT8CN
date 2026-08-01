package com.bg7yoz.ft8cn.timer;
/**
 * UtcTimer类，用于实现FT8/FT4在各通联周期开始时触发的动作。
 * 通过构造参数 sec（单位：十分之一秒）决定周期，例如：
 * FT8 = 150（15秒）
 * FT4 = 75（7.5秒）
 *
 * 时间由单调时钟锚定；NTP/GNSS 校准不会在 slot 中途跟随系统 wall clock 跳变。
 * 旧 delay 字段仅作为用户手动微调兼容层。
 *
 * @author BG7YOZ
 * @date 2022.5.7
 */

import android.annotation.SuppressLint;

import com.bg7yoz.ft8cn.core.time.DisciplinedClockRegistry;
import com.bg7yoz.ft8cn.core.time.MultiSourceNtpDiscipline;
import com.bg7yoz.ft8cn.core.time.NtpMeasurement;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class UtcTimer {
    /**
     * 周期，单位：0.1秒
     * 150 = 15秒（FT8）
     * 75  = 7.5秒（FT4）
     */
    private final int sec;

    private final boolean doOnce;
    private final OnUtcTimer onUtcTimer;

    private long utc;

    /**
     * 用户手动微调，单位毫秒。NTP/GNSS 校准由 DisciplinedClock 单独维护，避免重复叠加。
     */
    public static volatile int delay = 0;

    /**
     * NTP 返回的真实偏移量，单位毫秒
     */
    public static volatile int realDelay = 0;

    /**
     * 最近一次 NTP 往返延迟，单位毫秒
     */
    public static volatile long lastNtpRoundTripDelay = -1;

    /**
     * 最近一次同步时间（本地时间戳）
     */
    public static volatile long lastSyncTime = 0;

    /**
     * 最近一次使用的 NTP 服务器
     */
    public static volatile String lastSyncServer = "";

    private volatile boolean running = false;

    private static final ThreadFactory TIME_THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "ft8cn-time-scheduler");
        thread.setDaemon(true);
        return thread;
    };
    private static final ScheduledThreadPoolExecutor TIME_SCHEDULER =
            new ScheduledThreadPoolExecutor(1, TIME_THREAD_FACTORY);
    private static final ThreadPoolExecutor CALLBACK_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "ft8cn-time-callback");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor NTP_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, "ft8cn-sntp");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicBoolean NTP_SYNC_RUNNING = new AtomicBoolean(false);

    /**
     * 实例级时间偏移（毫秒）
     */
    private int time_sec = 0;
    private volatile long lastTriggeredSlotIndex = Long.MIN_VALUE;
    private volatile boolean destroyed = false;
    private final AtomicBoolean slotCallbackPending = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatCallbackPending = new AtomicBoolean(false);
    private final ScheduledFuture<?> slotFuture;
    private final ScheduledFuture<?> heartbeatFuture;

    /**
     * NTP 同步结果
     */
    public static class NtpSyncResult {
        public final String server;
        public final int realOffsetMs;
        public final int alignedOffsetMs;
        public final long roundTripDelayMs;
        public final long syncTimeMs;

        public NtpSyncResult(String server, int realOffsetMs, int alignedOffsetMs,
                             long roundTripDelayMs, long syncTimeMs) {
            this.server = server;
            this.realOffsetMs = realOffsetMs;
            this.alignedOffsetMs = alignedOffsetMs;
            this.roundTripDelayMs = roundTripDelayMs;
            this.syncTimeMs = syncTimeMs;
        }
    }

    @SuppressLint("DefaultLocale")
    public static String getTimeStr(long time) {
        long curtime = time / 1000;
        long hour = (curtime / (60 * 60)) % 24;
        long sec = curtime % 60;
        long min = (curtime % 3600) / 60;
        return String.format("UTC : %02d:%02d:%02d", hour, min, sec);
    }

    @SuppressLint("DefaultLocale")
    public static String getTimeHHMMSS(long time) {
        long curtime = time / 1000;
        long hour = (curtime / (60 * 60)) % 24;
        long sec = curtime % 60;
        long min = (curtime % 3600) / 60;
        return String.format("%02d%02d%02d", hour, min, sec);
    }

    public static String getYYYYMMDD(long time) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return simpleDateFormat.format(new Date(time));
    }

    public static String getDatetimeStr(long time) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return simpleDateFormat.format(new Date(time));
    }

    public static String getDatetimeYYYYMMDD_HHMMSS(long time) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return simpleDateFormat.format(new Date(time));
    }

    /**
     * 构造时钟触发器
     *
     * @param sec        周期，单位为十分之一秒
     * @param doOnce     是否只触发一次
     * @param onUtcTimer 回调
     */
    public UtcTimer(int sec, boolean doOnce, OnUtcTimer onUtcTimer) {
        this.sec = sec;
        this.doOnce = doOnce;
        this.onUtcTimer = onUtcTimer;

        TIME_SCHEDULER.setRemoveOnCancelPolicy(true);
        slotFuture = TIME_SCHEDULER.scheduleAtFixedRate(this::tickSlot, 0L, 10L, TimeUnit.MILLISECONDS);
        heartbeatFuture = TIME_SCHEDULER.scheduleAtFixedRate(
                this::tickHeartbeat, 0L, 1L, TimeUnit.SECONDS);
    }

    private void tickSlot() {
        if (destroyed) {
            return;
        }
        long currentUtc = getSystemTime();
        utc = currentUtc;
        long currentSlotIndex = getSlotIndex(currentUtc);
        if (!running || currentSlotIndex <= lastTriggeredSlotIndex) {
            return;
        }
        lastTriggeredSlotIndex = currentSlotIndex;
        if (doOnce) {
            running = false;
        }
        if (!slotCallbackPending.compareAndSet(false, true)) {
            return;
        }
        try {
            CALLBACK_EXECUTOR.execute(() -> {
                try {
                    if (!destroyed) {
                        onUtcTimer.doOnSecTimer(currentUtc);
                    }
                } finally {
                    slotCallbackPending.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            slotCallbackPending.set(false);
        }
    }

    private void tickHeartbeat() {
        if (destroyed || !heartbeatCallbackPending.compareAndSet(false, true)) {
            return;
        }
        long currentUtc = getSystemTime();
        utc = currentUtc;
        try {
            CALLBACK_EXECUTOR.execute(() -> {
                try {
                    if (!destroyed) {
                        onUtcTimer.doHeartBeatTimer(currentUtc);
                    }
                } finally {
                    heartbeatCallbackPending.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            heartbeatCallbackPending.set(false);
        }
    }

    /**
     * 心跳动作
     */
    public void stop() {
        running = false;
    }

    public void start() {
        if (destroyed) {
            return;
        }
        lastTriggeredSlotIndex = getSlotIndex(getSystemTime());
        running = true;
    }

    public boolean isRunning() {
        return running;
    }

    public void delete() {
        destroy();
    }

    public synchronized void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        running = false;
        slotFuture.cancel(false);
        heartbeatFuture.cancel(false);
    }

    /**
     * 设置时间偏移量，正值是向后偏移
     *
     * @param time_sec 时间偏移量（毫秒）
     */
    public void setTime_sec(int time_sec) {
        this.time_sec = time_sec;
        lastTriggeredSlotIndex = getSlotIndex(getSystemTime());
    }

    /**
     * 获取时间偏移
     *
     * @return 时间偏移值（毫秒）
     */
    public int getTime_sec() {
        return time_sec;
    }

    public long getUtc() {
        return utc;
    }

    private long getSlotIndex(long utc) {
        return (utc - time_sec) / (sec * 100L);
    }

    /**
     * 根据UTC时间和周期计算时序
     *
     * @param utc       UTC时间（毫秒）
     * @param slotTimeM 周期，单位：0.1秒
     * @return 时序0/1
     */
    public static int sequential(long utc, int slotTimeM) {
        long tick100ms = utc / 100L;
        long slotIndex = tick100ms / slotTimeM;
        return (int) (slotIndex % 2);
    }

    /**
     * 兼容旧逻辑：默认按FT8 15秒周期计算时序
     */
    public static int sequential(long utc) {
        return sequential(utc, com.bg7yoz.ft8cn.FT8Common.FT8_SLOT_TIME_M);
    }

    /**
     * 根据UTC时间和周期计算0~3时序
     */
    public static int sequential4(long utc, int slotTimeM) {
        long tick100ms = utc / 100L;
        long slotIndex = tick100ms / slotTimeM;
        return (int) (slotIndex % 4);
    }

    /**
     * 兼容旧逻辑：默认按FT8 15秒周期计算0~3时序
     */
    public static int sequential4(long utc) {
        return sequential4(utc, com.bg7yoz.ft8cn.FT8Common.FT8_SLOT_TIME_M);
    }

    /**
     * 当前时刻的时序（按指定周期）
     */
    public static int getNowSequential(int slotTimeM) {
        return sequential(getSystemTime(), slotTimeM);
    }

    /**
     * 兼容旧逻辑：默认按FT8 15秒周期
     */
    public static int getNowSequential() {
        return sequential(getSystemTime(), com.bg7yoz.ft8cn.FT8Common.FT8_SLOT_TIME_M);
    }

    public static long getSystemTime() {
        return delay + DisciplinedClockRegistry.nowMillis();
    }

    /**
     * 获取当前保存的真实NTP偏移
     */
    public static int getRealDelay() {
        return realDelay;
    }

    /**
     * 获取当前用于时隙计算的对齐偏移
     */
    public static int getAlignedDelay() {
        return delay;
    }

    /**
     * 兼容旧逻辑：默认使用 pool.ntp.org
     */
    public static void syncTime(AfterSyncTime afterSyncTime) {
        syncTime("pool.ntp.org", afterSyncTime);
    }

    /**
     * 兼容旧逻辑：指定服务器，同步后只返回真实offset
     */
    public static void syncTime(String server, AfterSyncTime afterSyncTime) {
        syncTime(server, new AfterSyncTimeDetail() {
            @Override
            public void doAfterSyncTimer(NtpSyncResult result) {
                if (afterSyncTime != null) {
                    afterSyncTime.doAfterSyncTimer(result.realOffsetMs);
                }
            }

            @Override
            public void syncFailed(IOException e) {
                if (afterSyncTime != null) {
                    afterSyncTime.syncFailed(e);
                }
            }
        });
    }

    /**
     * 详细同步接口：可返回服务器、真实偏移、对齐偏移、往返延迟、同步时间
     */
    public static void syncTime(String server, AfterSyncTimeDetail afterSyncTimeDetail) {
        if (!NTP_SYNC_RUNNING.compareAndSet(false, true)) {
            if (afterSyncTimeDetail != null) {
                afterSyncTimeDetail.syncFailed(new IOException("SNTP synchronization already in progress"));
            }
            return;
        }
        try {
            NTP_EXECUTOR.execute(() -> {
                try {
                String targetServer = (server == null || server.trim().length() == 0)
                        ? "pool.ntp.org"
                        : server.trim();

                IOException lastFailure = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        NtpSyncResult bestResult = queryNtpServers(targetServer);

                        realDelay = bestResult.realOffsetMs;
                        // 校准已进入单调时钟锚点，旧 delay 只保留给用户手动微调。
                        delay = 0;
                        lastNtpRoundTripDelay = bestResult.roundTripDelayMs;
                        lastSyncTime = bestResult.syncTimeMs;
                        lastSyncServer = bestResult.server;

                        if (afterSyncTimeDetail != null) {
                            afterSyncTimeDetail.doAfterSyncTimer(bestResult);
                        }
                        return;
                    } catch (IOException e) {
                        lastFailure = e;
                        if (attempt < 2) {
                            try {
                                Thread.sleep(500L << attempt);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                lastFailure = new IOException("NTP synchronization interrupted", interrupted);
                                break;
                            }
                        }
                    }
                }
                if (afterSyncTimeDetail != null) {
                    afterSyncTimeDetail.syncFailed(lastFailure == null
                            ? new IOException("NTP synchronization failed")
                            : lastFailure);
                }
                } finally {
                    NTP_SYNC_RUNNING.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            NTP_SYNC_RUNNING.set(false);
            if (afterSyncTimeDetail != null) {
                afterSyncTimeDetail.syncFailed(
                        new IOException("SNTP synchronization queue is unavailable", rejected));
            }
        }
    }

    private static NtpSyncResult queryNtpServers(String targetServer) throws IOException {
        NtpMeasurement measurement = new MultiSourceNtpDiscipline().synchronize(targetServer);
        if (!DisciplinedClockRegistry.submitSample(measurement.getSample())) {
            throw new IOException("NTP sample rejected by disciplined clock");
        }
        return new NtpSyncResult(
                measurement.getServer(),
                (int) Math.round(measurement.getOffsetMillis()),
                0,
                Math.round(measurement.getRoundTripDelayMillis()),
                DisciplinedClockRegistry.nowMillis()
        );
    }

    public interface AfterSyncTime {
        void doAfterSyncTimer(int secTime);

        void syncFailed(IOException e);
    }

    public interface AfterSyncTimeDetail {
        void doAfterSyncTimer(NtpSyncResult result);

        void syncFailed(IOException e);
    }
}

