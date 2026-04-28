package com.bg7yoz.ft8cn.ui;
/**
 * 呼叫界面。
 * 支持 FT8 / FT4 模式切换。
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
import com.bg7yoz.ft8cn.databinding.FragmentMyCallingBinding;
import com.bg7yoz.ft8cn.ft8transmit.DxpeditionFoxSlotFrequencyConfig;
import com.bg7yoz.ft8cn.ft8transmit.DxpeditionMacroSupport;
import com.bg7yoz.ft8cn.ft8transmit.FunctionOfTransmit;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;

public class MyCallingFragment extends Fragment {
    private static final String TAG = "MyCallingFragment";
    private FragmentMyCallingBinding binding;
    private MainViewModel mainViewModel;

    private RecyclerView transmitRecycleView;
    private CallingListAdapter transmitCallListAdapter;
    private FunctionOrderSpinnerAdapter functionOrderSpinnerAdapter;
    private boolean updatingDxpeditionModeUi = false;

    private boolean isExperimentalManualTxMode() {
        return GeneralVariables.isExperimentalCodecEnabled();
    }

    private String getCurrentModeLabel() {
        return GeneralVariables.getActiveModeLabel();
    }

    private void updateDxpeditionManualUi() {
        if (binding == null) {
            return;
        }
        boolean enabled = !isExperimentalManualTxMode()
                && !mainViewModel.getTransitIsFreeText()
                && GeneralVariables.getSignalMode() == FT8Common.FT8_MODE;
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
        if (!enabled && (binding.dxpeditionManualCheckBox.isChecked() || binding.dxpeditionFoxCheckBox.isChecked())) {
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
        binding.autoSessionTextView.setText(mainViewModel.ft8TransmitSignal.getAutoSessionStatusText());
    }

    static {
        System.loadLibrary("ft8cn");
    }

    /**
     * 马上对发起者呼叫
     *
     * @param message 消息
     */
    private void doCallNow(Ft8Message message) {
        mainViewModel.addFollowCallsign(message.getCallsignFrom());
        if (!mainViewModel.ft8TransmitSignal.isActivated()) {
            mainViewModel.ft8TransmitSignal.setActivated(true);
            GeneralVariables.transmitMessages.add(message);//把消息添加到关注列表中
        }
        // 呼叫发起者
        mainViewModel.ft8TransmitSignal.setTransmit(message.getFromCallTransmitCallsign(), 1, message.getAutoReplyExtraInfo());
        mainViewModel.ft8TransmitSignal.transmitNow();

        GeneralVariables.resetLaunchSupervision();//复位自动监管
    }

    /**
     * 菜单选项
     *
     * @param item 菜单
     * @return 是否选择
     */
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        int position = (int) item.getActionView().getTag();
        Ft8Message ft8Message = transmitCallListAdapter.getMessageByPosition(position);
        if (ft8Message == null) return super.onContextItemSelected(item);

        GeneralVariables.resetLaunchSupervision();//复位自动监管
        switch (item.getItemId()) {
            case 1://时序与发送者相反
                Log.d(TAG, "呼叫：" + ft8Message.getCallsignTo());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                }
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getToCallTransmitCallsign(), 1, ft8Message.getAutoReplyExtraInfo());
                mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 3:
                Log.d(TAG, "呼叫：" + ft8Message.getCallsignFrom());
                doCallNow(ft8Message);
                break;

            case 4://回复
                Log.d(TAG, "回复：" + ft8Message.getCallsignFrom());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignFrom());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                    GeneralVariables.transmitMessages.add(ft8Message);//把消息添加到关注列表中
                }
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getFromCallTransmitCallsign(), -1, ft8Message.getAutoReplyExtraInfo());
                mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 5://to 的QRZ
                showQrzFragment(ft8Message.getCallsignTo());
                break;
            case 6://from 的QRZ
                showQrzFragment(ft8Message.getCallsignFrom());
                break;
            case 7://查to的日志
                navigateToLogFragment(ft8Message.getCallsignTo());
                break;
            case 8://查from的日志
                navigateToLogFragment(ft8Message.getCallsignFrom());
                break;
        }

        return super.onContextItemSelected(item);
    }

    /**
     * 跳转到日志查询界面
     *
     * @param callsign 呼号
     */
    private void navigateToLogFragment(String callsign) {
        mainViewModel.queryKey = callsign;//把呼号作为关键字提交
        NavController navController = Navigation.findNavController(requireActivity(), R.id.fragmentContainerView);
        navController.navigate(R.id.action_menu_nav_mycalling_to_menu_nav_history);//跳转到日志
    }

    /**
     * 查询QRZ信息
     *
     * @param callsign 呼号
     */
    private void showQrzFragment(String callsign) {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity().getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;
        Bundle bundle = new Bundle();
        bundle.putString(QRZ_Fragment.CALLSIGN_PARAM, callsign);
        navHostFragment.getNavController().navigate(R.id.QRZ_Fragment, bundle);
    }

    /**
     * 切换 FT8 / FT4 模式
     */
    @SuppressLint("NotifyDataSetChanged")
    private void switchSignalMode(int mode) {
        if (GeneralVariables.getSignalMode() == mode) {
            updateSignalModeUI();
            return;
        }

        GeneralVariables.setSignalMode(mode);

        // 切换模式时，重建接收与发射时钟
        if (mainViewModel.ft8SignalListener != null) {
            mainViewModel.ft8SignalListener.restartByCurrentMode();
        }
        if (mainViewModel.ft8TransmitSignal != null) {
            mainViewModel.ft8TransmitSignal.restartByCurrentMode();
        }

        // 切模式时停止发射，避免跨模式卡住
        mainViewModel.ft8TransmitSignal.setActivated(false);
        mainViewModel.ft8TransmitSignal.setTransmitting(false);

        // 复位到 CQ 状态
        mainViewModel.ft8TransmitSignal.resetToCQ();

        // 清空当前显示列表，避免 FT8/FT4 混合
        mainViewModel.clearTransmittingMessage();

        updateSignalModeUI();

        ToastMessage.show("切换到 " + getCurrentModeLabel());
    }

    /**
     * 刷新模式相关 UI
     */
    @SuppressLint("DefaultLocale")
    private void updateSignalModeUI() {
        int mode = GeneralVariables.getSignalMode();

        if (mode == FT8Common.FT4_MODE) {
            binding.rbFt4.setChecked(true);
        } else {
            binding.rbFt8.setChecked(true);
        }

        // 更新发射频率标题
        binding.baseFrequencyTextView.setText(String.format(
                "[%s] " + GeneralVariables.getStringFromResource(R.string.sound_frequency_is),
                getCurrentModeLabel(),
                GeneralVariables.getBaseFrequency()
        ));

        // 更新当前目标显示
        if (mainViewModel.ft8TransmitSignal != null && mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue() != null) {
            TransmitCallsign transmitCallsign = mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue();
            if (GeneralVariables.toModifier != null) {
                binding.toCallsignTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.target_callsign),
                        "[" + getCurrentModeLabel() + "] " + transmitCallsign.callsign + " " + GeneralVariables.toModifier));
            } else {
                binding.toCallsignTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.target_callsign),
                        "[" + getCurrentModeLabel() + "] " + transmitCallsign.callsign));
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentMyCallingBinding.inflate(inflater, container, false);

        // 当横屏时显示频谱图
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.messageSpectrumView.run(mainViewModel, this);
        }

        // 发射消息的列表
        functionOrderSpinnerAdapter = new FunctionOrderSpinnerAdapter(requireContext(), mainViewModel);
        binding.functionOrderSpinner.setAdapter(functionOrderSpinnerAdapter);
        functionOrderSpinnerAdapter.notifyDataSetChanged();

        // 关注的消息列表
        transmitRecycleView = binding.transmitRecycleView;
        transmitCallListAdapter = new CallingListAdapter(this.getContext(), mainViewModel,
                GeneralVariables.transmitMessages, CallingListAdapter.ShowMode.MY_CALLING);
        transmitRecycleView.setLayoutManager(new LinearLayoutManager(requireContext()));
        transmitRecycleView.setAdapter(transmitCallListAdapter);
        transmitCallListAdapter.notifyDataSetChanged();

        // 设置消息列表滑动，用于快速呼叫
        initRecyclerViewAction();
        requireActivity().registerForContextMenu(transmitRecycleView);

        // 初始化模式选择 UI
        if (GeneralVariables.getSignalMode() == FT8Common.FT4_MODE) {
            binding.rbFt4.setChecked(true);
        } else {
            binding.rbFt8.setChecked(true);
        }

        binding.rgSignalMode.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = checkedId == R.id.rbFt4 ? FT8Common.FT4_MODE : FT8Common.FT8_MODE;
            switchSignalMode(mode);
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

        // 显示UTC时间
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                if (isExperimentalManualTxMode()) {
                    binding.timerTextView.setText("[" + getCurrentModeLabel() + "] MANUAL TX");
                    return;
                }
                binding.timerTextView.setText("[" + getCurrentModeLabel() + "] "
                        + UtcTimer.getTimeStr(aLong));
            }
        });

        // 显示发射频率
        GeneralVariables.mutableBaseFrequency.observe(getViewLifecycleOwner(), new Observer<Float>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Float aFloat) {
                binding.baseFrequencyTextView.setText(String.format(
                        "[%s] " + GeneralVariables.getStringFromResource(R.string.sound_frequency_is),
                        getCurrentModeLabel(),
                        aFloat));
            }
        });

        // 观察模式变化
        GeneralVariables.mutableSignalMode.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                updateSignalModeUI();
                updateDxpeditionManualUi();
                updateAutoSessionStatus();
            }
        });

        // 观察发射状态按钮的变化
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

                // 暂停播放按键
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

        // 暂停按钮
        binding.pauseTransmittingImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.ft8TransmitSignal.setTransmitting(false);
                GeneralVariables.resetLaunchSupervision();//复位自动监管
            }
        });

        // 监视命令程序
        mainViewModel.ft8TransmitSignal.mutableFunctions.observe(getViewLifecycleOwner(),
                new Observer<ArrayList<FunctionOfTransmit>>() {
                    @Override
                    public void onChanged(ArrayList<FunctionOfTransmit> functionOfTransmits) {
                        functionOrderSpinnerAdapter.notifyDataSetChanged();
                        updateAutoSessionStatus();
                    }
                });

        // 观察指令序号的变化
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
                updateAutoSessionStatus();
            }
        });

        // 设置当指令序号被选择的事件
        mainViewModel.ft8TransmitSignal.mutableCqQueue.observe(getViewLifecycleOwner(), new Observer<ArrayList<CqCallEntry>>() {
            @Override
            public void onChanged(ArrayList<CqCallEntry> cqCallEntries) {
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

        // 显示当前目标呼号
        mainViewModel.ft8TransmitSignal.mutableToCallsign.observe(getViewLifecycleOwner(), new Observer<TransmitCallsign>() {
            @Override
            public void onChanged(TransmitCallsign transmitCallsign) {
                if (transmitCallsign == null) {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign),
                            "[" + getCurrentModeLabel() + "]"));
                    updateAutoSessionStatus();
                    return;
                }
                if (GeneralVariables.toModifier != null) {
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
                updateAutoSessionStatus();
            }
        });

        // 显示当前发射的时序
        mainViewModel.ft8TransmitSignal.mutableSequential.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer integer) {
                if (isExperimentalManualTxMode()) {
                    binding.transmittingSequentialTextView.setText(
                            "[" + getCurrentModeLabel() + "] MANUAL");
                    return;
                }
                binding.transmittingSequentialTextView.setText(
                        String.format("[%s] " + GeneralVariables.getStringFromResource(R.string.transmission_sequence),
                                getCurrentModeLabel(),
                                integer));
            }
        });

        // 设置发射按钮
        binding.setTransmitImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isExperimentalManualTxMode()) {
                    // Experimental TX is intentionally triggered from the CQ button near the text box.
                    return;
                }
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.restTransmitting();
                }
                mainViewModel.ft8TransmitSignal.setActivated(!mainViewModel.ft8TransmitSignal.isActivated());
                GeneralVariables.resetLaunchSupervision();//复位自动监管
            }
        });

        // 观察传输消息列表的变化
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

        // 清除传输消息列表
        binding.clearMycallListImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.clearTransmittingMessage();
            }
        });

        // 复位到CQ按键
        binding.resetToCQImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isExperimentalManualTxMode()) {
                    if (mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue() == null) {
                        mainViewModel.ft8TransmitSignal.restTransmitting();
                    }
                    mainViewModel.ft8TransmitSignal.transmitNow();
                    GeneralVariables.resetLaunchSupervision();
                    return;
                }
                mainViewModel.ft8TransmitSignal.resetToCQ();
                GeneralVariables.resetLaunchSupervision();//复位自动监管
            }
        });

        // 自由文本输入框的限定操作
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

    private void showFreeTextEdit() {
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
     * 设置列表滑动动作
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
                if (direction == ItemTouchHelper.END) {//删除
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
