package com.bg7yoz.ft8cn.wave;

/**
 * 使用 Mic 录音的封装。
 *
 * 这里让录音采样率跟随前端配置变化，
 * 这样 FT8/FT4 解码链才能知道输入音频真实是 12k / 24k / 48k。
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
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
    private volatile boolean systemSilenced = false;
    private volatile String inputRouteDescription = "录音设备待连接";
    private volatile long lastInputStatusCheckMillis = 0L;

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
        preferExternalInput(audioRecord);
        updateInputStatus(audioRecord, true);
        return true;
    }

    /**
     * 电台通常通过 USB 声卡或有线输入接入。存在外接输入时显式选择它，
     * 避免部分厂商系统仍把 DEFAULT 路由到手机背部麦克风。
     */
    private void preferExternalInput(AudioRecord recorder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        AudioManager audioManager = getAudioManager();
        if (audioManager == null) {
            return;
        }
        AudioDeviceInfo preferred = null;
        int preferredPriority = Integer.MIN_VALUE;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int priority = externalInputPriority(device.getType());
            if (priority > preferredPriority) {
                preferred = device;
                preferredPriority = priority;
            }
        }
        if (preferred != null && preferredPriority > 0) {
            boolean accepted = recorder.setPreferredDevice(preferred);
            Log.i(TAG, "外接录音输入 " + describeDevice(preferred) + "，路由请求=" + accepted);
        }
    }

    private int externalInputPriority(int type) {
        if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
            return 40;
        }
        if (type == AudioDeviceInfo.TYPE_LINE_ANALOG || type == AudioDeviceInfo.TYPE_LINE_DIGITAL) {
            return 30;
        }
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            return 20;
        }
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return 10;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            return 10;
        }
        return 0;
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
            updateInputStatus(recorder, true);
            Log.i(TAG, "录音已启动，sampleRate=" + currentSampleRateInHz
                    + "，route=" + inputRouteDescription
                    + "，systemSilenced=" + systemSilenced);
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
                updateInputStatus(recorder, false);
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
        systemSilenced = false;
        inputRouteDescription = "录音已停止";
    }

    /** Android 10 起可直接识别并发录音策略是否正在向本应用返回静音数据。 */
    private void updateInputStatus(AudioRecord recorder, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastInputStatusCheckMillis < 1_000L) {
            return;
        }
        lastInputStatusCheckMillis = now;

        AudioDeviceInfo routedDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? recorder.getRoutedDevice() : null;
        boolean silenced = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioManager audioManager = getAudioManager();
            if (audioManager != null) {
                for (AudioRecordingConfiguration configuration
                        : audioManager.getActiveRecordingConfigurations()) {
                    if (configuration.getClientAudioSessionId() == recorder.getAudioSessionId()) {
                        silenced = configuration.isClientSilenced();
                        if (configuration.getAudioDevice() != null) {
                            routedDevice = configuration.getAudioDevice();
                        }
                        break;
                    }
                }
            }
        }

        boolean changed = silenced != systemSilenced;
        String route = routedDevice == null ? "系统默认输入" : describeDevice(routedDevice);
        changed |= !route.equals(inputRouteDescription);
        systemSilenced = silenced;
        inputRouteDescription = route;
        if (changed || force) {
            Log.i(TAG, "录音输入状态 route=" + route + "，systemSilenced=" + silenced);
        }
    }

    private AudioManager getAudioManager() {
        Context context = GeneralVariables.getMainContext();
        return context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private String describeDevice(AudioDeviceInfo device) {
        String type;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                type = "USB 音频";
                break;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                type = "USB 耳麦";
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                type = "有线输入";
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                type = "蓝牙 SCO";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                type = "手机麦克风";
                break;
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                type = "线路输入";
                break;
            default:
                type = "音频输入";
                break;
        }
        CharSequence productName = device.getProductName();
        String product = productName == null ? "" : productName.toString().trim();
        return product.isEmpty() ? type : type + " · " + product;
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

    public boolean isSystemSilenced() {
        return systemSilenced;
    }

    public String getInputRouteDescription() {
        return inputRouteDescription;
    }
}

