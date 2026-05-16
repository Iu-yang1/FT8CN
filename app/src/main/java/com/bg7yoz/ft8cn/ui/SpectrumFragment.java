package com.bg7yoz.ft8cn.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.databinding.FragmentSpectrumBinding;
import com.bg7yoz.ft8cn.spectrum.SpectrumListener;
import com.bg7yoz.ft8cn.timer.UtcTimer;

public class SpectrumFragment extends Fragment {
    private static final String TAG = "SpectrumFragment";
    private FragmentSpectrumBinding binding;
    private MainViewModel mainViewModel;

    private int frequencyLineTimeOut = 0;

    /** 复用的 FFT buffer，避免每帧分配 */
    private int[] fftBuffer;
    private int[] renderBuffer;
    private int lastLoggedSourceRate = -1;
    private int lastLoggedInputLen = -1;
    private int lastLoggedRenderBins = -1;

    static {
        System.loadLibrary("ft8cn");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentSpectrumBinding.inflate(inflater, container, false);

        binding.columnarView.setShowBlock(true);
        binding.deNoiseSwitch.setChecked(mainViewModel.deNoise);
        binding.waterfallView.setDrawMessage(false);

        setDeNoiseSwitchState();
        setMarkMessageSwitchState();

        binding.rulerFrequencyView.setFreq(Math.round(GeneralVariables.getBaseFrequency()));
        mainViewModel.currentMessages = null;

        setupSwitchListeners();
        setupTouchListener();
        observeViewModel();

        return binding.getRoot();
    }

    private void setupSwitchListeners() {
        binding.deNoiseSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                mainViewModel.deNoise = checked;
                setDeNoiseSwitchState();
                mainViewModel.currentMessages = null;
            }
        });

        binding.showMessageSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                mainViewModel.markMessage = checked;
                setMarkMessageSwitchState();
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchListener() {
        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                frequencyLineTimeOut = 60;
                binding.waterfallView.setTouch_x(Math.round(motionEvent.getX()));
                binding.columnarView.setTouch_x(Math.round(motionEvent.getX()));

                if (!mainViewModel.ft8TransmitSignal.isSynFrequency()
                        && binding.waterfallView.getFreq_hz() > 0
                        && motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    mainViewModel.databaseOpr.writeConfig(
                            "freq",
                            String.valueOf(binding.waterfallView.getFreq_hz()),
                            null
                    );
                    mainViewModel.ft8TransmitSignal.setBaseFrequency(
                            (float) binding.waterfallView.getFreq_hz()
                    );
                    binding.rulerFrequencyView.setFreq(binding.waterfallView.getFreq_hz());

                    requireActivity().runOnUiThread(() -> ToastMessage.show(
                            String.format(GeneralVariables.getStringFromResource(
                                            R.string.sound_frequency_is_set_to),
                                    binding.waterfallView.getFreq_hz()),
                            true
                    ));
                }
                return false;
            }
        };
        binding.waterfallView.setOnTouchListener(touchListener);
        binding.columnarView.setOnTouchListener(touchListener);
    }

    private void observeViewModel() {
        mainViewModel.spectrumListener.mutableDataBuffer.observe(getViewLifecycleOwner(), new Observer<SpectrumListener.SpectrumFrame>() {
            @Override
            public void onChanged(SpectrumListener.SpectrumFrame frame) {
                if (frame == null || frame.samples == null || frame.samples.length == 0) {
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
                    binding.waterfallView.setTouch_x(-1);
                    binding.columnarView.setTouch_x(-1);
                }

                binding.columnarView.setWaveData(renderBuffer);
                if (mainViewModel.markMessage) {
                    binding.waterfallView.setWaveData(renderBuffer, UtcTimer.getNowSequential(), mainViewModel.currentMessages);
                } else {
                    binding.waterfallView.setWaveData(renderBuffer, UtcTimer.getNowSequential(), null);
                }
            }
        });

        mainViewModel.ft8SignalListener.decodeTimeSec.observe(getViewLifecycleOwner(), aLong ->
                binding.decodeDurationTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.decoding_takes_milliseconds),
                        aLong
                ))
        );

        mainViewModel.mutableIsDecoding.observe(getViewLifecycleOwner(), aBoolean ->
                binding.waterfallView.setDrawMessage(!aBoolean)
        );

        mainViewModel.timerSec.observe(getViewLifecycleOwner(), aLong -> {
            binding.timersTextView.setText(UtcTimer.getTimeStr(aLong));
            binding.freqBandTextView.setText(GeneralVariables.getBandString());
        });
    }

    private void setDeNoiseSwitchState() {
        binding.deNoiseSwitch.setText(mainViewModel.deNoise ?
                getString(R.string.de_noise) :
                getString(R.string.raw_spectrum_data));
    }

    private void setMarkMessageSwitchState() {
        binding.showMessageSwitch.setText(mainViewModel.markMessage ?
                getString(R.string.markMessage) :
                getString(R.string.unMarkMessage));
    }

    // native 方法保持不变
    public native void getFFTData(int[] data, int fftData[]);
    public native void getFFTDataFloat(float[] data, int fftData[]);
    public native void getFFTDataRaw(int[] data, int fftData[]);
    public native void getFFTDataRawFloat(float[] data, int fftData[]);
}

