package com.bg7yoz.ft8cn.diagnostics;

import android.app.Activity;
import android.app.Instrumentation;

import androidx.test.platform.app.InstrumentationRegistry;

/**
 * 让长时间 native 仪器测试保持前台，避免厂商省电策略冻结无界面进程。
 */
public final class InternalForegroundTestSession {
    private static Activity activity;

    private InternalForegroundTestSession() {
    }

    public static synchronized void start() {
        if (activity != null && !activity.isFinishing()) {
            return;
        }
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        activity = instrumentation.startActivitySync(
                DeviceBenchmarkActivity.buildStartIntent(instrumentation.getTargetContext()));
        instrumentation.waitForIdleSync();
    }

    public static synchronized void stop() {
        Activity current = activity;
        activity = null;
        if (current == null) {
            return;
        }
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            if (!current.isFinishing()) {
                current.finish();
            }
        });
        instrumentation.waitForIdleSync();
    }
}
