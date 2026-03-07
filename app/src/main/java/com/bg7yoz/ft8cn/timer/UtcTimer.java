package com.bg7yoz.ft8cn.timer;
/**
 * UtcTimer类，用于实现FT8/FT4在各通联周期开始时触发的动作。
 * 通过构造参数 sec（单位：十分之一秒）决定周期，例如：
 * FT8 = 150（15秒）
 * FT4 = 75（7.5秒）
 *
 * @author BG7YOZ
 * @date 2022.5.7
 */

import android.annotation.SuppressLint;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    public static int delay = 0;//时钟总的延时，（毫秒）
    private boolean running = false;//用来判断是否触发周期的动作

    private final Timer secTimer = new Timer();
    private final Timer heartBeatTimer = new Timer();

    /**
     * 实例级时间偏移（毫秒）
     */
    private int time_sec = 0;

    private final ExecutorService cachedThreadPool = Executors.newCachedThreadPool();
    private final Runnable doSomething = new Runnable() {
        @Override
        public void run() {
            onUtcTimer.doOnSecTimer(utc);
        }
    };

    private final ExecutorService heartBeatThreadPool = Executors.newCachedThreadPool();
    private final Runnable doHeartBeat = new Runnable() {
        @Override
        public void run() {
            onUtcTimer.doHeartBeatTimer(utc);
        }
    };

    /**
     * 类方法。获得UTC时间的字符串表示结果。
     *
     * @param time 时间。
     * @return String 以字符串方式显示UTC时间。
     */
    @SuppressLint("DefaultLocale")
    public static String getTimeStr(long time) {
        long curtime = time / 1000;
        long hour = ((curtime) / (60 * 60)) % 24;//小时
        long sec = (curtime) % 60;//秒
        long min = ((curtime) % 3600) / 60;//分
        return String.format("UTC : %02d:%02d:%02d", hour, min, sec);
    }

    /**
     * 以HHMMSS格式显示UTC时间
     */
    @SuppressLint("DefaultLocale")
    public static String getTimeHHMMSS(long time) {
        long curtime = time / 1000;
        long hour = ((curtime) / (60 * 60)) % 24;//小时
        long sec = (curtime) % 60;//秒
        long min = ((curtime) % 3600) / 60;//分
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

        // 10ms 轮询，用于精确检测 0.1 秒周期边界
        secTimer.schedule(secTask(), 0, 10);
        // 1 秒心跳
        heartBeatTimer.schedule(heartBeatTask(), 0, 1000);
    }

    private TimerTask heartBeatTask() {
        return new TimerTask() {
            @Override
            public void run() {
                doHeartBeatEvent(onUtcTimer);
            }
        };
    }

    private TimerTask secTask() {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    utc = getSystemTime();//获取当前UTC时间（毫秒）

                    // 转换为 0.1 秒单位的时间轴
                    long tick100ms = (utc - time_sec) / 100L;

                    // 触发条件：
                    // tick100ms 对当前实例周期 sec 取模 == 0
                    if (running && (tick100ms % sec == 0)) {
                        cachedThreadPool.execute(doSomething);

                        if (doOnce) {
                            running = false;
                            return;
                        }

                        // 为防止同一个边界重复触发，睡眠一个完整周期长度
                        long sleepMs = sec * 100L;
                        Thread.sleep(sleepMs);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };
    }

    /**
     * 心跳动作
     */
    private void doHeartBeatEvent(OnUtcTimer onUtcTimer) {
        heartBeatThreadPool.execute(doHeartBeat);
    }

    public void stop() {
        running = false;
    }

    public void start() {
        running = true;
    }

    public boolean isRunning() {
        return running;
    }

    public void delete() {
        secTimer.cancel();
        heartBeatTimer.cancel();
    }

    /**
     * 设置时间偏移量，正值是向后偏移
     *
     * @param time_sec 时间偏移量（毫秒）
     */
    public void setTime_sec(int time_sec) {
        this.time_sec = time_sec;
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

    /**
     * 根据UTC时间和周期计算时序
     *
     * @param utc       UTC时间（毫秒）
     * @param slotTimeM 周期，单位：0.1秒
     * @return 时序 0/1
     */
    public static int sequential(long utc, int slotTimeM) {
        long tick100ms = utc / 100L;
        long slotIndex = tick100ms / slotTimeM;
        return (int) (slotIndex % 2);
    }

    /**
     * 兼容旧逻辑：默认按 FT8 15 秒周期计算时序
     */
    public static int sequential(long utc) {
        return sequential(utc, com.bg7yoz.ft8cn.FT8Common.FT8_SLOT_TIME_M);
    }

    /**
     * 根据UTC时间和周期计算 0~3 时序
     */
    public static int sequential4(long utc, int slotTimeM) {
        long tick100ms = utc / 100L;
        long slotIndex = tick100ms / slotTimeM;
        return (int) (slotIndex % 4);
    }

    /**
     * 兼容旧逻辑：默认按 FT8 15 秒周期计算 0~3 时序
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
     * 兼容旧逻辑：默认按 FT8 15 秒周期
     */
    public static int getNowSequential() {
        return sequential(getSystemTime(), com.bg7yoz.ft8cn.FT8Common.FT8_SLOT_TIME_M);
    }

    public static long getSystemTime() {
        return delay + System.currentTimeMillis();
    }

    /**
     * 使用微软的时间服务器同步时间
     */
    public static void syncTime(AfterSyncTime afterSyncTime) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                NTPUDPClient timeClient = new NTPUDPClient();
                try {
                    InetAddress inetAddress = InetAddress.getByName("time.windows.com");
                    TimeInfo timeInfo = timeClient.getTime(inetAddress);
                    long serverTime = timeInfo.getMessage().getTransmitTimeStamp().getTime();
                    int trueDelay = (int) (serverTime - System.currentTimeMillis());

                    // 保持按 FT8 基准 15 秒做大周期对齐，FT4 是其 1/2 子周期
                    delay = trueDelay % com.bg7yoz.ft8cn.FT8Common.FT8_SLOT_TIME_MILLISECOND;

                    if (afterSyncTime != null) {
                        afterSyncTime.doAfterSyncTimer(trueDelay);
                    }
                } catch (IOException e) {
                    if (afterSyncTime != null) {
                        afterSyncTime.syncFailed(e);
                    }
                }
            }
        }).start();
    }

    public interface AfterSyncTime {
        void doAfterSyncTimer(int secTime);

        void syncFailed(IOException e);
    }
}