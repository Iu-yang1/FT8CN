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

public class MicRecorder {
    private static final String TAG = "MicRecorder";
    private static final int DEFAULT_SAMPLE_RATE_IN_HZ = 12000;
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT;

    private int bufferSize = 0;
    private int currentSampleRateInHz = DEFAULT_SAMPLE_RATE_IN_HZ;
    private AudioRecord audioRecord = null;
    private boolean isRunning = false;
    private OnDataListener onDataListener;

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

    public void start() {
        if (isRunning) {
            return;
        }
        if (!ensureAudioRecord()) {
            ToastMessage.show(String.format(
                    GeneralVariables.getStringFromResource(R.string.recorder_cannot_record),
                    "AudioRecord init failed"
            ));
            return;
        }

        final float[] buffer = new float[Math.max(1, bufferSize / 4)];
        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            ToastMessage.show(String.format(
                    GeneralVariables.getStringFromResource(R.string.recorder_cannot_record),
                    e.getMessage()
            ));
            Log.d(TAG, "startRecord: " + e.getMessage());
            return;
        }

        isRunning = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    if (audioRecord == null
                            || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        isRunning = false;
                        Log.d(TAG, String.format(
                                "录音失败，状态码=%d, sampleRate=%d",
                                audioRecord == null ? -1 : audioRecord.getRecordingState(),
                                currentSampleRateInHz
                        ));
                        break;
                    }

                    int bufferReadResult = audioRecord.read(
                            buffer,
                            0,
                            buffer.length,
                            AudioRecord.READ_BLOCKING
                    );

                    if (onDataListener != null && bufferReadResult > 0) {
                        onDataListener.onDataReceived(buffer, bufferReadResult);
                    }
                }

                try {
                    if (audioRecord != null
                            && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                } catch (Exception e) {
                    ToastMessage.show(String.format(
                            GeneralVariables.getStringFromResource(R.string.recorder_stop_record_error),
                            e.getMessage()
                    ));
                    Log.d(TAG, "stopRecord: " + e.getMessage());
                }
            }
        }).start();
    }

    public void stopRecord() {
        isRunning = false;
        releaseAudioRecord();
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
