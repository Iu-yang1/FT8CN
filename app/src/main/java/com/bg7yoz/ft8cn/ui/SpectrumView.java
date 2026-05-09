package com.bg7yoz.ft8cn.ui;

import static android.view.MotionEvent.ACTION_UP;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.spectrum.SpectrumListener;
import com.bg7yoz.ft8cn.timer.UtcTimer;

public class SpectrumView extends ConstraintLayout {
    private static final String TAG = "SpectrumView";

    private MainViewModel mainViewModel;
    private ColumnarView columnarView;
    private Switch controlDeNoiseSwitch;
    private Switch controlShowMessageSwitch;
    private WaterfallView waterfallView;
    private RulerFrequencyView rulerFrequencyView;
    private Fragment fragment;

    private int frequencyLineTimeOut = 0; // 频率线显示倒计时
    private int[] fftBuffer;
    private int[] renderBuffer;
    private int lastLoggedSourceRate = -1;
    private int lastLoggedInputLen = -1;
    private int lastLoggedRenderBins = -1;

    static {
        System.loadLibrary("ft8cn");
    }

    public SpectrumView(@NonNull Context context) {
        super(context);
    }

    public SpectrumView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        View.inflate(context, R.layout.spectrum_layout, this);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void run(MainViewModel mainViewModel, Fragment fragment) {
        this.mainViewModel = mainViewModel;
        this.fragment = fragment;
        columnarView = findViewById(R.id.controlColumnarView);
        controlDeNoiseSwitch = findViewById(R.id.controlDeNoiseSwitch);
        waterfallView = findViewById(R.id.controlWaterfallView);
        rulerFrequencyView = findViewById(R.id.controlRulerFrequencyView);
        controlShowMessageSwitch = findViewById(R.id.controlShowMessageSwitch);

        setDeNoiseSwitchState();
        setMarkMessageSwitchState();

        rulerFrequencyView.setFreq(Math.round(GeneralVariables.getBaseFrequency()));
        mainViewModel.currentMessages = null;

        controlDeNoiseSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                mainViewModel.deNoise = checked;
                setDeNoiseSwitchState();
                mainViewModel.currentMessages = null;
            }
        });

        controlShowMessageSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                mainViewModel.markMessage = checked;
                setMarkMessageSwitchState();
            }
        });

        mainViewModel.spectrumListener.mutableDataBuffer.observe(fragment.getViewLifecycleOwner(), new Observer<SpectrumListener.SpectrumFrame>() {
            @Override
            public void onChanged(SpectrumListener.SpectrumFrame frame) {
                drawSpectrum(frame);
            }
        });

        mainViewModel.mutableIsDecoding.observe(fragment.getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean decoding) {
                waterfallView.setDrawMessage(!decoding);
            }
        });

        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                frequencyLineTimeOut = 60;
                waterfallView.setTouch_x(Math.round(motionEvent.getX()));
                columnarView.setTouch_x(Math.round(motionEvent.getX()));

                if (!mainViewModel.ft8TransmitSignal.isSynFrequency()
                        && waterfallView.getFreq_hz() > 0
                        && motionEvent.getAction() == ACTION_UP) {
                    mainViewModel.databaseOpr.writeConfig(
                            "freq",
                            String.valueOf(waterfallView.getFreq_hz()),
                            null
                    );
                    mainViewModel.ft8TransmitSignal.setBaseFrequency(
                            (float) waterfallView.getFreq_hz()
                    );
                    rulerFrequencyView.setFreq(waterfallView.getFreq_hz());

                    fragment.requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ToastMessage.show(
                                    String.format(
                                            GeneralVariables.getStringFromResource(R.string.sound_frequency_is_set_to),
                                            waterfallView.getFreq_hz()
                                    ),
                                    true
                            );
                        }
                    });
                }
                return false;
            }
        };

        waterfallView.setOnTouchListener(touchListener);
        columnarView.setOnTouchListener(touchListener);
    }

    private void setDeNoiseSwitchState() {
        if (mainViewModel == null) {
            return;
        }
        controlDeNoiseSwitch.setChecked(mainViewModel.deNoise);
        if (mainViewModel.deNoise) {
            controlDeNoiseSwitch.setText(GeneralVariables.getStringFromResource(R.string.de_noise));
        } else {
            controlDeNoiseSwitch.setText(GeneralVariables.getStringFromResource(R.string.raw_spectrum_data));
        }
    }

    private void setMarkMessageSwitchState() {
        if (mainViewModel.markMessage) {
            controlShowMessageSwitch.setText(GeneralVariables.getStringFromResource(R.string.markMessage));
        } else {
            controlShowMessageSwitch.setText(GeneralVariables.getStringFromResource(R.string.unMarkMessage));
        }
    }

    public void drawSpectrum(SpectrumListener.SpectrumFrame frame) {
        if (frame == null || frame.samples == null || frame.samples.length <= 0) {
            return;
        }

        float[] buffer = frame.samples;
        int sourceRate = frame.sampleRate;
        int requiredSize = buffer.length / 2;
        if (fftBuffer == null || fftBuffer.length != requiredSize) {
            fftBuffer = new int[requiredSize];
        }

        if (mainViewModel.deNoise) {
            getFFTDataFloat(buffer, fftBuffer);
        } else {
            getFFTDataRawFloat(buffer, fftBuffer);
        }

        int renderBinCount = SpectrumListener.resolveRenderBinCount(
                sourceRate,
                buffer.length,
                fftBuffer.length
        );
        renderBuffer = SpectrumListener.normalizeDisplayBins(fftBuffer, renderBinCount);

        if (lastLoggedSourceRate != sourceRate
                || lastLoggedInputLen != buffer.length
                || lastLoggedRenderBins != renderBuffer.length) {
            double binHz = (double) sourceRate / (double) buffer.length;
            Log.d(TAG, String.format(
                    "Spectrum display sourceRate=%d inputLen=%d fftSize=%d binHz=%.2f maxBin=%d renderRange=0-%dHz",
                    sourceRate,
                    buffer.length,
                    buffer.length,
                    binHz,
                    Math.max(0, renderBinCount - 1),
                    SpectrumListener.DISPLAY_MAX_FREQUENCY_HZ
            ));
            lastLoggedSourceRate = sourceRate;
            lastLoggedInputLen = buffer.length;
            lastLoggedRenderBins = renderBuffer.length;
        }

        frequencyLineTimeOut--;
        if (frequencyLineTimeOut < 0) {
            frequencyLineTimeOut = 0;
        }
        if (frequencyLineTimeOut == 0) {
            waterfallView.setTouch_x(-1);
            columnarView.setTouch_x(-1);
        }

        columnarView.setWaveData(renderBuffer);
        if (mainViewModel.markMessage) {
            waterfallView.setWaveData(renderBuffer, UtcTimer.getNowSequential(), mainViewModel.currentMessages);
        } else {
            waterfallView.setWaveData(renderBuffer, UtcTimer.getNowSequential(), null);
        }
    }

    public native void getFFTData(int[] data, int fftData[]);

    public native void getFFTDataFloat(float[] data, int fftData[]);

    public native void getFFTDataRaw(int[] data, int fftData[]);

    public native void getFFTDataRawFloat(float[] data, int fftData[]);
}
