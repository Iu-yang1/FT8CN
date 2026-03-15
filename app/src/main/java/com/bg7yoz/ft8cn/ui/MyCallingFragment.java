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
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;

import androidx.annotation.NonNull;
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
import com.bg7yoz.ft8cn.databinding.FragmentMyCallingBinding;
import com.bg7yoz.ft8cn.ft8transmit.FunctionOfTransmit;
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

        ToastMessage.show("切换到 " + FT8Common.modeToString(mode));
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
                FT8Common.modeToString(mode),
                GeneralVariables.getBaseFrequency()
        ));

        // 更新当前目标显示
        if (mainViewModel.ft8TransmitSignal != null && mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue() != null) {
            TransmitCallsign transmitCallsign = mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue();
            if (GeneralVariables.toModifier != null) {
                binding.toCallsignTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.target_callsign),
                        "[" + FT8Common.modeToString(mode) + "] " + transmitCallsign.callsign + " " + GeneralVariables.toModifier));
            } else {
                binding.toCallsignTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.target_callsign),
                        "[" + FT8Common.modeToString(mode) + "] " + transmitCallsign.callsign));
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

        // 显示UTC时间
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                binding.timerTextView.setText("[" + FT8Common.modeToString(GeneralVariables.getSignalMode()) + "] "
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
                        FT8Common.modeToString(GeneralVariables.getSignalMode()),
                        aFloat));
            }
        });

        // 观察模式变化
        GeneralVariables.mutableSignalMode.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                updateSignalModeUI();
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
                    if (mainViewModel.ft8TransmitSignal.isActivated() && mainViewModel.hamRecorder.isRunning()) {
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
                    }
                });

        // 观察指令序号的变化
        mainViewModel.ft8TransmitSignal.mutableFunctionOrder.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (mainViewModel.ft8TransmitSignal.functionList.size() < 6) {
                    binding.functionOrderSpinner.setSelection(0);
                } else {
                    binding.functionOrderSpinner.setSelection(integer - 1);
                }
            }
        });

        // 设置当指令序号被选择的事件
        binding.functionOrderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if (mainViewModel.ft8TransmitSignal.functionList.size() > 1) {
                    mainViewModel.ft8TransmitSignal.setCurrentFunctionOrder(i + 1);
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
                if (GeneralVariables.toModifier != null) {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign),
                            "[" + FT8Common.modeToString(GeneralVariables.getSignalMode()) + "] "
                                    + transmitCallsign.callsign + " " + GeneralVariables.toModifier));
                } else {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign),
                            "[" + FT8Common.modeToString(GeneralVariables.getSignalMode()) + "] "
                                    + transmitCallsign.callsign));
                }
            }
        });

        // 显示当前发射的时序
        mainViewModel.ft8TransmitSignal.mutableSequential.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer integer) {
                binding.transmittingSequentialTextView.setText(
                        String.format("[%s] " + GeneralVariables.getStringFromResource(R.string.transmission_sequence),
                                FT8Common.modeToString(GeneralVariables.getSignalMode()),
                                integer));
            }
        });

        // 设置发射按钮
        binding.setTransmitImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
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
                        FT8Common.modeToString(GeneralVariables.getSignalMode()),
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
            }
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
        return binding.getRoot();
    }

    private void showFreeTextEdit() {
        if (mainViewModel.getTransitIsFreeText()) {
            binding.transFreeTextEdit.setVisibility(View.VISIBLE);
            binding.functionOrderSpinner.setVisibility(View.GONE);
        } else {
            binding.transFreeTextEdit.setVisibility(View.GONE);
            binding.functionOrderSpinner.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 设置列表滑动动作
     */
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
