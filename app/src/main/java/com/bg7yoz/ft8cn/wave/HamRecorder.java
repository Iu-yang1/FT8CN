package com.bg7yoz.ft8cn.wave;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 录音数据分发器。
 *
 * 它既负责从 Mic 持续取样，也负责把网络/电台输入的音频转发给多个监听器。
 * 本次重构后会显式记录“当前输入音频真实采样率”，
 * 供 FT8/FT4 解码链在进入 core 之前决定是否需要重采样到 12000Hz。
 */
public class HamRecorder {
    private static final String TAG = "HamRecorder";
    private static final int DEFAULT_SAMPLE_RATE_IN_HZ = 12000;
    private static final int MAX_CAPTURE_DURATION_MS = 300_000;
    private static final int MAX_CAPTURE_SAMPLE_RATE = 48_000;
    private static final int MAX_CAPTURE_SAMPLES = MAX_CAPTURE_DURATION_MS
            / 1000 * MAX_CAPTURE_SAMPLE_RATE;
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT;

    private volatile boolean isRunning = false;
    private final CopyOnWriteArrayList<AudioMonitor> voiceDataMonitorList =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OnCaptureStateChanged> captureStateListeners =
            new CopyOnWriteArrayList<>();
    private OnVoiceMonitorChanged onVoiceMonitorChanged = null;

    private volatile boolean isMicRecord = true;
    private final MicRecorder micRecorder = new MicRecorder();
    private int currentInputSampleRate = DEFAULT_SAMPLE_RATE_IN_HZ;

    public HamRecorder(OnVoiceMonitorChanged onVoiceMonitorChanged) {
        this.onVoiceMonitorChanged = onVoiceMonitorChanged;
    }

    /** 录音源重启或采样率变化时通知长期消费者重建固定长度窗口。 */
    public interface OnCaptureStateChanged {
        void onCaptureStateChanged(boolean running, int sampleRate);
    }

    /** 供长期消费者持有并安全取消订阅，不暴露内部采样缓冲区。 */
    public interface VoiceDataSubscription {
    }

    /** 音频线程只面向这个最小接口分发数据，避免了解具体缓冲区所有权。 */
    private interface AudioMonitor extends VoiceDataSubscription {
        void consumeFromSource(float[] data, int size, int sourceSampleRate);

        void cancel();

        boolean isOneShot();
    }

    public void addCaptureStateListener(OnCaptureStateChanged listener) {
        if (listener != null) {
            captureStateListeners.addIfAbsent(listener);
        }
    }

    public void removeCaptureStateListener(OnCaptureStateChanged listener) {
        captureStateListeners.remove(listener);
    }

    private void notifyCaptureStateChanged() {
        final boolean running = isRunning;
        final int sampleRate = getCurrentSampleRate();
        for (OnCaptureStateChanged listener : captureStateListeners) {
            listener.onCaptureStateChanged(running, sampleRate);
        }
    }

    public void setDataFromMic() {
        isMicRecord = true;
        currentInputSampleRate = micRecorder.getCurrentSampleRate();
        startRecord();
    }

    public void setDataFromLan() {
        isMicRecord = false;
        currentInputSampleRate = DEFAULT_SAMPLE_RATE_IN_HZ;
        micRecorder.stopRecord();
        notifyCaptureStateChanged();
    }

    /**
     * 兼容旧调用，外部未显式给出采样率时沿用当前输入采样率。
     */
    public void doOnWaveDataReceived(int bufferLen, float[] buffer) {
        doOnWaveDataReceived(bufferLen, buffer, currentInputSampleRate);
    }

