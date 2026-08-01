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
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.R;

import java.util.Locale;

public class SampleDecodeForegroundService extends Service {
    private static final String TAG = "SampleDecodeService";
    static final String ACTION_RUN = "com.bg7yoz.ft8cn.ft4.action.RUN_SAMPLE_DECODE";
    private static final String CHANNEL_ID = "sample_decode_debug";
    private static final int NOTIFICATION_ID = 46521;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L;

    static Intent buildStartIntent(Context context, Intent sourceIntent) {
        Intent serviceIntent = new Intent(context, SampleDecodeForegroundService.class);
        serviceIntent.setAction(ACTION_RUN);
        if (sourceIntent != null && sourceIntent.getExtras() != null) {
            serviceIntent.putExtras(sourceIntent.getExtras());
        }
        return serviceIntent;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_RUN.equals(intent.getAction())) {
            Log.w(TAG, "ignore unexpected service intent: " + intent);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Log.i(TAG, String.format(Locale.US,
                "start foreground decode service mode=%s engine=%s q65Submode=%d q65TrPeriod=%d",
                intent.getStringExtra("mode"),
                intent.getStringExtra("engine"),
                intent.getIntExtra("q65_submode", FT8Common.Q65_SUBMODE_A),
                intent.getIntExtra("q65_tr_period", FT8Common.Q65_DEFAULT_TR_PERIOD_SECONDS)));
        startForeground(NOTIFICATION_ID, buildNotification(intent));

        new Thread(null, () -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                wakeLock = acquireWakeLock();
                SampleDecodeReceiver.runDecodeRequest(getApplicationContext(), intent);
            } catch (Throwable throwable) {
                Log.e(TAG, "sample decode foreground service failed", throwable);
            } finally {
                releaseWakeLock(wakeLock);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelfResult(startId);
            }
        }, "sample-decode-service-" + startId, SampleDecodeReceiver.DECODE_THREAD_STACK_BYTES).start();

        return START_NOT_STICKY;
    }

    private PowerManager.WakeLock acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return null;
        }
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":sample-decode-debug"
        );
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        return wakeLock;
    }

    private void releaseWakeLock(@Nullable PowerManager.WakeLock wakeLock) {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private Notification buildNotification(Intent intent) {
        ensureNotificationChannel();
        int mode = parseMode(intent.getStringExtra("mode"));
        int q65Submode = intent.getIntExtra("q65_submode", FT8Common.Q65_SUBMODE_A);
        int q65TrPeriodSeconds = intent.getIntExtra("q65_tr_period", FT8Common.Q65_DEFAULT_TR_PERIOD_SECONDS);
        String engine = intent.getStringExtra("engine");
        if (engine == null || engine.trim().isEmpty()) {
            engine = "listener";
        }

        String modeLabel = mode == FT8Common.Q65_MODE
                ? FT8Common.getQ65ModeLabel(q65Submode, q65TrPeriodSeconds)
                : FT8Common.modeToString(mode);
        String content = "Q65 debug decode running: engine=" + engine + ", mode=" + modeLabel;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ft8cn_icon)
                .setContentTitle("FT8CN Debug Decode")
                .setContentText(content)
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
                "Sample Decode Debug",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("用于保持样本解码调试任务以前台方式执行");
        manager.createNotificationChannel(channel);
    }

    private int parseMode(String modeText) {
        if (modeText == null) {
            return FT8Common.FT8_MODE;
        }
        String normalized = modeText.trim().toLowerCase(Locale.US);
        if ("ft4".equals(normalized)) {
            return FT8Common.FT4_MODE;
        }
        if ("q65".equals(normalized)) {
            return FT8Common.Q65_MODE;
        }
        return FT8Common.FT8_MODE;
    }
}
