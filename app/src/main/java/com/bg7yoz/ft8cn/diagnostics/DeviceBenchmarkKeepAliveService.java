package com.bg7yoz.ft8cn.diagnostics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bg7yoz.ft8cn.R;

/**
 * 仅在内部真机基准期间防止厂商省电策略冻结测试进程。
 *
 * <p>该服务不触发解码，也不读取用户配置；普通应用流程不会启动它。</p>
 */
public final class DeviceBenchmarkKeepAliveService extends Service {
    static final String ACTION_KEEP_ALIVE =
            "com.bg7yoz.ft8cn.ft4.action.DEVICE_BENCHMARK_KEEP_ALIVE";

    private static final String CHANNEL_ID = "device_benchmark_internal";
    private static final int NOTIFICATION_ID = 46522;
    private static final long WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L;

    private PowerManager.WakeLock wakeLock;

    static Intent buildStartIntent(Context context) {
        return new Intent(context, DeviceBenchmarkKeepAliveService.class)
                .setAction(ACTION_KEEP_ALIVE);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_KEEP_ALIVE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":device-benchmark"
        );
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private Notification buildNotification() {
        ensureNotificationChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ft8cn_icon)
                .setContentTitle("FT8CN 内部真机测试")
                .setContentText("正在执行解码正确性与性能验证")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "内部真机测试",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("仅在内部解码基准运行期间防止系统挂起测试进程");
        manager.createNotificationChannel(channel);
    }
}