    public void doOnWaveDataReceived(int bufferLen, float[] buffer, int sampleRate) {
        if (!isRunning || buffer == null || bufferLen <= 0) {
            return;
        }

        final int normalizedSampleRate = normalizeInputSampleRate(sampleRate);
        if (currentInputSampleRate != normalizedSampleRate) {
            currentInputSampleRate = normalizedSampleRate;
            notifyCaptureStateChanged();
        }
        // 录音热路径直接遍历稳定快照，避免每个音频块复制监听器列表。
        for (AudioMonitor monitor : voiceDataMonitorList) {
            if (monitor != null) {
                monitor.consumeFromSource(buffer, bufferLen, currentInputSampleRate);
            }
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    /** 当前生产输入的可读路由，不包含设备序列号等敏感信息。 */
    public String getCurrentInputRouteDescription() {
        return isMicRecord ? micRecorder.getInputRouteDescription() : "电台网络音频";
    }

    /** Android 并发录音策略可能让 read() 正常返回但数据全为零。 */
    public boolean isCurrentInputSystemSilenced() {
        return isMicRecord && micRecorder.isSystemSilenced();
    }

    @SuppressLint("MissingPermission")
    public void startRecord() {
        if (isMicRecord) {
            micRecorder.setOnDataListener(new MicRecorder.OnDataListener() {
                @Override
                public void onDataReceived(float[] data, int len) {
                    currentInputSampleRate = micRecorder.getCurrentSampleRate();
                    doOnWaveDataReceived(len, data, currentInputSampleRate);
                }
            });
            isRunning = true;
            if (!micRecorder.start()) {
                isRunning = false;
                notifyCaptureStateChanged();
                return;
            }
            currentInputSampleRate = micRecorder.getCurrentSampleRate();
            notifyCaptureStateChanged();
            return;
        }
        isRunning = true;
        notifyCaptureStateChanged();
    }

    private int normalizeInputSampleRate(int sampleRate) {
        if (sampleRate == 12000 || sampleRate == 24000 || sampleRate == 48000) {
            return sampleRate;
        }
        return DEFAULT_SAMPLE_RATE_IN_HZ;
    }

    private void doDataMonitorChanged() {
        if (onVoiceMonitorChanged != null) {
            onVoiceMonitorChanged.onMonitorChanged(voiceDataMonitorList.size());
        }
    }

    public void deleteVoiceDataMonitor(VoiceDataSubscription subscription) {
        if (!(subscription instanceof AudioMonitor)) {
            return;
        }
        AudioMonitor monitor = (AudioMonitor) subscription;
        monitor.cancel();
        voiceDataMonitorList.remove(monitor);
        doDataMonitorChanged();
    }

    public int getVoiceMonitorCount() {
        return voiceDataMonitorList.size();
    }

    /** 模式切换时只撤销解码用的一次性时隙，不影响频谱等长期订阅。 */
    public void cancelPendingOneShotVoiceCaptures() {
        for (AudioMonitor monitor : voiceDataMonitorList) {
            if (monitor != null && monitor.isOneShot()) {
                deleteVoiceDataMonitor(monitor);
            }
        }
    }

    public ArrayList<VoiceDataMonitor> getVoiceDataMonitors() {
        ArrayList<VoiceDataMonitor> result = new ArrayList<>();
        for (AudioMonitor monitor : voiceDataMonitorList) {
            if (monitor instanceof VoiceDataMonitor) {
                result.add((VoiceDataMonitor) monitor);
            }
        }
        return result;
    }

    public void stopRecord() {
        isRunning = false;
        micRecorder.stopRecord();
        notifyCaptureStateChanged();
    }

    /** 终止录音并撤销所有未完成的时隙订阅，避免页面重建后继续持有 PCM 缓冲区。 */
    public void release() {
        stopRecord();
        for (AudioMonitor monitor : voiceDataMonitorList) {
            if (monitor != null) {
                monitor.cancel();
            }
        }
        voiceDataMonitorList.clear();
        captureStateListeners.clear();
        onVoiceMonitorChanged = null;
    }

    public VoiceDataSubscription getVoiceData(int duration,
                                              boolean afterDoneRemove,
                                              OnGetVoiceDataDone getVoiceDataDone) {
        if (!isRunning || getVoiceDataDone == null) {
            return null;
        }

        VoiceDataMonitor dataMonitor = new VoiceDataMonitor(
                duration,
                getCurrentSampleRate(),
                this,
                afterDoneRemove,
                getVoiceDataDone
        );
        dataMonitor.voiceDataMonitor = dataMonitor;
        voiceDataMonitorList.add(dataMonitor);
        doDataMonitorChanged();
        return dataMonitor;
    }

    /**
     * Q65 长时隙入口：高采样率数据按录音小块抽取，最终只常驻目标采样率缓冲区。
     */
    public VoiceDataMonitor getVoiceDataAtSampleRate(int duration,
                                                     int targetSampleRate,
                                                     boolean afterDoneRemove,
                                                     OnGetVoiceDataDone getVoiceDataDone) {
        if (!isRunning || getVoiceDataDone == null) {
            return null;
        }
        final int inputSampleRate = getCurrentSampleRate();
        try {
            VoiceDataMonitor dataMonitor = new VoiceDataMonitor(
                    duration,
                    inputSampleRate,
                    targetSampleRate,
                    this,
                    afterDoneRemove,
                    getVoiceDataDone
            );
            dataMonitor.voiceDataMonitor = dataMonitor;
            voiceDataMonitorList.add(dataMonitor);
            doDataMonitorChanged();
            return dataMonitor;
        } catch (RuntimeException error) {
            Log.e(TAG, "create streaming voice monitor failed: inputRate="
                    + inputSampleRate + ", targetRate=" + targetSampleRate, error);
            return null;
        }
    }

    /**
     * Q65 生产入口：完整 12 kHz 时隙只存在于 native，Java 仅处理 AudioRecord 小块。
     * 回调取得缓冲区所有权，必须在解码结束或任务取消时关闭。
     */
    public VoiceDataSubscription getNativeVoiceDataAtSampleRate(
            int duration,
            int targetSampleRate,
            boolean afterDoneRemove,
            OnGetNativeVoiceDataDone getVoiceDataDone) {
        if (!isRunning || getVoiceDataDone == null || !afterDoneRemove) {
            return null;
        }
        final int inputSampleRate = getCurrentSampleRate();
        try {
            NativeVoiceDataMonitor monitor = new NativeVoiceDataMonitor(
                    duration,
                    inputSampleRate,
                    targetSampleRate,
                    this,
                    getVoiceDataDone);
            voiceDataMonitorList.add(monitor);
            doDataMonitorChanged();
            return monitor;
        } catch (RuntimeException error) {
            Log.e(TAG, "create native streaming voice monitor failed: inputRate="
                    + inputSampleRate + ", targetRate=" + targetSampleRate, error);
            return null;
        }
    }

    public int getCurrentSampleRate() {
        if (isMicRecord) {
            currentInputSampleRate = micRecorder.getCurrentSampleRate();
        }
        return normalizeInputSampleRate(currentInputSampleRate);
    }

    /**
     * 当采样率配置变化后，按当前输入源重建录音链路。
     * Mic 会重新创建 AudioRecord；网络源只刷新采样率标记。
     */
    public void refreshCurrentAudioSource() {
        final boolean wasRunning = isRunning;
        stopRecord();
        if (isMicRecord) {
            currentInputSampleRate = micRecorder.getCurrentSampleRate();
        } else {
            currentInputSampleRate = DEFAULT_SAMPLE_RATE_IN_HZ;
        }
        if (wasRunning) {
            startRecord();
        }
    }

    static class VoiceDataMonitor implements AudioMonitor {
        private final float[] voiceData;
        private int dataCount;
        private int inputDataCount;
        private final int expectedInputSamples;
        private final int inputSampleRate;
        private final int targetSampleRate;
        private final HamRecorder hamRecorder;
        private final boolean afterDoneRemove;
        private final OnGetVoiceDataDone onGetVoiceDataDone;
        private FtxStreamingResampler streamingResampler;
        private boolean closed;
        public OnHamRecord onHamRecord;
        public VoiceDataMonitor voiceDataMonitor = null;

        private static int resolveVoiceBufferLength(int durationMs, int sampleRate) {
            if (durationMs <= 0 || durationMs > MAX_CAPTURE_DURATION_MS
                    || sampleRate <= 0 || sampleRate > MAX_CAPTURE_SAMPLE_RATE) {
                throw new IllegalArgumentException(
                        "unsupported capture size: durationMs=" + durationMs
                                + ", sampleRate=" + sampleRate);
            }
            long requestedSamples = (long) durationMs
                    * (long) sampleRate
                    / 1000L;
            if (requestedSamples <= 0L || requestedSamples > MAX_CAPTURE_SAMPLES) {
                throw new IllegalArgumentException(
                        "capture sample count overflow: " + requestedSamples);
            }
            return (int) requestedSamples;
        }

        public VoiceDataMonitor(int duration,
                                int sampleRate,
                                HamRecorder hamRecorder,
                                boolean afterDoneRemove,
                                OnGetVoiceDataDone onGetVoiceDataDone) {
            this(duration,
                    sampleRate,
                    sampleRate,
                    hamRecorder,
                    afterDoneRemove,
                    onGetVoiceDataDone);
        }

        public VoiceDataMonitor(int duration,
                                int inputSampleRate,
                                int targetSampleRate,
                                HamRecorder hamRecorder,
                                boolean afterDoneRemove,
                                OnGetVoiceDataDone onGetVoiceDataDone) {
            dataCount = 0;
            inputDataCount = 0;
            this.inputSampleRate = inputSampleRate <= 0
                    ? DEFAULT_SAMPLE_RATE_IN_HZ
                    : inputSampleRate;
            this.targetSampleRate = targetSampleRate <= 0
                    ? this.inputSampleRate
                    : targetSampleRate;
            this.hamRecorder = hamRecorder;
            this.afterDoneRemove = afterDoneRemove;
            this.onGetVoiceDataDone = onGetVoiceDataDone;
            if (this.inputSampleRate != this.targetSampleRate && !afterDoneRemove) {
                throw new IllegalArgumentException(
                        "streaming resample monitor must be one-shot");
            }
            expectedInputSamples = resolveVoiceBufferLength(duration, this.inputSampleRate);
            final int requestedSamples = resolveVoiceBufferLength(duration, this.targetSampleRate);
            voiceData = new float[requestedSamples];
            if (this.inputSampleRate != this.targetSampleRate) {
                streamingResampler = new FtxStreamingResampler(
                        this.inputSampleRate,
                        this.targetSampleRate);
            }
            Log.d(TAG, String.format(
                    "create voice monitor: durationMs=%d, inputRate=%d, targetRate=%d, inputSamples=%d, outputSamples=%d, streaming=%s, afterDoneRemove=%s",
                    duration,
                    this.inputSampleRate,
                    this.targetSampleRate,
                    expectedInputSamples,
                    requestedSamples,
                    streamingResampler == null ? "N" : "Y",
                    afterDoneRemove ? "Y" : "N"
            ));

            onHamRecord = (data, size) -> consumeFromSource(data, size, this.inputSampleRate);
        }

        @Override
        public void consumeFromSource(float[] data, int size, int sourceSampleRate) {
            if (sourceSampleRate != inputSampleRate) {
                Log.w(TAG, "capture sample rate changed mid-slot: expected="
                        + inputSampleRate + ", actual=" + sourceSampleRate);
                hamRecorder.deleteVoiceDataMonitor(voiceDataMonitor);
                return;
            }
            consume(data, size);
        }

        private synchronized void consume(float[] data, int size) {
            if (closed || data == null || size <= 0) {
                return;
            }
            int available = Math.min(size, data.length);
            if (streamingResampler != null) {
                consumeStreaming(data, available);
                return;
            }
            int offset = 0;
            while (!closed && offset < available) {
                int copyCount = Math.min(available - offset, voiceData.length - dataCount);
                System.arraycopy(data, offset, voiceData, dataCount, copyCount);
                offset += copyCount;
                dataCount += copyCount;
                inputDataCount += copyCount;
                if (dataCount == voiceData.length) {
                    publishCompletedSlot();
                    if (!afterDoneRemove && !closed) {
                        dataCount = 0;
                        inputDataCount = 0;
                    }
                }
            }
        }

        private void consumeStreaming(float[] data, int available) {
            int accepted = Math.min(available, expectedInputSamples - inputDataCount);
            if (accepted <= 0) {
                return;
            }
            try {
                int written = streamingResampler.process(
                        data,
                        0,
                        accepted,
                        voiceData,
                        dataCount,
                        voiceData.length - dataCount);
                inputDataCount += accepted;
                dataCount += written;
                if (inputDataCount == expectedInputSamples) {
                    dataCount += streamingResampler.finish(
                            voiceData,
                            dataCount,
                            voiceData.length - dataCount);
                    if (dataCount != voiceData.length) {
                        throw new IllegalStateException(
                                "stream output length mismatch: " + dataCount + " != " + voiceData.length);
                    }
                    publishCompletedSlot();
                }
            } catch (RuntimeException error) {
                Log.e(TAG, "streaming voice monitor failed", error);
                hamRecorder.deleteVoiceDataMonitor(voiceDataMonitor);
            }
        }

        private void publishCompletedSlot() {
            closeResampler();
            try {
                onGetVoiceDataDone.onGetDone(voiceData);
            } finally {
                if (afterDoneRemove) {
                    hamRecorder.deleteVoiceDataMonitor(voiceDataMonitor);
                }
            }
        }

        private void closeResampler() {
            if (streamingResampler != null) {
                streamingResampler.close();
                streamingResampler = null;
            }
        }

        @Override
        public synchronized void cancel() {
            if (!closed) {
                closed = true;
                closeResampler();
            }
        }

        @Override
        public boolean isOneShot() {
            return afterDoneRemove;
        }
    }

    /** Q65 一次性 native 时隙收集器，不创建完整 Java 输出数组。 */
    private static final class NativeVoiceDataMonitor implements AudioMonitor {
        private final int expectedInputSamples;
        private final int expectedOutputSamples;
        private final int inputSampleRate;
        private final HamRecorder hamRecorder;
        private final OnGetNativeVoiceDataDone callback;
        private FtxStreamingResampler streamingResampler;
        private NativeFloatBuffer output;
        private int inputDataCount;
        private boolean closed;

        NativeVoiceDataMonitor(int duration,
                               int inputSampleRate,
                               int targetSampleRate,
                               HamRecorder hamRecorder,
                               OnGetNativeVoiceDataDone callback) {
            this.inputSampleRate = inputSampleRate;
            this.hamRecorder = hamRecorder;
            this.callback = callback;
            expectedInputSamples = VoiceDataMonitor.resolveVoiceBufferLength(
                    duration, inputSampleRate);
            expectedOutputSamples = VoiceDataMonitor.resolveVoiceBufferLength(
                    duration, targetSampleRate);
            output = new NativeFloatBuffer(expectedOutputSamples);
            if (inputSampleRate != targetSampleRate) {
                streamingResampler = new FtxStreamingResampler(inputSampleRate, targetSampleRate);
            }
            Log.d(TAG, String.format(
                    "create native voice monitor: durationMs=%d, inputRate=%d, targetRate=%d, inputSamples=%d, outputSamples=%d, streaming=%s",
                    duration,
                    inputSampleRate,
                    targetSampleRate,
                    expectedInputSamples,
                    expectedOutputSamples,
                    streamingResampler == null ? "N" : "Y"));
        }

        @Override
        public void consumeFromSource(float[] data, int size, int sourceSampleRate) {
            if (sourceSampleRate != inputSampleRate) {
                Log.w(TAG, "native capture sample rate changed mid-slot: expected="
                        + inputSampleRate + ", actual=" + sourceSampleRate);
                hamRecorder.deleteVoiceDataMonitor(this);
                return;
            }
            consume(data, size);
        }

        private synchronized void consume(float[] data, int size) {
            if (closed || output == null || data == null || size <= 0) {
                return;
            }
            int accepted = Math.min(Math.min(size, data.length),
                    expectedInputSamples - inputDataCount);
            if (accepted <= 0) {
                return;
            }
            try {
                if (streamingResampler == null) {
                    output.append(data, 0, accepted);
                } else {
                    int outputOffset = output.size();
                    streamingResampler.process(
                            data,
                            0,
                            accepted,
                            output,
                            outputOffset,
                            output.capacity() - outputOffset);
                }
                inputDataCount += accepted;
                if (inputDataCount == expectedInputSamples) {
                    finishAndPublish();
                }
            } catch (RuntimeException error) {
                Log.e(TAG, "native streaming voice monitor failed", error);
                hamRecorder.deleteVoiceDataMonitor(this);
            }
        }

        private void finishAndPublish() {
            if (streamingResampler != null) {
                int outputOffset = output.size();
                streamingResampler.finish(
                        output,
                        outputOffset,
                        output.capacity() - outputOffset);
            }
            if (output.size() != expectedOutputSamples) {
                throw new IllegalStateException(
                        "native stream output length mismatch: " + output.size()
                                + " != " + expectedOutputSamples);
            }
            closeResampler();
            NativeFloatBuffer completed = output;
            output = null;
            closed = true;
            try {
                callback.onGetDone(completed);
            } catch (RuntimeException error) {
                completed.close();
                throw error;
            } finally {
                hamRecorder.deleteVoiceDataMonitor(this);
            }
        }

        private void closeResampler() {
            if (streamingResampler != null) {
                streamingResampler.close();
                streamingResampler = null;
            }
        }

        @Override
        public synchronized void cancel() {
            if (closed) {
                return;
            }
            closed = true;
            closeResampler();
            if (output != null) {
                output.close();
                output = null;
            }
        }


        @Override
        public boolean isOneShot() {
            return true;
        }
    }

    public static String saveDataToFile(byte[] data) {
        String audioFileName = null;
        File recordingFile;
        try {
            recordingFile = File.createTempFile("Audio", ".wav", null);
            audioFileName = recordingFile.getPath();

            DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(audioFileName))
            );
            new WriteWavHeader(data.length, DEFAULT_SAMPLE_RATE_IN_HZ, channelConfig, audioFormat)
                    .writeHeader(dos);
            for (byte datum : data) {
                dos.write(datum);
            }
            Log.d(TAG, String.format(
                    "生成临时音频文件完成(%d字节, %.2f秒): %s",
                    data.length + 44,
                    ((float) data.length / 2 / DEFAULT_SAMPLE_RATE_IN_HZ),
                    audioFileName
            ));
            dos.close();
        } catch (IOException e) {
            Log.e(TAG, String.format("生成临时文件错误: %s", e.getMessage()));
        }

        return audioFileName;
    }

    public static int[] byteDataTo16BitData(byte[] buffer) {
        int[] data = new int[buffer.length / 2];
        for (int i = 0; i < buffer.length / 2; i++) {
            int res = (buffer[i * 2] & 0x000000FF) | (((int) buffer[i * 2 + 1]) << 8);
            data[i] = res;
        }
        return data;
    }

    public static float[] getFloatFromBytes(byte[] bytes) {
        float[] floats = new float[bytes.length / 4];
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < floats.length; i++) {
            try {
                floats[i] = dis.readFloat();
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
        try {
            dis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return floats;
    }
}

