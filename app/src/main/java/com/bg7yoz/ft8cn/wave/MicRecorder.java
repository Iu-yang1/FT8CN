package com.bg7yoz.ft8cn.wave;

/**
 * 使用 Mic 录音的封装。
 *
 * 这里让录音采样率跟随前端配置变化，
 * 这样 FT8/FT4 解码链才能知道输入音频真实是 12k / 24k / 48k。
 */

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.util.concurrent.atomic.AtomicBoolean;

public class MicRecorder {
    private static final String TAG = "MicRecorder";
    private static final int DEFAULT_SAMPLE_RATE_IN_HZ = 12000;
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT;

    private int bufferSize = 0;
    private volatile int currentSampleRateInHz = DEFAULT_SAMPLE_RATE_IN_HZ;
    private final Object recorderLock = new Object();
    private volatile AudioRecord audioRecord = null;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile OnDataListener onDataListener;

    public interface OnDataListener {
        void onDataReceived(float[] data, int len);
    }

    @SuppressLint("MissingPermission")
    public MicRecorder() {
    }

    private int normalizeRequestedSampleRate(int sampleRate) {
        if (sampleRate == 12000 || sampleRate == 24000 || sampleRate == 48000) {
            return sampleRate;
        }
        return DEFAULT_SAMPLE_RATE_IN_HZ;
    }

    @SuppressLint("MissingPermission")
    private boolean ensureAudioRecord() {
        final int desiredSampleRate = normalizeRequestedSampleRate(GeneralVariables.audioSampleRate);
        if (audioRecord != null && currentSampleRateInHz == desiredSampleRate) {
            return true;
        }

        releaseAudioRecord();
        currentSampleRateInHz = desiredSampleRate;
        bufferSize = AudioRecord.getMinBufferSize(currentSampleRateInHz, channelConfig, audioFormat);
        if (bufferSize <= 0) {
            Log.e(TAG, "ensureAudioRecord: invalid bufferSize=" + bufferSize
                    + ", sampleRate=" + currentSampleRateInHz);
            return false;
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                currentSampleRateInHz,
                channelConfig,
                audioFormat,
                bufferSize
        );
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "ensureAudioRecord: AudioRecord init failed, sampleRate=" + currentSampleRateInHz);
            releaseAudioRecord();
            return false;
        }
        return true;
    }

    private void releaseAudioRecord() {
        if (audioRecord == null) {
            return;
        }

        try {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
        } catch (Exception e) {
            Log.d(TAG, "releaseAudioRecord stop: " + e.getMessage());
        }

        audioRecord.release();
        audioRecord = null;
    }

    public boolean start() {
        final AudioRecord recorder;
        final int readBufferSize;
        synchronized (recorderLock) {
            if (isRunning.get()) {
                return true;
            }
            if (!ensureAudioRecord()) {
                ToastMessage.show(String.format(
                        GeneralVariables.getStringFromResource(R.string.recorder_cannot_record),
                        "AudioRecord init failed"
                ));
                return false;
            }
            recorder = audioRecord;
            readBufferSize = Math.max(1, bufferSize / 4);
            try {
                recorder.startRecording();
            } catch (Exception e) {
                releaseAudioRecord();
                ToastMessage.show(String.format(
                        GeneralVariables.getStringFromResource(R.string.recorder_cannot_record),
                        e.getMessage()
                ));
                Log.d(TAG, "startRecord: " + e.getMessage());
                return false;
            }
            isRunning.set(true);
        }

        Thread recordThread = new Thread(
                () -> recordLoop(recorder, new float[readBufferSize]),
                "FT8CN-AudioRecord");
        recordThread.start();
        return true;
    }

    public void stopRecord() {
        isRunning.set(false);
        final AudioRecord recorder;
        synchronized (recorderLock) {
            recorder = audioRecord;
            audioRecord = null;
        }
        stopAndRelease(recorder);
    }

    private void recordLoop(AudioRecord recorder, float[] buffer) {
        try {
            // 录音重启后，旧线程只能退出自己的 recorder，不能干扰新会话。
            while (isRunning.get() && audioRecord == recorder) {
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.d(TAG, String.format(
                            "录音停止，状态码=%d, sampleRate=%d",
                            recorder.getRecordingState(),
                            currentSampleRateInHz));
                    break;
                }
                final int count = recorder.read(
                        buffer,
                        0,
                        buffer.length,
                        AudioRecord.READ_BLOCKING);
                final OnDataListener listener = onDataListener;
                if (listener != null && count > 0
                        && isRunning.get() && audioRecord == recorder) {
                    listener.onDataReceived(buffer, count);
                }
            }
        } catch (RuntimeException error) {
            if (isRunning.get() && audioRecord == recorder) {
                Log.e(TAG, "录音线程异常", error);
            }
        } finally {
            boolean releaseRecorder = false;
            synchronized (recorderLock) {
                if (audioRecord == recorder) {
                    audioRecord = null;
                    isRunning.set(false);
                    releaseRecorder = true;
                }
            }
            if (releaseRecorder) {
                stopAndRelease(recorder);
            }
        }
    }

    private void stopAndRelease(AudioRecord recorder) {
        if (recorder == null) {
            return;
        }
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
        } catch (RuntimeException error) {
            Log.d(TAG, "stopRecord: " + error.getMessage());
        }
        try {
            recorder.release();
        } catch (RuntimeException error) {
            Log.d(TAG, "releaseRecord: " + error.getMessage());
        }
    }

    public OnDataListener getOnDataListener() {
        return onDataListener;
    }

    public void setOnDataListener(OnDataListener onDataListener) {
        this.onDataListener = onDataListener;
    }

    public int getCurrentSampleRate() {
        return currentSampleRateInHz;
    }
}

