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
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT;

    private boolean isRunning = false;
    private final ArrayList<VoiceDataMonitor> voiceDataMonitorList = new ArrayList<>();
    private OnVoiceMonitorChanged onVoiceMonitorChanged = null;

    private boolean isMicRecord = true;
    private final MicRecorder micRecorder = new MicRecorder();
    private int currentInputSampleRate = DEFAULT_SAMPLE_RATE_IN_HZ;

    public HamRecorder(OnVoiceMonitorChanged onVoiceMonitorChanged) {
        this.onVoiceMonitorChanged = onVoiceMonitorChanged;
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

        currentInputSampleRate = normalizeInputSampleRate(sampleRate);
        ArrayList<VoiceDataMonitor> monitors = new ArrayList<>(voiceDataMonitorList);
        for (VoiceDataMonitor monitor : monitors) {
            if (monitor != null && voiceDataMonitorList.contains(monitor)) {
                monitor.onHamRecord.OnReceiveData(buffer, bufferLen);
            }
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    @SuppressLint("MissingPermission")
    public void startRecord() {
        if (isMicRecord) {
            micRecorder.start();
            currentInputSampleRate = micRecorder.getCurrentSampleRate();
            micRecorder.setOnDataListener(new MicRecorder.OnDataListener() {
                @Override
                public void onDataReceived(float[] data, int len) {
                    currentInputSampleRate = micRecorder.getCurrentSampleRate();
                    doOnWaveDataReceived(len, data, currentInputSampleRate);
                }
            });
        }
        isRunning = true;
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

    public void deleteVoiceDataMonitor(VoiceDataMonitor monitor) {
        voiceDataMonitorList.remove(monitor);
        doDataMonitorChanged();
    }

    public int getVoiceMonitorCount() {
        return voiceDataMonitorList.size();
    }

    public ArrayList<VoiceDataMonitor> getVoiceDataMonitors() {
        return this.voiceDataMonitorList;
    }

    public void stopRecord() {
        micRecorder.stopRecord();
        isRunning = false;
    }

    public VoiceDataMonitor getVoiceData(int duration,
                                         boolean afterDoneRemove,
                                         OnGetVoiceDataDone getVoiceDataDone) {
        if (!isRunning) {
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

    static class VoiceDataMonitor {
        private final float[] voiceData;
        private int dataCount;
        public OnHamRecord onHamRecord;
        public VoiceDataMonitor voiceDataMonitor = null;

        private static int resolveVoiceBufferLength(int durationMs, int sampleRate) {
            long requestedSamples = (long) Math.max(0, durationMs)
                    * (long) Math.max(1, sampleRate)
                    / 1000L;
            if (requestedSamples <= 0L) {
                return 1;
            }
            if (requestedSamples > Integer.MAX_VALUE) {
                Log.w(TAG, String.format(
                        "voice monitor sample request overflow, durationMs=%d, sampleRate=%d, requestedSamples=%d",
                        durationMs,
                        sampleRate,
                        requestedSamples
                ));
                return Integer.MAX_VALUE;
            }
            return (int) requestedSamples;
        }

        public VoiceDataMonitor(int duration,
                                int sampleRate,
                                HamRecorder hamRecorder,
                                boolean afterDoneRemove,
                                OnGetVoiceDataDone onGetVoiceDataDone) {
            dataCount = 0;
            final int normalizedSampleRate = sampleRate <= 0 ? DEFAULT_SAMPLE_RATE_IN_HZ : sampleRate;
            final int requestedSamples = resolveVoiceBufferLength(duration, normalizedSampleRate);
            voiceData = new float[requestedSamples];
            Log.d(TAG, String.format(
                    "create voice monitor: durationMs=%d, sampleRate=%d, requestedSamples=%d, afterDoneRemove=%s",
                    duration,
                    normalizedSampleRate,
                    requestedSamples,
                    afterDoneRemove ? "Y" : "N"
            ));

            onHamRecord = new OnHamRecord() {
                @Override
                public void OnReceiveData(float[] data, int size) {
                    int remainingSize = size + dataCount - voiceData.length;
                    for (int i = 0; (i < size) && (dataCount < voiceData.length); i++) {
                        voiceData[dataCount] = data[i];
                        dataCount++;
                    }

                    if (dataCount >= voiceData.length) {
                        onGetVoiceDataDone.onGetDone(voiceData);
                        if (afterDoneRemove) {
                            hamRecorder.deleteVoiceDataMonitor(voiceDataMonitor);
                        } else {
                            dataCount = 0;
                            if (remainingSize > 0) {
                                float[] remainingData = new float[remainingSize];
                                System.arraycopy(data, size - remainingSize, remainingData, 0, remainingSize);
                                OnReceiveData(remainingData, remainingSize);
                            }
                        }
                    }
                }
            };
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

