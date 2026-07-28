package com.bg7yoz.ft8cn.spectrum;
/**
 * 用于瀑布图的音频接收。以一个FT8符号为颗粒度。
 * @author BGY70Z
 * @date 2023-03-20
 */

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.wave.HamRecorder;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;

import java.util.Arrays;

public class SpectrumListener implements HamRecorder.OnCaptureStateChanged {
    private static final String TAG = "SpectrumListener";
    public static final int DISPLAY_MIN_FREQUENCY_HZ = 0;
    public static final int DISPLAY_MAX_FREQUENCY_HZ = 3000;
    public static final int DISPLAY_BIN_COUNT = 640;
    private final HamRecorder hamRecorder;
    private HamRecorder.VoiceDataSubscription subscription;

    public static final class SpectrumFrame {
        public final float[] samples;
        public final int sampleRate;

        public SpectrumFrame(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private float[] dataBuffer=new float[0];
    public MutableLiveData<SpectrumFrame> mutableDataBuffer = new MutableLiveData<>();


    private final OnGetVoiceDataDone onGetVoiceDataDone=new OnGetVoiceDataDone() {
        @Override
        public void onGetDone(float[] data) {
                    dataBuffer = data;
                    mutableDataBuffer.postValue(new SpectrumFrame(data, hamRecorder.getCurrentSampleRate()));
        }
    };

    public SpectrumListener(HamRecorder hamRecorder) {
        this.hamRecorder = hamRecorder;
        hamRecorder.addCaptureStateListener(this);
        start();
    }

    /** 每次录音源变化都丢弃旧窗口，确保频谱使用当前真实采样率。 */
    public synchronized void start() {
        stopSubscription();
        if (hamRecorder.isRunning()) {
            subscription = hamRecorder.getVoiceData(160, false, onGetVoiceDataDone);
        }
    }

    public synchronized void stop() {
        stopSubscription();
    }

    public synchronized void release() {
        hamRecorder.removeCaptureStateListener(this);
        stopSubscription();
    }

    private void stopSubscription() {
        if (subscription != null) {
            hamRecorder.deleteVoiceDataMonitor(subscription);
            subscription = null;
        }
    }

    @Override
    public void onCaptureStateChanged(boolean running, int sampleRate) {
        if (running) {
            start();
        } else {
            stop();
        }
    }

    public float[] getDataBuffer() {
        return dataBuffer;
    }

    public int getCurrentSampleRate() {
        return hamRecorder.getCurrentSampleRate();
    }

    public static int resolveRenderBinCount(int sampleRate, int fftSize, int fftOutputLength) {
        if (sampleRate <= 0 || fftSize <= 0 || fftOutputLength <= 0) {
            return 0;
        }
        final double binHz = (double) sampleRate / (double) fftSize;
        final int maxBin = (int) Math.floor(DISPLAY_MAX_FREQUENCY_HZ / binHz);
        return Math.max(1, Math.min(fftOutputLength, maxBin + 1));
    }

    public static int[] normalizeDisplayBins(int[] source, int sourceLength) {
        int validLength = Math.max(0, Math.min(source == null ? 0 : source.length, sourceLength));
        if (validLength <= 0) {
            return new int[0];
        }
        int[] display = new int[DISPLAY_BIN_COUNT];
        normalizeDisplayBins(source, validLength, display);
        return display;
    }

    /**
     * 把频谱写入调用方复用的固定显示缓冲，避免每个 160 ms 帧创建新数组。
     */
    public static int normalizeDisplayBins(int[] source, int sourceLength, int[] display) {
        int validLength = Math.max(0, Math.min(source == null ? 0 : source.length, sourceLength));
        if (display == null || display.length < DISPLAY_BIN_COUNT) {
            return 0;
        }
        if (validLength <= 0) {
            Arrays.fill(display, 0, DISPLAY_BIN_COUNT, 0);
            return 0;
        }
        for (int i = 0; i < DISPLAY_BIN_COUNT; i++) {
            int start = Math.round(i * validLength / (float) DISPLAY_BIN_COUNT);
            int end = Math.round((i + 1) * validLength / (float) DISPLAY_BIN_COUNT);
            if (end <= start) {
                end = start + 1;
            }
            if (end > validLength) {
                end = validLength;
            }

            int peak = 0;
            for (int j = start; j < end; j++) {
                if (source[j] > peak) {
                    peak = source[j];
                }
            }
            display[i] = peak;
        }
        return DISPLAY_BIN_COUNT;
    }
}

