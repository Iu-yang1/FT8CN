package com.bg7yoz.ft8cn.ft8listener;

import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;

import java.util.Locale;

/**
 * 记录 scheduler worker 与长期 native decoder/bridge slot 的所有权关系。
 *
 * 当前 native 入口仍串行，worker 0 会按 mode 持有多个 handle；增加 worker 前必须先把
 * 每个 worker 固定绑定到独立 handle、bridge context 和 result buffer。
 */
final class NativeDecodeWorkerContext {
    enum LifecycleState {
        CREATED,
        CONFIGURED,
        PROCESSING,
        RESULTS_READY,
        RESET,
        DESTROYED
    }

    private static final String TRACE_TAG = "WSJTX3CallbackSlot";

    final int workerId;
    final int mode;
    long nativeDecoderHandle;
    int bridgeContextId;
    int callbackSlotId;
    int expectedSamples;
    long traceId;
    LifecycleState lifecycleState = LifecycleState.DESTROYED;

    NativeDecodeWorkerContext(int workerId, int mode) {
        this.workerId = workerId;
        this.mode = mode;
    }

    boolean matches(int requestedExpectedSamples) {
        return nativeDecoderHandle != 0L && expectedSamples == requestedExpectedSamples;
    }

    void created(long handle, int bridgeContext, int requestedExpectedSamples) {
        nativeDecoderHandle = handle;
        bridgeContextId = bridgeContext;
        callbackSlotId = bridgeContext;
        expectedSamples = requestedExpectedSamples;
        lifecycleState = LifecycleState.CREATED;
        logLifecycle("create", 0);
    }

    void configure(long requestTraceId) {
        traceId = requestTraceId;
        if (lifecycleState == LifecycleState.RESULTS_READY) {
            lifecycleState = LifecycleState.RESET;
            logLifecycle("reset", 0);
        }
        lifecycleState = LifecycleState.CONFIGURED;
        logLifecycle("configure", 0);
    }

    void processing() {
        lifecycleState = LifecycleState.PROCESSING;
        logLifecycle("process", 0);
    }

    void resultsReady(int resultCount) {
        lifecycleState = LifecycleState.RESULTS_READY;
        logLifecycle("get-result", resultCount);
    }

    void destroyed() {
        lifecycleState = LifecycleState.DESTROYED;
        logLifecycle("destroy", 0);
        nativeDecoderHandle = 0L;
        bridgeContextId = 0;
        callbackSlotId = 0;
        expectedSamples = 0;
        traceId = 0L;
    }

    private void logLifecycle(String operation, int resultCount) {
        if (!Log.isLoggable(TRACE_TAG, Log.DEBUG)) {
            return;
        }
        int mismatch = bridgeContextId > 0 && callbackSlotId != bridgeContextId ? 1 : 0;
        Log.i(TRACE_TAG, String.format(Locale.US,
                "slotLifecycle operation=%s workerId=%d handle=%d callbackSlot=%d "
                        + "bridgeContextId=%d mode=%s expectedSamples=%d traceId=%d state=%s "
                        + "resultCount=%d mismatch=%d fallbackReason=%s",
                operation,
                workerId,
                nativeDecoderHandle,
                callbackSlotId,
                bridgeContextId,
                FT8Common.modeToString(mode),
                expectedSamples,
                traceId,
                lifecycleState,
                resultCount,
                mismatch,
                mismatch == 0 ? "none" : "active-context"));
    }
}
