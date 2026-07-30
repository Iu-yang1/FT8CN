package com.bg7yoz.ft8cn.ui;
/**
 * My calling list screen.
 * Handles FT8 / FT4 / Q65 transmit targets and quick actions.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.cq.CqCallEntry;
import com.bg7yoz.ft8cn.core.automation.Q65AutomationState;
import com.bg7yoz.ft8cn.databinding.FragmentMyCallingBinding;
import com.bg7yoz.ft8cn.eme.EmeAssistState;
import com.bg7yoz.ft8cn.eme.EmeRigControlAdapter;
import com.bg7yoz.ft8cn.eme.EmeTrackingEnvironment;
import com.bg7yoz.ft8cn.eme.EmeTrackingPolicy;
import com.bg7yoz.ft8cn.eme.EmeTrackingResult;
import com.bg7yoz.ft8cn.eme.ui.DopplerCorrectionView;
import com.bg7yoz.ft8cn.eme.ui.MoonSkyView;
import com.bg7yoz.ft8cn.ft8transmit.DxpeditionFoxSlotFrequencyConfig;
import com.bg7yoz.ft8cn.ft8transmit.DxpeditionMacroSupport;
import com.bg7yoz.ft8cn.ft8transmit.FunctionOfTransmit;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;

public class MyCallingFragment extends Fragment {
    private static final String TAG = "MyCallingFragment";
    private FragmentMyCallingBinding binding;
    private MainViewModel mainViewModel;

    private RecyclerView transmitRecycleView;
    private CallingListAdapter transmitCallListAdapter;
    private CqQueueAdapter cqQueueAdapter;
    private FunctionOrderSpinnerAdapter functionOrderSpinnerAdapter;
    private boolean updatingDxpeditionModeUi = false;
    private boolean updatingSignalModeUi = false;
    private AlertDialog emeTrackingDialog;
    private boolean emeTrackingDialogAlive = false;
    private Runnable emeTrackingPanelRefreshAction;

    private static final class FragmentSafeEmeTrackingEnvironment implements EmeTrackingEnvironment {
        private final WeakReference<MainViewModel> viewModelRef;

        private FragmentSafeEmeTrackingEnvironment(MainViewModel mainViewModel) {
            viewModelRef = new WeakReference<>(mainViewModel);
        }

        @Override
        public String getObserverGrid() {
            return GeneralVariables.getMyMaidenheadGrid();
        }

        @Override
        public EmeRigControlAdapter getRigControlAdapter() {
            MainViewModel viewModel = viewModelRef.get();
            return new EmeRigControlAdapter(viewModel == null ? null : viewModel.baseRig);
        }

        @Override
        public long getFallbackBaseFrequencyHz() {
            MainViewModel viewModel = viewModelRef.get();
            if (GeneralVariables.emeUseCurrentRigFrequency
                    && viewModel != null
                    && viewModel.baseRig != null
                    && viewModel.baseRig.getFreq() > 0L) {
                return viewModel.baseRig.getFreq();
            }
            if (!GeneralVariables.emeUseCurrentRigFrequency && GeneralVariables.emeBaseFrequencyHz > 0L) {
                return GeneralVariables.emeBaseFrequencyHz;
            }
            return GeneralVariables.band;
        }

        @Override
        public boolean hasAutoFrequencyConflict() {
            return GeneralVariables.manualDxpeditionFoxMode
                    || GeneralVariables.manualDxpeditionHoundMode
                    || GeneralVariables.dxpeditionFoxManualSlotFrequency;
        }

        @Override
        public String getAutoFrequencyConflictReason() {
            if (GeneralVariables.manualDxpeditionFoxMode) {
                return "dxpedition-fox-active";
            }
            if (GeneralVariables.manualDxpeditionHoundMode) {
                return "dxpedition-hound-active";
            }
            if (GeneralVariables.dxpeditionFoxManualSlotFrequency) {
                return "dxpedition-slot-frequency-active";
            }
            return "";
        }
    }

    private boolean isExperimentalManualTxMode() {
        return GeneralVariables.isExperimentalCodecEnabled()
                && GeneralVariables.getOperatingProfile() == GeneralVariables.OPERATING_PROFILE_NORMAL;
    }

    private String getCurrentModeLabel() {
        return GeneralVariables.getActiveModeLabel();
    }

    private String getQ65ConfigLabel() {
        return "Q65 "
                + FT8Common.getQ65SubmodeLabel(GeneralVariables.getQ65Submode())
                + " · "
                + GeneralVariables.getQ65TrPeriodSeconds()
                + "s";
    }

    private void updateQ65ConfigUi() {
        if (binding == null) {
            return;
        }
        boolean emeMode = GeneralVariables.isEmeOperatingProfile();
        binding.rbQ65.setText(emeMode ? getQ65ConfigLabel() : "Q65");
        binding.rbQ65.setVisibility(emeMode ? View.VISIBLE : View.GONE);
        binding.q65ConfigButton.setVisibility(emeMode ? View.VISIBLE : View.GONE);
        binding.q65ConfigButton.setEnabled(false);
        binding.q65ConfigButton.setText(getQ65ConfigLabel());
    }

    private String getDefaultQ65Message() {
        return "CQ " + GeneralVariables.myCallsign + " " + GeneralVariables.getMyMaidenhead4Grid();
    }

    /** 在解码、呼叫两页统一显示当前业务模式、T/R 序列和 Doppler 状态。 */
    private void updateOperatingModeUi() {
        if (binding == null) {
            return;
        }
        int profile = GeneralVariables.getOperatingProfile();
        if (profile == GeneralVariables.OPERATING_PROFILE_NORMAL) {
            binding.operatingModeTextView.setVisibility(View.GONE);
            return;
        }

        int mode = profile == GeneralVariables.OPERATING_PROFILE_Q65_EME
                ? FT8Common.Q65_MODE : FT8Common.FT4_MODE;
        int currentSequence = UtcTimer.getNowSequential(FT8Common.getSlotTimeM(mode));
        int txSequence = mainViewModel == null || mainViewModel.ft8TransmitSignal == null
                ? 0 : mainViewModel.ft8TransmitSignal.sequential;
        if (mode == FT8Common.Q65_MODE && mainViewModel != null
                && mainViewModel.ft8TransmitSignal != null) {
            Q65AutomationState state = mainViewModel.ft8TransmitSignal
                    .getQ65AutomationState().getValue();
            if (state != null) {
                txSequence = state.getTxSequence();
            }
        }
        boolean txSlot = mainViewModel != null && mainViewModel.ft8TransmitSignal != null
                && mainViewModel.ft8TransmitSignal.isActivated()
                && currentSequence == txSequence;
        String tracking = GeneralVariables.getOperatingTrackingStatus();
        if (tracking == null || tracking.trim().isEmpty()) {
            tracking = "Doppler 未启用";
        }
        String sequenceText = "计划 TX" + (txSequence + 1)
                + " · 当前 " + (txSlot ? "TX" : "RX") + (currentSequence + 1);
        if (profile == GeneralVariables.OPERATING_PROFILE_Q65_EME) {
            binding.operatingModeTextView.setText("Q65 EME · " + getQ65ConfigLabel()
                    + " · " + sequenceText + " · " + tracking);
        } else {
            binding.operatingModeTextView.setText("卫星 · FT4 · " + sequenceText
                    + " · 定向 CQ 已暂停 · " + tracking);
        }
        binding.operatingModeTextView.setVisibility(View.VISIBLE);
    }

    private void updateEmeAssistButtonUi() {
        if (binding == null) {
            return;
        }
        binding.emeAssistButton.setVisibility(View.GONE);
        EmeTrackingResult result = mainViewModel == null || mainViewModel.emeAssistController == null
                ? null
                : mainViewModel.emeAssistController.getTrackingResult();
        String status = result == null ? "OFF" : result.status.name();
        if (!GeneralVariables.emeAssistEnabled && (result == null || !mainViewModel.emeAssistController.isTrackingActive())) {
            status = "OFF";
        }
        binding.emeAssistButton.setText(getString(R.string.eme_tracking_entry, status));
        binding.emeAssistButton.setAlpha(GeneralVariables.emeAssistEnabled ? 1.0f : 0.72f);
    }

    private void restartForModeRuntimeChange() {
        if (mainViewModel.ft8SignalListener != null) {
            mainViewModel.ft8SignalListener.restartByCurrentMode();
        }
        if (mainViewModel.ft8TransmitSignal != null) {
            mainViewModel.ft8TransmitSignal.restartByCurrentMode();
            mainViewModel.ft8TransmitSignal.setActivated(false);
            mainViewModel.ft8TransmitSignal.setTransmitting(false);
            mainViewModel.ft8TransmitSignal.resetToCQ();
        }
        mainViewModel.clearTransmittingMessage();
    }

    private void applyQ65Configuration(int submode, int trPeriodSeconds) {
        String nextLabel = FT8Common.getQ65ModeLabel(submode, trPeriodSeconds);
        boolean changed = GeneralVariables.setQ65Configuration(submode, trPeriodSeconds);
        updateQ65ConfigUi();
        if (!changed) {
            return;
        }

        if (GeneralVariables.getSignalMode() == FT8Common.Q65_MODE) {
            restartForModeRuntimeChange();
            updateSignalModeUI();
            ToastMessage.show(getString(R.string.q65_config_applied, nextLabel));
            return;
        }

        ToastMessage.show(getString(R.string.q65_config_saved, nextLabel));
    }

    private void showQ65ConfigDialog() {
        if (getContext() == null) {
            return;
        }

        ArrayList<String> submodeLabels = new ArrayList<>();
        for (String label : FT8Common.Q65_SUBMODE_LABELS) {
            submodeLabels.add("Q65" + label);
        }
        ArrayList<String> periodLabels = new ArrayList<>();
        for (int period : FT8Common.Q65_SUPPORTED_TR_PERIODS) {
            periodLabels.add(period + "s");
        }

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(getResources().getDisplayMetrics().density * 20.0f);
        root.setPadding(padding, padding, padding, padding / 2);

        TextView submodeTitle = new TextView(requireContext());
        submodeTitle.setText(R.string.q65_config_submode);
        root.addView(submodeTitle);

        Spinner submodeSpinner = new Spinner(requireContext());
        submodeSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                submodeLabels
        ));
        submodeSpinner.setSelection(GeneralVariables.getQ65Submode());
        root.addView(submodeSpinner);

        TextView periodTitle = new TextView(requireContext());
        periodTitle.setText(R.string.q65_config_tr_period);
        periodTitle.setPadding(0, padding / 2, 0, 0);
        root.addView(periodTitle);

        Spinner periodSpinner = new Spinner(requireContext());
        periodSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                periodLabels
        ));
        int selectedPeriodIndex = 0;
        for (int index = 0; index < FT8Common.Q65_SUPPORTED_TR_PERIODS.length; ++index) {
            if (FT8Common.Q65_SUPPORTED_TR_PERIODS[index] == GeneralVariables.getQ65TrPeriodSeconds()) {
                selectedPeriodIndex = index;
                break;
            }
        }
        periodSpinner.setSelection(selectedPeriodIndex);
        root.addView(periodSpinner);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.q65_config_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int selectedSubmode = submodeSpinner.getSelectedItemPosition();
                    int selectedTrPeriod = FT8Common.Q65_SUPPORTED_TR_PERIODS[
                            Math.max(0, Math.min(periodSpinner.getSelectedItemPosition(),
                                    FT8Common.Q65_SUPPORTED_TR_PERIODS.length - 1))
                            ];
                    applyQ65Configuration(selectedSubmode, selectedTrPeriod);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private long getEmeSourceFrequencyHz() {
        if (GeneralVariables.emeUseCurrentRigFrequency
                && mainViewModel != null
                && mainViewModel.baseRig != null
                && mainViewModel.baseRig.getFreq() > 0L) {
            return mainViewModel.baseRig.getFreq();
        }
        if (!GeneralVariables.emeUseCurrentRigFrequency && GeneralVariables.emeBaseFrequencyHz > 0L) {
            return GeneralVariables.emeBaseFrequencyHz;
        }
        return GeneralVariables.band;
    }

    private double parseDoubleOrDefault(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void writeEmeConfig(String key, String value) {
        if (mainViewModel == null || mainViewModel.databaseOpr == null) {
            return;
        }
        mainViewModel.databaseOpr.writeConfig(key, value, null);
    }

    private EmeAssistState updateEmePreview(boolean enabled, EmeAssistState.Mode mode) {
        return mainViewModel.emeAssistController.updateCorrectionPreview(
                GeneralVariables.getMyMaidenheadGrid(),
                getEmeSourceFrequencyHz(),
                UtcTimer.getSystemTime(),
                enabled,
                mode,
                GeneralVariables.emeCorrectionDirectionMode,
                GeneralVariables.emeMaxCorrectionHz,
                GeneralVariables.emeMinElevationDeg);
    }

    private EmeTrackingPolicy buildEmeTrackingPolicy(boolean enabled) {
        return new EmeTrackingPolicy(
                enabled,
                GeneralVariables.emeUseCurrentRigFrequency,
                GeneralVariables.emeBaseFrequencyHz,
                GeneralVariables.emeMaxCorrectionHz,
                GeneralVariables.emeUpdateIntervalSeconds,
                GeneralVariables.emeMinElevationDeg,
                GeneralVariables.emeAllowCorrectionWhileTransmitting,
                GeneralVariables.emeRestoreFrequencyOnDisable,
                GeneralVariables.emeCorrectionDirectionMode);
    }

    private EmeTrackingEnvironment buildEmeTrackingEnvironment() {
        return new FragmentSafeEmeTrackingEnvironment(mainViewModel);
    }

    private void detachEmeTrackingPanel(String reason) {
        Log.i(TAG, "detach EME tracking panel: reason=" + reason);
        emeTrackingDialogAlive = false;
        emeTrackingPanelRefreshAction = null;
    }

    private void saveEmeTrackingConfig() {
        writeEmeConfig("emeAssistEnabled", GeneralVariables.emeAssistEnabled ? "1" : "0");
        writeEmeConfig("emeUseCurrentRigFrequency", GeneralVariables.emeUseCurrentRigFrequency ? "1" : "0");
        writeEmeConfig("emeMaxCorrectionHz", String.format(Locale.US, "%.1f", GeneralVariables.emeMaxCorrectionHz));
        writeEmeConfig("emeMinElevationDeg", String.format(Locale.US, "%.1f", GeneralVariables.emeMinElevationDeg));
        writeEmeConfig("emeUpdateIntervalSeconds", String.valueOf(GeneralVariables.emeUpdateIntervalSeconds));
        writeEmeConfig("emeAllowCorrectionWhileTransmitting",
                GeneralVariables.emeAllowCorrectionWhileTransmitting ? "1" : "0");
        writeEmeConfig("emeApplyMode", EmeAssistState.Mode.CAT_TRACKING.name());
    }

    private void showEmeAssistDialog() {
        if (getContext() == null || mainViewModel == null || mainViewModel.emeAssistController == null) {
            return;
        }
        Log.i(TAG, "open EME tracking dialog: orientation="
                + getResources().getConfiguration().orientation
                + " trackingActive="
                + mainViewModel.emeAssistController.isTrackingActive());
        if (emeTrackingDialog != null && emeTrackingDialog.isShowing()) {
            emeTrackingDialog.dismiss();
        }
        View panelView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_eme_tracking, null);
        TextView headerView = panelView.findViewById(R.id.emeTrackingHeaderTextView);
        MoonSkyView moonSkyView = panelView.findViewById(R.id.emeMoonSkyView);
        DopplerCorrectionView dopplerCorrectionView = panelView.findViewById(R.id.emeDopplerCorrectionView);
        TextView statusView = panelView.findViewById(R.id.emeTrackingStatusTextView);
        CheckBox useCurrentRigFrequencyCheckBox =
                panelView.findViewById(R.id.emeUseCurrentRigFrequencyCheckBox);
        useCurrentRigFrequencyCheckBox.setChecked(GeneralVariables.emeUseCurrentRigFrequency);
        CheckBox allowTxCorrectionCheckBox =
                panelView.findViewById(R.id.emeAllowTxCorrectionCheckBox);
        allowTxCorrectionCheckBox.setChecked(GeneralVariables.emeAllowCorrectionWhileTransmitting);
        EditText maxCorrectionEditor = panelView.findViewById(R.id.emeMaxCorrectionEditText);
        maxCorrectionEditor.setText(String.format(Locale.US, "%.0f", GeneralVariables.emeMaxCorrectionHz));
        EditText minElevationEditor = panelView.findViewById(R.id.emeMinElevationEditText);
        minElevationEditor.setText(String.format(Locale.US, "%.1f", GeneralVariables.emeMinElevationDeg));
        EditText updateIntervalEditor = panelView.findViewById(R.id.emeUpdateIntervalEditText);
        updateIntervalEditor.setText(String.valueOf(GeneralVariables.emeUpdateIntervalSeconds));

        Runnable applyEditors = () -> {
            GeneralVariables.emeApplyMode = EmeAssistState.Mode.CAT_TRACKING;
            GeneralVariables.emeUseCurrentRigFrequency = useCurrentRigFrequencyCheckBox.isChecked();
            GeneralVariables.emeAllowCorrectionWhileTransmitting = allowTxCorrectionCheckBox.isChecked();
            GeneralVariables.emeMaxCorrectionHz = Math.max(0.0, parseDoubleOrDefault(
                    maxCorrectionEditor.getText().toString(),
                    GeneralVariables.emeMaxCorrectionHz));
            GeneralVariables.emeMinElevationDeg = parseDoubleOrDefault(
                    minElevationEditor.getText().toString(),
                    GeneralVariables.emeMinElevationDeg);
            try {
                GeneralVariables.emeUpdateIntervalSeconds = Math.max(1, Math.min(
                        Integer.parseInt(updateIntervalEditor.getText().toString()),
                        60));
            } catch (Exception e) {
                GeneralVariables.emeUpdateIntervalSeconds = 10;
            }
        };

        Runnable refreshPanel = () -> {
            if (!isAdded()
                    || getView() == null
                    || binding == null
                    || emeTrackingDialog == null
                    || !emeTrackingDialog.isShowing()
                    || !emeTrackingDialogAlive) {
                Log.i(TAG, "skip EME tracking UI update: reason=ui-detached");
                return;
            }
            EmeTrackingResult result = mainViewModel.emeAssistController.getTrackingResult();
            EmeAssistState emeState = mainViewModel.emeAssistController.updateCorrectionPreview(
                    GeneralVariables.getMyMaidenheadGrid(),
                    getEmeSourceFrequencyHz(),
                    UtcTimer.getSystemTime(),
                    GeneralVariables.emeAssistEnabled,
                    EmeAssistState.Mode.CAT_TRACKING,
                    GeneralVariables.emeCorrectionDirectionMode,
                    GeneralVariables.emeMaxCorrectionHz,
                    GeneralVariables.emeMinElevationDeg);
            headerView.setText(getString(
                    R.string.eme_tracking_header,
                    result.status.name(),
                    GeneralVariables.getActiveModeLabel()));
            if (emeState.moonEphemeris != null) {
                moonSkyView.setMoonPosition(
                        emeState.moonEphemeris.azimuthDeg,
                        emeState.moonEphemeris.elevationDeg);
            }
            double selectedCorrection = result.selectedCorrectionHz;
            if (selectedCorrection == 0.0 && emeState.sourceFrequencyHz > 0L) {
                selectedCorrection = emeState.targetFrequencyHz - emeState.sourceFrequencyHz;
            }
            double lastAppliedCorrection = emeState.lastAppliedRigFrequencyHz > 0L && emeState.sourceFrequencyHz > 0L
                    ? emeState.lastAppliedRigFrequencyHz - emeState.sourceFrequencyHz
                    : 0.0;
            dopplerCorrectionView.setCorrections(
                    GeneralVariables.emeMaxCorrectionHz,
                    emeState.lastDopplerHz,
                    emeState.lastTxDopplerHz,
                    selectedCorrection,
                    lastAppliedCorrection,
                    Math.abs(result.rawCorrectionHz) > GeneralVariables.emeMaxCorrectionHz);
            statusView.setText(String.format(
                    Locale.US,
                    "Rig: %s  connected=%s\nFrequency: %d -> %d Hz  source=%s\nCorrection: raw %.1f Hz selected %.1f Hz limit %.0f Hz\nMoon: Az %.1f El %.1f rangeRate %.2f m/s\nUpdate interval: %ds\nLast CAT: %s\nReason: %s",
                    result.rigName,
                    result.rigConnected ? "yes" : "no",
                    result.currentFrequencyHz > 0L ? result.currentFrequencyHz : emeState.sourceFrequencyHz,
                    result.targetFrequencyHz > 0L ? result.targetFrequencyHz : emeState.targetFrequencyHz,
                    result.frequencySource,
                    result.rawCorrectionHz,
                    selectedCorrection,
                    GeneralVariables.emeMaxCorrectionHz,
                    emeState.moonEphemeris == null ? Double.NaN : emeState.moonEphemeris.azimuthDeg,
                    emeState.moonEphemeris == null ? Double.NaN : emeState.moonEphemeris.elevationDeg,
                    emeState.moonEphemeris == null ? Double.NaN : emeState.moonEphemeris.rangeRateMps,
                    GeneralVariables.emeUpdateIntervalSeconds,
                    result.catResult == null ? "-" : result.catResult.toSummary(),
                    result.reason));
            updateEmeAssistButtonUi();
            updateAutoSessionStatus();
        };
        emeTrackingPanelRefreshAction = refreshPanel;

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.eme_tracking_title)
                .setView(panelView)
                .setPositiveButton(R.string.eme_tracking_start, null)
                .setNegativeButton(R.string.eme_tracking_stop, null)
                .setNeutralButton(R.string.eme_tracking_refresh, null)
                .create();
        emeTrackingDialog = dialog;
        dialog.setOnDismissListener(dialogInterface -> {
            Log.i(TAG, "EME tracking dialog dismissed");
            detachEmeTrackingPanel("dialog-dismiss");
        });
        dialog.setOnShowListener(dialogInterface -> {
            emeTrackingDialogAlive = true;
            refreshPanel.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                applyEditors.run();
                GeneralVariables.emeAssistEnabled = true;
                saveEmeTrackingConfig();
                Log.i(TAG, "start EME tracking from dialog");
                mainViewModel.emeAssistController.startEmeTracking(
                        buildEmeTrackingEnvironment(),
                        buildEmeTrackingPolicy(true));
                refreshPanel.run();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                GeneralVariables.emeAssistEnabled = false;
                saveEmeTrackingConfig();
                Log.i(TAG, "stop EME tracking from dialog");
                mainViewModel.emeAssistController.stopEmeTracking("user-stopped");
                refreshPanel.run();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                applyEditors.run();
                EmeAssistState preview = updateEmePreview(
                        GeneralVariables.emeAssistEnabled,
                        EmeAssistState.Mode.CAT_TRACKING);
                Log.i(TAG, "EME tracking refresh: " + (preview == null ? "null" : preview.statusText));
                refreshPanel.run();
            });
        });
        dialog.show();
    }

    private void updateDxpeditionManualUi() {
        if (binding == null) {
            return;
        }
        boolean specialProfile = GeneralVariables.isEmeOperatingProfile()
                || GeneralVariables.isSatelliteOperatingProfile();
        boolean enabled = !isExperimentalManualTxMode()
                && !mainViewModel.getTransitIsFreeText()
                && GeneralVariables.getSignalMode() == FT8Common.FT8_MODE
                && !specialProfile;
        int controlVisibility = specialProfile ? View.GONE : View.VISIBLE;
        binding.dxpeditionManualCheckBox.setVisibility(controlVisibility);
        binding.dxpeditionFoxCheckBox.setVisibility(controlVisibility);
        binding.dxpeditionHelpButton.setVisibility(controlVisibility);
        binding.dxpeditionManualCheckBox.setEnabled(enabled);
        binding.dxpeditionFoxCheckBox.setEnabled(enabled);
        boolean foxSlotsEnabled = enabled && binding.dxpeditionFoxCheckBox.isChecked();
        binding.dxpeditionTxSlotsButton.setVisibility(foxSlotsEnabled ? View.VISIBLE : View.GONE);
        binding.dxpeditionTxSlotsButton.setEnabled(foxSlotsEnabled);
        binding.dxpeditionTxSlotsButton.setAlpha(foxSlotsEnabled ? 1.0f : 0.45f);
        if (mainViewModel != null && mainViewModel.ft8TransmitSignal != null) {
            String slotsText = getString(
                    R.string.dxpedition_tx_slots_button,
                    mainViewModel.ft8TransmitSignal.getDxpeditionFoxTxSlots());
            if (GeneralVariables.dxpeditionFoxManualSlotFrequency) {
                slotsText += "*";
            }
            binding.dxpeditionTxSlotsButton.setText(slotsText);
        }
        if (!enabled && !specialProfile
                && (binding.dxpeditionManualCheckBox.isChecked() || binding.dxpeditionFoxCheckBox.isChecked())) {
            applyManualDxpeditionMode(false, false, true);
        }
        binding.dxpeditionManualCheckBox.setAlpha(enabled ? 1.0f : 0.45f);
        binding.dxpeditionFoxCheckBox.setAlpha(enabled ? 1.0f : 0.45f);
        updateDxpeditionMacroUi();
    }

    private void showDxpeditionTxSlotsPicker() {
        showDxpeditionFoxTransmitSettingsDialog();
    }

    private void showDxpeditionTxFrequencyDialog() {
        showDxpeditionFoxTransmitSettingsDialog();
    }

    private int parseTxFrequencyInput(EditText input, int fallback) {
        if (input == null || input.getText() == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void showDxpeditionFoxTransmitSettingsDialog() {
        if (binding == null || mainViewModel == null || mainViewModel.ft8TransmitSignal == null) {
            return;
        }

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * requireContext().getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, 0);

        TextView slotsLabel = new TextView(requireContext());
        slotsLabel.setText(R.string.dxpedition_tx_slots_title);
        root.addView(slotsLabel);

        Spinner slotsSpinner = new Spinner(requireContext());
        String[] slotItems = new String[5];
        for (int i = 0; i < slotItems.length; i++) {
            slotItems[i] = getString(R.string.dxpedition_tx_slots_item, i + 1);
        }
        ArrayAdapter<String> slotAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                slotItems
        );
        slotAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        slotsSpinner.setAdapter(slotAdapter);
        slotsSpinner.setSelection(mainViewModel.ft8TransmitSignal.getDxpeditionFoxTxSlots() - 1);
        root.addView(slotsSpinner);

        CheckBox manualCheckBox = new CheckBox(requireContext());
        manualCheckBox.setText(R.string.dxpedition_tx_frequency_manual);
        manualCheckBox.setChecked(GeneralVariables.dxpeditionFoxManualSlotFrequency);
        root.addView(manualCheckBox);

        TextView standardView = new TextView(requireContext());
        standardView.setText(R.string.dxpedition_tx_frequency_standard_item);
        root.addView(standardView);

        EditText startInput = new EditText(requireContext());
        startInput.setSingleLine(true);
        startInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        startInput.setHint(R.string.dxpedition_tx_frequency_start_hint);
        startInput.setText(String.valueOf(DxpeditionFoxSlotFrequencyConfig.getStartHz()));
        root.addView(startInput);

        EditText stepInput = new EditText(requireContext());
        stepInput.setSingleLine(true);
        stepInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        stepInput.setHint(R.string.dxpedition_tx_frequency_step_hint);
        stepInput.setText(String.valueOf(DxpeditionFoxSlotFrequencyConfig.getStepHz()));
        root.addView(stepInput);

        TextView previewView = new TextView(requireContext());
        root.addView(previewView);

        Runnable refreshPreview = () -> {
            boolean manual = manualCheckBox.isChecked();
            int slots = slotsSpinner.getSelectedItemPosition() + 1;
            int start = DxpeditionFoxSlotFrequencyConfig.clampManualFrequency(
                    parseTxFrequencyInput(startInput, DxpeditionFoxSlotFrequencyConfig.MANUAL_START_HZ));
            int step = DxpeditionFoxSlotFrequencyConfig.clampStep(
                    parseTxFrequencyInput(stepInput, DxpeditionFoxSlotFrequencyConfig.STANDARD_STEP_HZ));
            previewView.setText(getString(
                    R.string.dxpedition_tx_frequency_preview,
                    DxpeditionFoxSlotFrequencyConfig.buildPreview(
                            slots,
                            manual,
                            start,
                            step)));
        };

        slotsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshPreview.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        manualCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> refreshPreview.run());
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshPreview.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        startInput.addTextChangedListener(watcher);
        stepInput.addTextChangedListener(watcher);
        refreshPreview.run();

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dxpedition_tx_settings_title)
                .setView(root)
                .setPositiveButton(R.string.ok_confirmed, (dialogInterface, which) -> {
                    int slots = slotsSpinner.getSelectedItemPosition() + 1;
                    boolean manual = manualCheckBox.isChecked();
                    int start = DxpeditionFoxSlotFrequencyConfig.clampManualFrequency(
                            parseTxFrequencyInput(startInput, DxpeditionFoxSlotFrequencyConfig.MANUAL_START_HZ));
                    int step = DxpeditionFoxSlotFrequencyConfig.clampStep(
                            parseTxFrequencyInput(stepInput, DxpeditionFoxSlotFrequencyConfig.STANDARD_STEP_HZ));
                    mainViewModel.ft8TransmitSignal.setDxpeditionFoxTxSlots(slots);
                    mainViewModel.databaseOpr.writeConfig("dxpeditionFoxTxSlots", String.valueOf(slots), null);
                    mainViewModel.ft8TransmitSignal.setDxpeditionFoxSlotFrequencyConfig(manual, start, step);
                    mainViewModel.databaseOpr.writeConfig("dxpeditionFoxManualSlotFrequency", manual ? "1" : "0", null);
                    mainViewModel.databaseOpr.writeConfig("dxpeditionFoxSlotStartHz", String.valueOf(start), null);
                    mainViewModel.databaseOpr.writeConfig("dxpeditionFoxSlotStepHz", String.valueOf(step), null);
                    updateDxpeditionManualUi();
                    updateAutoSessionStatus();
                    ToastMessage.show(getString(R.string.dxpedition_tx_frequency_saved));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private boolean canUseDxpeditionMacroUi() {
        return binding != null
                && !isExperimentalManualTxMode()
                && !mainViewModel.getTransitIsFreeText()
                && GeneralVariables.getSignalMode() == FT8Common.FT8_MODE
                && mainViewModel.ft8TransmitSignal.canUseManualDxpeditionMacro();
    }

    private void applyManualDxpeditionMode(boolean hound, boolean fox, boolean persist) {
        if (binding == null) {
            return;
        }
        if (hound && fox) {
            fox = false;
        }
        updatingDxpeditionModeUi = true;
        binding.dxpeditionManualCheckBox.setChecked(hound);
        binding.dxpeditionFoxCheckBox.setChecked(fox);
        updatingDxpeditionModeUi = false;

        GeneralVariables.manualDxpeditionHoundMode = hound;
        GeneralVariables.manualDxpeditionFoxMode = fox;

        if ((hound || fox) && GeneralVariables.synFrequency) {
            ToastMessage.show(getString(R.string.dxpedition_split_required_hint));
        }

        if (persist) {
            mainViewModel.databaseOpr.writeConfig("manualDxpeditionHoundMode", hound ? "1" : "0", null);
            mainViewModel.databaseOpr.writeConfig("manualDxpeditionFoxMode", fox ? "1" : "0", null);
        }

        mainViewModel.ft8TransmitSignal.refreshSessionModeByCurrentTarget();
        updateAutoSessionStatus();
        updateDxpeditionMacroUi();
    }

    private void updateDxpeditionMacroUi() {
        if (binding == null) {
            return;
        }
        boolean enabled = canUseDxpeditionMacroUi();
        if (mainViewModel != null
                && mainViewModel.ft8TransmitSignal != null
                && mainViewModel.ft8TransmitSignal.isManualDxpeditionFoxMode()) {
            binding.dxpeditionMacroButton.setText(R.string.dxpedition_compound_button);
        } else {
            binding.dxpeditionMacroButton.setText(R.string.dxpedition_macro_button);
        }
        binding.dxpeditionMacroButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.dxpeditionMacroButton.setEnabled(enabled);
        binding.dxpeditionMacroButton.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private String getDxpeditionMacroPreview(int slot) {
        String template = DxpeditionMacroSupport.getTemplateForSlot(slot);
        String preview = mainViewModel.ft8TransmitSignal.previewManualDxpeditionMacro(template);
        if (preview.length() == 0) {
            preview = DxpeditionMacroSupport.normalizeTemplate(template);
        }
        return DxpeditionMacroSupport.getSlotLabel(slot) + ": " + preview;
    }

    private void showDxpeditionMacroPicker() {
        if (!canUseDxpeditionMacroUi()) {
            return;
        }
        if (mainViewModel.ft8TransmitSignal.isManualDxpeditionFoxMode()) {
            showDxpeditionFoxCompoundPicker();
            return;
        }
        String[] items = new String[DxpeditionMacroSupport.SLOT_COUNT];
        for (int i = 0; i < items.length; i++) {
            items[i] = getDxpeditionMacroPreview(i);
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dxpedition_macro_pick_title)
                .setItems(items, (dialogInterface, which) -> {
                    String template = DxpeditionMacroSupport.getTemplateForSlot(which);
                    mainViewModel.ft8TransmitSignal.sendManualDxpeditionMacro(template);
                    updateAutoSessionStatus();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int parseCompoundReport(String input, int fallback) {
        if (input == null) {
            return fallback;
        }
        String text = input.trim();
        if (text.length() == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void showDxpeditionFoxCompoundPicker() {
        int suggestedReport = mainViewModel.ft8TransmitSignal.getSuggestedDxpeditionCompoundReport();
        String preview = mainViewModel.ft8TransmitSignal.previewManualDxpeditionCompoundMessage(
                true, "", "", suggestedReport);
        String autoItem = preview.length() > 0
                ? getString(R.string.dxpedition_compound_auto_item_preview, preview)
                : getString(R.string.dxpedition_compound_auto_item);

        String[] items = new String[]{
                autoItem,
                getString(R.string.dxpedition_compound_manual_item)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dxpedition_compound_picker_title)
                .setItems(items, (dialogInterface, which) -> {
                    if (which == 0) {
                        if (mainViewModel.ft8TransmitSignal.sendManualDxpeditionCompoundMessage(
                                true, "", "", suggestedReport)) {
                            updateAutoSessionStatus();
                        }
                        return;
                    }
                    showDxpeditionFoxCompoundManualDialog(suggestedReport);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDxpeditionFoxCompoundManualDialog(int suggestedReport) {
        ArrayList<String> candidates = mainViewModel.ft8TransmitSignal.getDxpeditionFoxCandidateCallsigns();
        if (candidates.size() < 2) {
            ToastMessage.show(getString(R.string.dxpedition_compound_need_candidates));
            return;
        }

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * requireContext().getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, 0);

        TextView hint = new TextView(requireContext());
        hint.setText(R.string.dxpedition_compound_manual_hint);
        root.addView(hint);

        Spinner call1Spinner = new Spinner(requireContext());
        Spinner call2Spinner = new Spinner(requireContext());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                candidates
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        call1Spinner.setAdapter(adapter);
        call2Spinner.setAdapter(adapter);
        call2Spinner.setSelection(Math.min(1, candidates.size() - 1));
        root.addView(call1Spinner);
        root.addView(call2Spinner);

        EditText reportInput = new EditText(requireContext());
        reportInput.setSingleLine(true);
        reportInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        reportInput.setHint(R.string.dxpedition_compound_report_hint);
        reportInput.setText(String.format("%+03d", suggestedReport));
        root.addView(reportInput);

        TextView previewView = new TextView(requireContext());
        root.addView(previewView);

        Runnable refreshPreview = () -> {
            String call1 = call1Spinner.getSelectedItem() == null ? "" : call1Spinner.getSelectedItem().toString();
            String call2 = call2Spinner.getSelectedItem() == null ? "" : call2Spinner.getSelectedItem().toString();
            int report = parseCompoundReport(reportInput.getText().toString(), suggestedReport);
            String preview = mainViewModel.ft8TransmitSignal.previewManualDxpeditionCompoundMessage(
                    false, call1, call2, report);
            if (preview.length() == 0) {
                previewView.setText(R.string.dxpedition_compound_preview_invalid);
            } else {
                previewView.setText(preview);
            }
        };

        call1Spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshPreview.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        call2Spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshPreview.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        reportInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshPreview.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        refreshPreview.run();

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dxpedition_compound_manual_title)
                .setView(root)
                .setPositiveButton(R.string.send, (dialogInterface, i) -> {
                    String call1 = call1Spinner.getSelectedItem() == null ? "" : call1Spinner.getSelectedItem().toString();
                    String call2 = call2Spinner.getSelectedItem() == null ? "" : call2Spinner.getSelectedItem().toString();
                    int report = parseCompoundReport(reportInput.getText().toString(), suggestedReport);
                    if (mainViewModel.ft8TransmitSignal.sendManualDxpeditionCompoundMessage(
                            false, call1, call2, report)) {
                        updateAutoSessionStatus();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDxpeditionMacroEditPicker() {
        if (!canUseDxpeditionMacroUi()) {
            return;
        }
        String[] items = new String[]{
                getDxpeditionMacroPreview(DxpeditionMacroSupport.SLOT_CUSTOM_1),
                getDxpeditionMacroPreview(DxpeditionMacroSupport.SLOT_CUSTOM_2)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dxpedition_macro_edit_title)
                .setItems(items, (dialogInterface, which) ->
                        showDxpeditionMacroEditor(which == 0 ? 0 : 1))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDxpeditionMacroEditor(int customIndex) {
        EditText input = new EditText(requireContext());
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        input.setHint(R.string.dxpedition_macro_edit_hint);
        input.setText(DxpeditionMacroSupport.getCustomTemplate(customIndex));
        input.setSelection(input.getText().length());

        String label = DxpeditionMacroSupport.getSlotLabel(
                customIndex == 0 ? DxpeditionMacroSupport.SLOT_CUSTOM_1 : DxpeditionMacroSupport.SLOT_CUSTOM_2
        );

        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.dxpedition_macro_edit_slot_title, label))
                .setView(input)
                .setPositiveButton(R.string.ok_confirmed, (dialogInterface, i) -> {
                    String template = DxpeditionMacroSupport.normalizeTemplate(input.getText().toString());
                    if (template.length() == 0) {
                        ToastMessage.show(getString(R.string.dxpedition_macro_empty));
                        return;
                    }
                    DxpeditionMacroSupport.setCustomTemplate(customIndex, template);
                    mainViewModel.databaseOpr.writeConfig(
                            customIndex == 0
                                    ? "manualDxpeditionMacroCustom1"
                                    : "manualDxpeditionMacroCustom2",
                            DxpeditionMacroSupport.getCustomTemplate(customIndex),
                            null
                    );
                    ToastMessage.show(getString(R.string.dxpedition_macro_saved));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDxpeditionGuideDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dxpedition_guide_title)
                .setMessage(getString(R.string.dxpedition_guide_text))
                .setPositiveButton(R.string.ok_confirmed, null)
                .show();
    }

    private void showTransmitTypeGuideDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.transmit_type_examples_title)
                .setMessage(getString(R.string.transmit_type_examples_text))
                .setPositiveButton(R.string.ok_confirmed, null)
                .show();
    }

    private void updateAutoSessionStatus() {
        if (binding == null) {
            return;
        }
        if (isExperimentalManualTxMode()) {
            binding.autoSessionTextView.setText("");
            return;
        }
        if (GeneralVariables.getSignalMode() == FT8Common.Q65_MODE) {
            binding.autoSessionTextView.setMaxLines(5);
            binding.autoSessionTextView.setText(buildQ65StatusText());
            return;
        }
        binding.autoSessionTextView.setMaxLines(1);
        binding.autoSessionTextView.setText(mainViewModel.ft8TransmitSignal.getAutoSessionStatusText());
    }

    private String buildQ65StatusText() {
        String rxSummary = mainViewModel.ft8SignalListener == null
                ? ""
                : mainViewModel.ft8SignalListener.getLastDecodeStatusSummary();
        String txSummary = mainViewModel.ft8TransmitSignal == null
                ? ""
                : mainViewModel.ft8TransmitSignal.getLastTransmitStatusSummary();
        String schedulerSummary = mainViewModel.ft8SignalListener == null
                ? ""
                : mainViewModel.ft8SignalListener.getDecodeSchedulerStatusSummary();
        if (rxSummary == null || rxSummary.trim().length() == 0) {
            rxSummary = "-";
        }
        if (txSummary == null || txSummary.trim().length() == 0) {
            txSummary = "-";
        }
        if (schedulerSummary == null || schedulerSummary.trim().length() == 0) {
            schedulerSummary = "-";
        }
        Q65AutomationState state = mainViewModel.ft8TransmitSignal == null
                ? null : mainViewModel.ft8TransmitSignal.getQ65AutomationState().getValue();
        String automation = state == null
                ? "RECEIVE_ONLY"
                : state.getPhase().name()
                    + " · TX" + (state.getTxSequence() + 1)
                    + (state.getRepeat() ? " · 重复" : " · 单次")
                    + " · 已发 " + state.getTransmitCount();
        return getString(
                R.string.q65_status_line,
                GeneralVariables.getActiveModeLabel(),
                "on",
                "on",
                "off",
                "yes")
                + "\n"
                + getString(R.string.q65_status_rx_line, rxSummary)
                + "\n"
                + getString(R.string.q65_status_tx_line, txSummary)
                + "\n"
                + getString(R.string.q65_status_eme_line, buildEmeAssistStatusText())
                + "\n"
                + getString(R.string.q65_status_scheduler_line, schedulerSummary)
                + "\nAutomation: " + automation;
    }

    private String buildEmeAssistStatusText() {
        if (mainViewModel == null || mainViewModel.emeAssistController == null) {
            return "Tracking: unavailable";
        }
        EmeTrackingResult result = mainViewModel.emeAssistController.getTrackingResult();
        return String.format(
                Locale.US,
                "Tracking %s Az %.1f El %.1f dR %.2fm/s RX %.1fHz TX %.1fHz target %dHz reason=%s",
                result.status,
                result.moonAzimuthDeg,
                result.moonElevationDeg,
                result.rangeRateMps,
                result.rxDopplerHz,
                result.txDopplerHz,
                result.targetFrequencyHz,
                result.reason);
    }

    private void initCqQueuePanel() {
        cqQueueAdapter = new CqQueueAdapter(requireContext(), new CqQueueAdapter.OnQueueAction() {
            @Override
            public void onPromote(CqCallEntry entry) {
                if (entry == null) {
                    return;
                }
                mainViewModel.ft8TransmitSignal.promoteCqQueueEntry(entry.callsign);
                updateCqQueuePanel(mainViewModel.ft8TransmitSignal.getCqQueueSnapshot());
            }

            @Override
            public void onStartNow(CqCallEntry entry) {
                if (entry == null) {
                    return;
                }
                if (mainViewModel.ft8TransmitSignal.startCqQueueEntryNow(entry.callsign)) {
                    GeneralVariables.resetLaunchSupervision();
                    updateCqQueuePanel(mainViewModel.ft8TransmitSignal.getCqQueueSnapshot());
                    updateAutoSessionStatus();
                }
            }

            @Override
            public void onRemove(CqCallEntry entry) {
                if (entry == null) {
                    return;
                }
                mainViewModel.ft8TransmitSignal.removeCqQueueEntry(entry.callsign);
                updateCqQueuePanel(mainViewModel.ft8TransmitSignal.getCqQueueSnapshot());
            }
        });
        binding.cqQueueRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.cqQueueRecyclerView.setAdapter(cqQueueAdapter);
        updateCqQueuePanel(mainViewModel.ft8TransmitSignal.getCqQueueSnapshot());
    }

    private void updateCqQueuePanel(ArrayList<CqCallEntry> entries) {
        if (binding == null || cqQueueAdapter == null || mainViewModel == null
                || mainViewModel.ft8TransmitSignal == null) {
            return;
        }
        int count = entries == null ? 0 : entries.size();
        boolean visible = !GeneralVariables.isEmeOperatingProfile()
                && (GeneralVariables.cqQueueEnabled || count > 0);
        binding.cqQueuePanelLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        binding.cqQueueStatusTextView.setText(String.format(
                getString(R.string.cq_queue_panel_status),
                mainViewModel.ft8TransmitSignal.getCqQueueNowText(),
                count
        ));
        cqQueueAdapter.submitList(entries);
        binding.cqQueueRecyclerView.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    static {
        System.loadLibrary("ft8cn");
    }

    /**
     * Start an immediate follow-up call for the selected message.
     *
     * @param message selected message
     */
    private void doCallNow(Ft8Message message) {
        mainViewModel.addFollowCallsign(message.getCallsignFrom());
        if (GeneralVariables.isEmeOperatingProfile()
                && GeneralVariables.getSignalMode() == FT8Common.Q65_MODE) {
            if (!mainViewModel.ft8TransmitSignal.armQ65Reply(message, true)) {
                ToastMessage.show("无法从该消息建立 Q65 回复序列");
                return;
            }
            if (!GeneralVariables.transmitMessages.contains(message)) {
                GeneralVariables.transmitMessages.add(message);
            }
            updateSignalModeUI();
            GeneralVariables.resetLaunchSupervision();
            return;
        }
        if (!mainViewModel.ft8TransmitSignal.isActivated()) {
            mainViewModel.ft8TransmitSignal.setActivated(true);
            GeneralVariables.transmitMessages.add(message);// keep it in the transmit list for later status display
        }
        // Jump straight into the follow-up transmit flow.
        mainViewModel.ft8TransmitSignal.setTransmit(message.getFromCallTransmitCallsign(), 1, message.getAutoReplyExtraInfo());
        mainViewModel.ft8TransmitSignal.transmitNow();

        GeneralVariables.resetLaunchSupervision();// reset launch supervision state
    }

    /**
     * Handle the context menu actions.
     *
     * @param item selected menu item
     * @return whether the event should continue to the parent handler
     */
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        int position = (int) item.getActionView().getTag();
        Ft8Message ft8Message = transmitCallListAdapter.getMessageByPosition(position);
        if (ft8Message == null) return super.onContextItemSelected(item);

        GeneralVariables.resetLaunchSupervision();// reset launch supervision state
        switch (item.getItemId()) {
            case 1:// call the target station
                Log.d(TAG, "call TO: " + ft8Message.getCallsignTo());
                if (GeneralVariables.isEmeOperatingProfile()) {
                    doCallNow(ft8Message);
                    break;
                }
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                }
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getToCallTransmitCallsign(), 1, ft8Message.getAutoReplyExtraInfo());
                mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 3:
                Log.d(TAG, "call FROM: " + ft8Message.getCallsignFrom());
                doCallNow(ft8Message);
                break;

            case 4:// auto reply
                Log.d(TAG, "auto reply " + ft8Message.getCallsignFrom());
                if (GeneralVariables.isEmeOperatingProfile()) {
                    doCallNow(ft8Message);
                    break;
                }
                mainViewModel.addFollowCallsign(ft8Message.getCallsignFrom());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                    GeneralVariables.transmitMessages.add(ft8Message);// keep it in the transmit list for later status display
                }
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getFromCallTransmitCallsign(), -1, ft8Message.getAutoReplyExtraInfo());
                mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 5:// open target in QRZ
                showQrzFragment(ft8Message.getCallsignTo());
                break;
            case 6:// open source in QRZ
                showQrzFragment(ft8Message.getCallsignFrom());
                break;
            case 7:// open target in history
                navigateToLogFragment(ft8Message.getCallsignTo());
                break;
            case 8:// open source in history
                navigateToLogFragment(ft8Message.getCallsignFrom());
                break;
        }

        return super.onContextItemSelected(item);
    }

    /**
     * Navigate to the history screen.
     *
     * @param callsign callsign key
     */
    private void navigateToLogFragment(String callsign) {
        mainViewModel.queryKey = callsign;// pass the lookup key to the history page
        NavController navController = Navigation.findNavController(requireActivity(), R.id.fragmentContainerView);
        navController.navigate(R.id.action_menu_nav_mycalling_to_menu_nav_history);// open the history page
    }

    /**
     * Open the QRZ page.
     *
     * @param callsign callsign key
     */
    private void showQrzFragment(String callsign) {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity().getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;
        Bundle bundle = new Bundle();
        bundle.putString(QRZ_Fragment.CALLSIGN_PARAM, callsign);
        navHostFragment.getNavController().navigate(R.id.QRZ_Fragment, bundle);
    }

    /**
     * 在 Call 页面原子切换 FT8 / FT4 模式。
     */
    @SuppressLint("NotifyDataSetChanged")
    private void switchSignalMode(int mode) {
        if (GeneralVariables.isEmeOperatingProfile()
                || GeneralVariables.isSatelliteOperatingProfile()) {
            updateSignalModeUI();
            ToastMessage.show(GeneralVariables.isEmeOperatingProfile()
                    ? "请先从 EME 页关闭 Q65 EME 模式"
                    : "请先从卫星页关闭卫星 FT4 模式");
            return;
        }
        if (mode != FT8Common.FT8_MODE && mode != FT8Common.FT4_MODE) {
            Log.w(TAG, "Call page rejected non-FT8/FT4 mode: " + mode);
            mode = FT8Common.FT8_MODE;
        }
        if (GeneralVariables.getSignalMode() == mode) {
            updateSignalModeUI();
            return;
        }

        GeneralVariables.setSignalMode(mode);
        Log.i(TAG, "switchSignalMode: mode=" + FT8Common.modeToString(mode));
        restartForModeRuntimeChange();
        updateSignalModeUI();
        ToastMessage.show("switch to " + getCurrentModeLabel());
    }

    private int getSignalModeButtonId(int mode) {
        if (mode == FT8Common.Q65_MODE) {
            return R.id.rbQ65;
        }
        if (mode == FT8Common.FT4_MODE) {
            return R.id.rbFt4;
        }
        return R.id.rbFt8;
    }

    /**
     * Refresh the signal-mode radio UI from the current mode.
     */
    @SuppressLint("DefaultLocale")
    private void updateSignalModeUI() {
        int mode = GeneralVariables.getSignalMode();
        boolean emeProfile = GeneralVariables.isEmeOperatingProfile();
        boolean satelliteProfile = GeneralVariables.isSatelliteOperatingProfile();
        int targetButtonId = getSignalModeButtonId(mode);

        updatingSignalModeUi = true;
        try {
            if (binding.rgSignalMode.getCheckedRadioButtonId() != targetButtonId) {
                binding.rgSignalMode.check(targetButtonId);
            }
        } finally {
            updatingSignalModeUi = false;
        }
        updateQ65ConfigUi();
        updateEmeAssistButtonUi();
        binding.rbFt8.setVisibility(emeProfile ? View.GONE : View.VISIBLE);
        binding.rbFt4.setVisibility(emeProfile ? View.GONE : View.VISIBLE);
        binding.rbFt8.setEnabled(!satelliteProfile);
        binding.rbFt4.setEnabled(!satelliteProfile);
        binding.rbQ65.setEnabled(false);
        binding.q65SequenceControls.setVisibility(emeProfile ? View.VISIBLE : View.GONE);
        binding.dxpeditionMacroButton.setVisibility(
                emeProfile || satelliteProfile ? View.GONE : View.VISIBLE);
        if (emeProfile) {
            binding.transFreeTextEdit.setHint("Q65 发射报文");
            Editable current = binding.transFreeTextEdit.getText();
            if (current == null || current.toString().trim().isEmpty()
                    || "FREE TEXT".equalsIgnoreCase(current.toString().trim())) {
                binding.transFreeTextEdit.setText(getDefaultQ65Message());
                binding.transFreeTextEdit.setSelection(binding.transFreeTextEdit.length());
            }
        } else {
            binding.transFreeTextEdit.setHint(R.string.transmit_freeText);
        }
        showFreeTextEdit();
        updateOperatingModeUi();
        updateDxpeditionManualUi();

        Long currentUtc = mainViewModel == null ? null : mainViewModel.timerSec.getValue();
        if (currentUtc != null) {
            binding.timerTextView.setText("[" + getCurrentModeLabel() + "] "
                    + UtcTimer.getTimeStr(currentUtc));
        }

        Integer currentSequential = mainViewModel == null || mainViewModel.ft8TransmitSignal == null
                ? null
                : mainViewModel.ft8TransmitSignal.mutableSequential.getValue();
        updateTransmitSequentialLabel(currentSequential);

        // Refresh the base-frequency label for the active mode.
        binding.baseFrequencyTextView.setText(String.format(
                "[%s] " + GeneralVariables.getStringFromResource(R.string.sound_frequency_is),
                getCurrentModeLabel(),
                GeneralVariables.getBaseFrequency()
        ));

        // Refresh the target-callsign label for the active mode.
        if (mainViewModel.ft8TransmitSignal != null && mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue() != null) {
            TransmitCallsign transmitCallsign = mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue();
            if (!GeneralVariables.isSatelliteOperatingProfile()
                    && GeneralVariables.toModifier != null) {
                binding.toCallsignTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.target_callsign),
                        "[" + getCurrentModeLabel() + "] " + transmitCallsign.callsign + " " + GeneralVariables.toModifier));
            } else {
                binding.toCallsignTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.target_callsign),
                        "[" + getCurrentModeLabel() + "] " + transmitCallsign.callsign));
            }
        }
        if (mainViewModel.ft8TransmitSignal != null) {
            updateCqQueuePanel(mainViewModel.ft8TransmitSignal.getCqQueueSnapshot());
        }
    }

    /**
     * 发射序号和模式来自两个独立状态源，模式切换时也必须主动刷新，避免显示旧模式。
     */
    @SuppressLint("DefaultLocale")
    private void updateTransmitSequentialLabel(Integer sequential) {
        if (binding == null || sequential == null) {
            return;
        }
        if (isExperimentalManualTxMode()) {
            binding.transmittingSequentialTextView.setText(
                    "[" + getCurrentModeLabel() + "] MANUAL");
            return;
        }
        binding.transmittingSequentialTextView.setText(
                String.format("[%s] " + GeneralVariables.getStringFromResource(R.string.transmission_sequence),
                        getCurrentModeLabel(),
                        sequential));
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentMyCallingBinding.inflate(inflater, container, false);

        // 页面只修复与全局运行档位不一致的模式，不擅自退出 EME/卫星模式。
        int expectedMode = GeneralVariables.isEmeOperatingProfile()
                ? FT8Common.Q65_MODE
                : GeneralVariables.isSatelliteOperatingProfile()
                    ? FT8Common.FT4_MODE
                    : GeneralVariables.getSignalMode() == FT8Common.Q65_MODE
                        ? FT8Common.FT8_MODE
                        : GeneralVariables.getSignalMode();
        if (GeneralVariables.getSignalMode() != expectedMode) {
            GeneralVariables.setSignalMode(expectedMode);
            restartForModeRuntimeChange();
        }

        // Show the spectrum panel in landscape mode.
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.messageSpectrumView.run(mainViewModel, this);
        }

        // Initialize the function-order spinner.
        functionOrderSpinnerAdapter = new FunctionOrderSpinnerAdapter(requireContext(), mainViewModel);
        binding.functionOrderSpinner.setAdapter(functionOrderSpinnerAdapter);
        functionOrderSpinnerAdapter.notifyDataSetChanged();

        // Initialize the transmit-message list.
        transmitRecycleView = binding.transmitRecycleView;
        transmitCallListAdapter = new CallingListAdapter(this.getContext(), mainViewModel,
                GeneralVariables.transmitMessages, CallingListAdapter.ShowMode.MY_CALLING);
        transmitRecycleView.setLayoutManager(new LinearLayoutManager(requireContext()));
        transmitRecycleView.setAdapter(transmitCallListAdapter);
        transmitCallListAdapter.notifyDataSetChanged();
        initCqQueuePanel();

        // Initialize swipe actions and the context menu for the list.
        initRecyclerViewAction();
        requireActivity().registerForContextMenu(transmitRecycleView);

        // Refresh the mode UI when the fragment starts.
        updateSignalModeUI();

        binding.rgSignalMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (updatingSignalModeUi) {
                return;
            }
            if (checkedId == View.NO_ID) {
                updateSignalModeUI();
                return;
            }
            if (GeneralVariables.isEmeOperatingProfile()
                    || GeneralVariables.isSatelliteOperatingProfile()) {
                updateSignalModeUI();
                return;
            }
            int mode;
            if (checkedId == R.id.rbFt4) {
                mode = FT8Common.FT4_MODE;
            } else {
                mode = FT8Common.FT8_MODE;
            }
            switchSignalMode(mode);
        });
        binding.q65ConfigButton.setOnClickListener(view -> showQ65ConfigDialog());
        binding.emeAssistButton.setOnClickListener(view -> showEmeAssistDialog());
        binding.rbQ65.setOnLongClickListener(view -> {
            showQ65ConfigDialog();
            return true;
        });

        binding.dxpeditionManualCheckBox.setOnCheckedChangeListener(null);
        binding.dxpeditionFoxCheckBox.setOnCheckedChangeListener(null);
        applyManualDxpeditionMode(
                GeneralVariables.manualDxpeditionHoundMode,
                GeneralVariables.manualDxpeditionFoxMode,
                false
        );
        binding.dxpeditionManualCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingDxpeditionModeUi) {
                return;
            }
            applyManualDxpeditionMode(isChecked, false, true);
        });
        binding.dxpeditionFoxCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingDxpeditionModeUi) {
                return;
            }
            applyManualDxpeditionMode(false, isChecked, true);
        });
        binding.dxpeditionTxSlotsButton.setOnClickListener(view -> showDxpeditionTxSlotsPicker());
        binding.dxpeditionTxSlotsButton.setOnLongClickListener(view -> {
            showDxpeditionTxFrequencyDialog();
            return true;
        });
        binding.dxpeditionHelpButton.setOnClickListener(view -> showDxpeditionGuideDialog());
        binding.dxpeditionManualCheckBox.setOnLongClickListener(view -> {
            showDxpeditionGuideDialog();
            return true;
        });
        binding.dxpeditionFoxCheckBox.setOnLongClickListener(view -> {
            showDxpeditionGuideDialog();
            return true;
        });
        updateDxpeditionManualUi();

        binding.dxpeditionMacroButton.setOnClickListener(view -> showDxpeditionMacroPicker());
        binding.dxpeditionMacroButton.setOnLongClickListener(view -> {
            if (mainViewModel.ft8TransmitSignal.isManualDxpeditionFoxMode()) {
                showDxpeditionFoxCompoundManualDialog(
                        mainViewModel.ft8TransmitSignal.getSuggestedDxpeditionCompoundReport()
                );
                return true;
            }
            showDxpeditionMacroEditPicker();
            return true;
        });

        // Observe the UTC timer.
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                if (isExperimentalManualTxMode()) {
                    binding.timerTextView.setText("[" + getCurrentModeLabel() + "] MANUAL TX");
                    return;
                }
                binding.timerTextView.setText("[" + getCurrentModeLabel() + "] "
                        + UtcTimer.getTimeStr(aLong));
                updateOperatingModeUi();
            }
        });

        // Observe base-frequency changes.
        GeneralVariables.mutableBaseFrequency.observe(getViewLifecycleOwner(), new Observer<Float>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Float aFloat) {
                binding.baseFrequencyTextView.setText(String.format(
                        "[%s] " + GeneralVariables.getStringFromResource(R.string.sound_frequency_is),
                        getCurrentModeLabel(),
                        aFloat));
                updateAutoSessionStatus();
            }
        });

        GeneralVariables.mutableMyMaidenheadGrid.observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String grid) {
                updateAutoSessionStatus();
            }
        });

        GeneralVariables.mutableBandChange.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer bandIndex) {
                updateAutoSessionStatus();
            }
        });

        // Observe signal-mode changes.
        GeneralVariables.mutableSignalMode.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                updateSignalModeUI();
                updateDxpeditionManualUi();
                updateAutoSessionStatus();
            }
        });
        GeneralVariables.mutableOperatingProfile.observe(getViewLifecycleOwner(), profile -> {
            updateSignalModeUI();
            updateOperatingModeUi();
        });
        GeneralVariables.mutableOperatingTrackingStatus.observe(getViewLifecycleOwner(), status ->
                updateOperatingModeUi());

        // Observe transmit-state changes.
        Observer<Boolean> transmittingObserver = new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (mainViewModel.ft8TransmitSignal.isTransmitting()) {
                    binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_send_red_48);
                    binding.setTransmitImageButton.setAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.view_blink));
                } else {
                    if (!isExperimentalManualTxMode()
                            && mainViewModel.ft8TransmitSignal.isActivated()
                            && mainViewModel.hamRecorder.isRunning()) {
                        binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_send_white_48);
                    } else {
                        binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_cancel_schedule_send_off);
                    }
                    binding.setTransmitImageButton.setAnimation(null);
                }

                // Update the pause button to match the current transmit state.
                if (mainViewModel.ft8TransmitSignal.isTransmitting()) {
                    binding.pauseTransmittingImageButton.setImageResource(R.drawable.ic_baseline_pause_circle_outline_24);
                    binding.pauseTransmittingImageButton.setVisibility(View.VISIBLE);
                } else {
                    binding.pauseTransmittingImageButton.setVisibility(View.GONE);
                    binding.pauseTransmittingImageButton.setImageResource(R.drawable.ic_baseline_pause_disable_circle_outline_24);
                }
                updateAutoSessionStatus();
            }
        };
        mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observe(getViewLifecycleOwner(), transmittingObserver);
        mainViewModel.ft8TransmitSignal.mutableIsActivated.observe(getViewLifecycleOwner(), transmittingObserver);

        // Pause transmission on demand.
        binding.pauseTransmittingImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (GeneralVariables.isEmeOperatingProfile()) {
                    mainViewModel.ft8TransmitSignal.stopQ65Sequence("用户暂停 Q65 序列");
                }
                mainViewModel.ft8TransmitSignal.setTransmitting(false);
                GeneralVariables.resetLaunchSupervision();// reset launch supervision state
                updateSignalModeUI();
            }
        });

        // Observe the transmit function list.
        mainViewModel.ft8TransmitSignal.mutableFunctions.observe(getViewLifecycleOwner(),
                new Observer<ArrayList<FunctionOfTransmit>>() {
                    @Override
                    public void onChanged(ArrayList<FunctionOfTransmit> functionOfTransmits) {
                        functionOrderSpinnerAdapter.notifyDataSetChanged();
                        updateAutoSessionStatus();
                    }
                });

        // Observe DXpedition Fox slot status.
        mainViewModel.ft8TransmitSignal.mutableDxpeditionFoxSlotStatus.observe(getViewLifecycleOwner(),
                new Observer<String>() {
                    @Override
                    public void onChanged(String s) {
                        updateDxpeditionManualUi();
                        updateAutoSessionStatus();
                    }
                });

        mainViewModel.ft8TransmitSignal.mutableFunctionOrder.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                binding.functionOrderSpinner.setSelection(
                        mainViewModel.ft8TransmitSignal.getFunctionSelectionIndex(integer)
                );
                updateCqQueuePanel(mainViewModel.ft8TransmitSignal.getCqQueueSnapshot());
                updateAutoSessionStatus();
            }
        });

        // Observe CQ queue changes.
        mainViewModel.ft8TransmitSignal.mutableCqQueue.observe(getViewLifecycleOwner(), new Observer<ArrayList<CqCallEntry>>() {
            @Override
            public void onChanged(ArrayList<CqCallEntry> cqCallEntries) {
                updateCqQueuePanel(cqCallEntries);
                updateAutoSessionStatus();
            }
        });

        binding.functionOrderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if (mainViewModel.ft8TransmitSignal.functionList.size() > 1) {
                    mainViewModel.ft8TransmitSignal.setCurrentFunctionOrder(
                            mainViewModel.ft8TransmitSignal.getFunctionOrderAt(i)
                    );
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });

        // Observe target-callsign changes.
        mainViewModel.ft8TransmitSignal.mutableToCallsign.observe(getViewLifecycleOwner(), new Observer<TransmitCallsign>() {
            @Override
            public void onChanged(TransmitCallsign transmitCallsign) {
                if (transmitCallsign == null) {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign),
                            "[" + getCurrentModeLabel() + "]"));
                    updateCqQueuePanel(mainViewModel.ft8TransmitSignal.getCqQueueSnapshot());
                    updateAutoSessionStatus();
                    return;
                }
                if (!GeneralVariables.isSatelliteOperatingProfile()
                        && GeneralVariables.toModifier != null) {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign),
                            "[" + getCurrentModeLabel() + "] "
                                    + transmitCallsign.callsign + " " + GeneralVariables.toModifier));
                } else {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign),
                            "[" + getCurrentModeLabel() + "] "
                                    + transmitCallsign.callsign));
                }
                updateCqQueuePanel(mainViewModel.ft8TransmitSignal.getCqQueueSnapshot());
                updateAutoSessionStatus();
            }
        });

        // Observe transmit-sequence changes.
        mainViewModel.ft8TransmitSignal.mutableSequential.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                updateTransmitSequentialLabel(integer);
            }
        });

        // Toggle scheduled transmission on or off.
        binding.setTransmitImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (GeneralVariables.isEmeOperatingProfile()
                        && GeneralVariables.getSignalMode() == FT8Common.Q65_MODE) {
                    if (mainViewModel.ft8TransmitSignal.isActivated()) {
                        mainViewModel.ft8TransmitSignal.stopQ65Sequence("用户停止 Q65 序列");
                        mainViewModel.ft8TransmitSignal.setTransmitting(false);
                    } else {
                        String message = binding.transFreeTextEdit.getText() == null
                                ? "" : binding.transFreeTextEdit.getText().toString();
                        int txSequence = binding.rbQ65Tx2.isChecked() ? 1 : 0;
                        boolean armed = mainViewModel.ft8TransmitSignal.armQ65Sequence(
                                message,
                                txSequence,
                                binding.q65RepeatCheckBox.isChecked());
                        if (!armed) {
                            ToastMessage.show("Q65 报文为空或 TX 序列无效");
                        }
                    }
                    GeneralVariables.resetLaunchSupervision();
                    updateSignalModeUI();
                    return;
                }
                if (isExperimentalManualTxMode()) {
                    // Experimental TX is intentionally triggered from the CQ button near the text box.
                    return;
                }
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.restTransmitting();
                }
                mainViewModel.ft8TransmitSignal.setActivated(!mainViewModel.ft8TransmitSignal.isActivated());
                GeneralVariables.resetLaunchSupervision();// reset launch supervision state
            }
        });

        // Observe message-count changes and auto-scroll when appropriate.
        mainViewModel.mutableTransmitMessagesCount.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer count) {
                binding.decoderCounterTextView.setText(String.format(
                        "[%s] " + GeneralVariables.getStringFromResource(R.string.message_count),
                        getCurrentModeLabel(),
                        GeneralVariables.transmitMessages.size()));

                transmitCallListAdapter.notifyDataSetChanged();

                if (transmitRecycleView.computeVerticalScrollRange()
                        - transmitRecycleView.computeVerticalScrollExtent()
                        - transmitRecycleView.computeVerticalScrollOffset() < 300) {
                    transmitRecycleView.scrollToPosition(transmitCallListAdapter.getItemCount() - 1);
                }
            }
        });

        // Clear the local calling list.
        binding.clearMycallListImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.clearTransmittingMessage();
            }
        });

        // Reset the transmit target back to CQ.
        binding.resetToCQImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (GeneralVariables.isEmeOperatingProfile()) {
                    binding.transFreeTextEdit.setText(getDefaultQ65Message());
                    binding.transFreeTextEdit.setSelection(binding.transFreeTextEdit.length());
                    GeneralVariables.resetLaunchSupervision();
                    return;
                }
                if (isExperimentalManualTxMode()) {
                    if (mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue() == null) {
                        mainViewModel.ft8TransmitSignal.restTransmitting();
                    }
                    mainViewModel.ft8TransmitSignal.transmitNow();
                    GeneralVariables.resetLaunchSupervision();
                    return;
                }
                mainViewModel.ft8TransmitSignal.resetToCQ();
                GeneralVariables.resetLaunchSupervision();// reset launch supervision state
            }
        });

        // Watch free-text input changes.
        binding.transFreeTextEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                mainViewModel.ft8TransmitSignal.setFreeText(editable.toString().toUpperCase());
                updateFreeTextTypeHint();
            }
        });
        binding.transFreeTextTypeTextView.setOnClickListener(view -> showTransmitTypeGuideDialog());
        binding.transFreeTextTypeTextView.setOnLongClickListener(view -> {
            showTransmitTypeGuideDialog();
            return true;
        });

        binding.resetToCQImageView.setLongClickable(true);
        binding.resetToCQImageView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (GeneralVariables.isEmeOperatingProfile()) {
                    return true;
                }
                mainViewModel.setTransmitIsFreeText(!mainViewModel.getTransitIsFreeText());
                showFreeTextEdit();
                return true;
            }
        });

        binding.mycallToolsBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GeneralVariables.simpleCallItemMode = !GeneralVariables.simpleCallItemMode;
                transmitRecycleView.setAdapter(transmitCallListAdapter);
                transmitCallListAdapter.notifyDataSetChanged();
                transmitRecycleView.scrollToPosition(transmitCallListAdapter.getItemCount() - 1);
                if (GeneralVariables.simpleCallItemMode) {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_simple_mode));
                } else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_standard_mode));
                }
            }
        });

        showFreeTextEdit();
        updateSignalModeUI();
        updateDxpeditionManualUi();
        updateAutoSessionStatus();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mainViewModel.emeAssistController.getTrackingResultLiveData().observe(
                getViewLifecycleOwner(),
                result -> {
                    if (binding == null) {
                        Log.i(TAG, "skip EME tracking observer update: reason=binding-null");
                        return;
                    }
                    updateEmeAssistButtonUi();
                    updateAutoSessionStatus();
                    Runnable refreshAction = emeTrackingPanelRefreshAction;
                    if (refreshAction != null) {
                        refreshAction.run();
                    }
                });
    }

    @Override
    public void onDestroyView() {
        Log.i(TAG, "MyCallingFragment onDestroyView: detach EME tracking panel");
        detachEmeTrackingPanel("fragment-destroy-view");
        if (emeTrackingDialog != null) {
            emeTrackingDialog.setOnDismissListener(null);
            if (emeTrackingDialog.isShowing()) {
                emeTrackingDialog.dismiss();
            }
            emeTrackingDialog = null;
        }
        binding = null;
        super.onDestroyView();
    }

    private void showFreeTextEdit() {
        if (GeneralVariables.isEmeOperatingProfile()) {
            binding.transFreeTextEdit.setVisibility(View.VISIBLE);
            binding.transFreeTextTypeTextView.setVisibility(View.GONE);
            binding.q65SequenceControls.setVisibility(View.VISIBLE);
            binding.functionOrderSpinner.setVisibility(View.GONE);
            binding.dxpeditionMacroButton.setVisibility(View.GONE);
            updateAutoSessionStatus();
            return;
        }
        binding.q65SequenceControls.setVisibility(View.GONE);
        if (mainViewModel.getTransitIsFreeText()) {
            binding.transFreeTextEdit.setVisibility(View.VISIBLE);
            binding.transFreeTextTypeTextView.setVisibility(View.VISIBLE);
            binding.functionOrderSpinner.setVisibility(View.GONE);
            syncFreeTextInput();
            updateFreeTextTypeHint();
        } else {
            binding.transFreeTextEdit.setVisibility(View.GONE);
            binding.transFreeTextTypeTextView.setVisibility(View.GONE);
            binding.functionOrderSpinner.setVisibility(View.VISIBLE);
        }
        updateDxpeditionManualUi();
        if (mainViewModel.ft8TransmitSignal != null) {
            mainViewModel.ft8TransmitSignal.refreshSessionModeByCurrentTarget();
        }
        updateAutoSessionStatus();
    }

    /**
     * Synchronize the free-text input field with the current transmit state.
     */
    private void syncFreeTextInput() {
        String currentFreeText = mainViewModel.ft8TransmitSignal.getFreeText();
        Editable editable = binding.transFreeTextEdit.getText();
        String currentInput = editable == null ? "" : editable.toString();
        if (!currentInput.equals(currentFreeText)) {
            binding.transFreeTextEdit.setText(currentFreeText);
            binding.transFreeTextEdit.setSelection(binding.transFreeTextEdit.getText().length());
        }
    }

    private void updateFreeTextTypeHint() {
        if (binding == null || !mainViewModel.getTransitIsFreeText()) {
            return;
        }

        Editable editable = binding.transFreeTextEdit.getText();
        String input = editable == null ? "" : editable.toString();
        String typeInfo = GenerateFT8.getPackedTypeInfo(input);
        if (typeInfo.length() == 0) {
            binding.transFreeTextTypeTextView.setText(R.string.transmit_type_hint_empty);
            return;
        }

        binding.transFreeTextTypeTextView.setText(getString(R.string.transmit_type_hint, typeInfo));
    }

    private void initRecyclerViewAction() {
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.ANIMATION_TYPE_DRAG,
                ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                if (direction == ItemTouchHelper.START) {
                    Ft8Message message = transmitCallListAdapter.getMessageByViewHolder(viewHolder);
                    if (message != null) {
                        if (!message.getCallsignFrom().equals("<...>")
                                && !GeneralVariables.checkIsMyCallsign(message.getCallsignFrom())
                                && !(message.i3 == 0 && message.n3 == 0)) {
                            doCallNow(message);
                        }
                    }
                    transmitCallListAdapter.notifyItemChanged(viewHolder.getAdapterPosition());
                }
                if (direction == ItemTouchHelper.END) {// swipe left to delete
                    transmitCallListAdapter.deleteMessage(viewHolder.getAdapterPosition());
                    transmitCallListAdapter.notifyItemRemoved(viewHolder.getAdapterPosition());
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                Drawable callIcon = ContextCompat.getDrawable(requireActivity(), R.drawable.ic_baseline_send_red_48);
                Drawable delIcon = ContextCompat.getDrawable(requireActivity(), R.drawable.log_item_delete_icon);
                Drawable background = new ColorDrawable(Color.LTGRAY);
                Ft8Message message = transmitCallListAdapter.getMessageByViewHolder(viewHolder);
                if (message == null) {
                    return;
                }
                if (message.getCallsignFrom().equals("<...>")) {
                    return;
                }
                Drawable icon;
                if (dX > 0) {
                    icon = delIcon;
                } else {
                    icon = callIcon;
                }
                View itemView = viewHolder.itemView;
                int iconMargin = (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                int iconLeft, iconRight, iconTop, iconBottom;
                int backTop, backBottom, backLeft, backRight;
                backTop = itemView.getTop();
                backBottom = itemView.getBottom();
                iconTop = itemView.getTop() + (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                iconBottom = iconTop + icon.getIntrinsicHeight();
                if (dX > 0) {
                    backLeft = itemView.getLeft();
                    backRight = itemView.getLeft() + (int) dX;
                    background.setBounds(backLeft, backTop, backRight, backBottom);
                    iconLeft = itemView.getLeft() + iconMargin;
                    iconRight = iconLeft + icon.getIntrinsicWidth();
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                } else if (dX < 0) {
                    backRight = itemView.getRight();
                    backLeft = itemView.getRight() + (int) dX;
                    background.setBounds(backLeft, backTop, backRight, backBottom);
                    iconRight = itemView.getRight() - iconMargin;
                    iconLeft = iconRight - icon.getIntrinsicWidth();
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                } else {
                    background.setBounds(0, 0, 0, 0);
                    icon.setBounds(0, 0, 0, 0);
                }
                background.draw(c);
                icon.draw(c);
            }
        }).attachToRecyclerView(binding.transmitRecycleView);
    }
}
