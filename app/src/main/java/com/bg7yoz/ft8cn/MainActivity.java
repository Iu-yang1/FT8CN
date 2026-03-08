package com.bg7yoz.ft8cn;
/**
 * FT8CN程序的主Activity。本APP采用Fragment框架实现，每个Fragment实现不同的功能。
 * 1. 顶部时间条支持 FT8 / FT4 周期切换
 * 2. 发射时序高亮按当前模式判断
 * 3. 顶部时间条改为连续刷新
 *
 * @author BG7YOZ
 * @date 2022.5.6
 */

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.bg7yoz.ft8cn.bluetooth.BluetoothStateBroadcastReceive;
import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.connector.CableSerialPort;
import com.bg7yoz.ft8cn.database.OnAfterQueryConfig;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.databinding.MainActivityBinding;
import com.bg7yoz.ft8cn.floatview.FloatView;
import com.bg7yoz.ft8cn.floatview.FloatViewButton;
import com.bg7yoz.ft8cn.grid_tracker.GridTrackerMainActivity;
import com.bg7yoz.ft8cn.log.ImportSharedLogs;
import com.bg7yoz.ft8cn.log.OnShareLogEvents;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.FreqDialog;
import com.bg7yoz.ft8cn.ui.SetVolumeDialog;
import com.bg7yoz.ft8cn.ui.ShareLogsProgressDialog;
import com.bg7yoz.ft8cn.ui.ToastMessage;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private BluetoothStateBroadcastReceive mReceive;
    private static final String TAG = "MainActivity";
    private MainViewModel mainViewModel;
    private NavController navController;
    private static boolean animatorRunned = false;

    private MainActivityBinding binding;
    private FloatView floatView;

    private ShareLogsProgressDialog dialog = null;//生成共享log的对话框

    String[] permissions = new String[]{Manifest.permission.RECORD_AUDIO
            , Manifest.permission.ACCESS_COARSE_LOCATION
            , Manifest.permission.ACCESS_WIFI_STATE
            , Manifest.permission.BLUETOOTH
            , Manifest.permission.BLUETOOTH_ADMIN
            , Manifest.permission.MODIFY_AUDIO_SETTINGS
            , Manifest.permission.WAKE_LOCK
            , Manifest.permission.ACCESS_FINE_LOCATION};
    List<String> mPermissionList = new ArrayList<>();

    private static final int PERMISSION_REQUEST = 1;

    /**
     * 连续刷新顶部进度条
     */
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateUtcProgressBarSmooth();
            progressHandler.postDelayed(this, 40); // 约25fps，足够顺滑
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{Manifest.permission.RECORD_AUDIO
                    , Manifest.permission.ACCESS_COARSE_LOCATION
                    , Manifest.permission.ACCESS_WIFI_STATE
                    , Manifest.permission.BLUETOOTH
                    , Manifest.permission.BLUETOOTH_ADMIN
                    , Manifest.permission.BLUETOOTH_CONNECT
                    , Manifest.permission.MODIFY_AUDIO_SETTINGS
                    , Manifest.permission.WAKE_LOCK
                    , Manifest.permission.ACCESS_FINE_LOCATION};
        }

        checkPermission();

        //全屏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                , WindowManager.LayoutParams.FLAG_FULLSCREEN);

        //禁止休眠
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                , WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        super.onCreate(savedInstanceState);
        GeneralVariables.getInstance().setMainContext(getApplicationContext());

        //判断是不是简体中文
        GeneralVariables.isTraditionalChinese =
                getResources().getConfiguration().locale.getDisplayCountry().equals("中國");

        //确定是不是中国、香港、澳门、台湾
        GeneralVariables.isChina = (getResources().getConfiguration().locale
                .getLanguage().toUpperCase().startsWith("ZH"));

        mainViewModel = MainViewModel.getInstance(this);
        binding = MainActivityBinding.inflate(getLayoutInflater());
        binding.initDataLayout.setVisibility(View.VISIBLE);//显示LOG页面
        setContentView(binding.getRoot());

        ToastMessage.getInstance();
        registerBluetoothReceiver();//注册蓝牙动作改变的广播
        if (mainViewModel.isBTConnected()) {
            mainViewModel.setBlueToothOn();
        }

        //观察DEBUG信息
        GeneralVariables.mutableDebugMessage.observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s.length() > 1) {
                    binding.debugLayout.setVisibility(View.VISIBLE);
                } else {
                    binding.debugLayout.setVisibility(View.GONE);
                }
                binding.debugMessageTextView.setText(s);
            }
        });

        binding.debugLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.debugLayout.setVisibility(View.GONE);
            }
        });

        mainViewModel.mutableIsRecording.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    binding.utcProgressBar.setVisibility(View.VISIBLE);
                } else {
                    binding.utcProgressBar.setVisibility(View.GONE);
                }
            }
        });

        /**
         * 原来的 timerSec 观察只作为“触发刷新”的辅助，
         * 实际显示由 progressRunnable 连续刷新。
         */
        mainViewModel.timerSec.observe(this, new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                updateUtcProgressBarSmooth();
            }
        });

        //观察模式变化，刷新进度条周期
        GeneralVariables.mutableSignalMode.observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                updateUtcProgressBarSmooth();
            }
        });

        //添加点击发射消息提示窗口点击关闭动作
        binding.transmittingLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.transmittingLayout.setVisibility(View.GONE);
            }
        });

        //用于Fragment的导航。
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;
        navController = navHostFragment.getNavController();

        NavigationUI.setupWithNavController(binding.navView, navController);
        binding.navView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                navController.navigate(item.getItemId());
                return true;
            }
        });

        binding.welcomTextView.setText(String.format(getString(R.string.version_info)
                , GeneralVariables.VERSION, GeneralVariables.BUILD_DATE));

        floatView = new FloatView(this, 32);
        if (!animatorRunned) {
            animationImage();
            animatorRunned = true;
        } else {
            binding.initDataLayout.setVisibility(View.GONE);
            InitFloatView();
        }

        //初始化数据
        InitData();

        //观察是不是flex radio
        mainViewModel.mutableIsFlexRadio.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    floatView.addButton(R.id.flex_radio, "flex_radio", R.drawable.flex_icon
                            , new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    navController.navigate(R.id.flexRadioInfoFragment);
                                }
                            });
                } else {
                    floatView.deleteButtonByName("flex_radio");
                }
            }
        });

        //观察是不是xiegu radio
        mainViewModel.mutableIsXieguRadio.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    floatView.addButton(R.id.xiegu_radio, "xiegu_radio", R.drawable.xiegulogo32
                            , new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    navController.navigate(R.id.xieguInfoFragment);
                                }
                            });
                } else {
                    floatView.deleteButtonByName("xiegu_radio");
                }
            }
        });

        //关闭串口设备列表按钮
        binding.closeSelectSerialPortImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.selectSerialPortLayout.setVisibility(View.GONE);
            }
        });

        //观察串口设备列表的变化
        mainViewModel.mutableSerialPorts.observe(this, new Observer<ArrayList<CableSerialPort.SerialPort>>() {
            @Override
            public void onChanged(ArrayList<CableSerialPort.SerialPort> serialPorts) {
                setSelectUsbDevice();
            }
        });

        //列USB设备列表
        mainViewModel.getUsbDevice();

        //设置发射消息框的动画
        binding.transmittingMessageTextView.setAnimation(AnimationUtils.loadAnimation(this
                , R.anim.view_blink));

        //观察发射的状态
        mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observe(this,
                new Observer<Boolean>() {
                    @Override
                    public void onChanged(Boolean aBoolean) {
                        if (aBoolean) {
                            binding.transmittingLayout.setVisibility(View.VISIBLE);
                        } else {
                            binding.transmittingLayout.setVisibility(View.GONE);
                        }
                        updateUtcProgressBarSmooth();
                    }
                });

        //观察发射内容的变化
        mainViewModel.ft8TransmitSignal.mutableTransmittingMessage.observe(this,
                new Observer<String>() {
                    @Override
                    public void onChanged(String s) {
                        binding.transmittingMessageTextView.setText(s);
                    }
                });

        //判断导入共享log文件的工作线程还在，如果在，就显示对话框
        if (Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue())) {
            showShareDialog();
        } else {
            //读取共享的文件
            doReceiveShareFile(getIntent());
        }

        // 启动顶部连续进度刷新
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
    }

    /**
     * 连续刷新顶部时间条
     *
     * 逻辑：
     * 1. 按当前模式决定 FT8/FT4 周期
     * 2. 使用 UtcTimer.getSystemTime()，保证跟应用内部时钟偏移保持一致
     * 3. 进度条 max 固定为 1000，progress 按比例连续变化，视觉更顺滑
     * 4. 发射高亮仍按当前模式下的时序判断
     */
    private void updateUtcProgressBarSmooth() {
        if (binding == null || mainViewModel == null) {
            return;
        }

        int slotTimeM = GeneralVariables.getCurrentSlotTimeM(); // 0.1秒单位
        int slotTimeMs = GeneralVariables.getCurrentSlotTimeMillisecond(); // 毫秒
        long nowUtc = UtcTimer.getSystemTime();

        if (slotTimeMs <= 0) {
            return;
        }

        boolean isMyTxSlot = mainViewModel.ft8TransmitSignal.sequential
                == UtcTimer.getNowSequential(slotTimeM)
                && mainViewModel.ft8TransmitSignal.isActivated();

        if (isMyTxSlot) {
            binding.utcProgressBar.setBackgroundColor(getColor(R.color.calling_list_isMyCall_color));
        } else {
            binding.utcProgressBar.setBackgroundColor(getColor(R.color.progresss_bar_back_color));
        }

        // 固定最大值，连续按比例显示
        final int progressMax = 1000;
        int progress = (int) ((nowUtc % slotTimeMs) * progressMax / (float) slotTimeMs);

        if (progress < 0) progress = 0;
        if (progress > progressMax) progress = progressMax;

        binding.utcProgressBar.setMax(progressMax);
        binding.utcProgressBar.setProgress(progress);
    }

    /**
     * 接收共享文件
     * @param intent intent
     */
    private void doReceiveShareFile(Intent intent) {
        Uri uri = (Uri) intent.getData();

        if (uri != null) {
            ImportSharedLogs importSharedLogs;
            showShareDialog();
            try {
                importSharedLogs = new ImportSharedLogs(mainViewModel);
                Log.e(TAG, "开始导入。。。");
                mainViewModel.mutableImportShareRunning.setValue(true);
                importSharedLogs.doImport(getBaseContext().getContentResolver().openInputStream(uri)
                        , new OnShareLogEvents() {
                            @Override
                            public void onPreparing(String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                            }

                            @Override
                            public void onShareStart(int count, String info) {
                                mainViewModel.mutableSharePosition.postValue(0);
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableImportShareRunning.postValue(true);
                                mainViewModel.mutableShareCount.postValue(count);
                            }

                            @Override
                            public boolean onShareProgress(int count, int position, String info) {
                                mainViewModel.mutableSharePosition.postValue(position);
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableShareCount.postValue(count);
                                return Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue());
                            }

                            @Override
                            public void afterGet(int count, String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableImportShareRunning.postValue(false);
                            }

                            @Override
                            public void onShareFailed(String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                            }
                        });
            } catch (IOException e) {
                mainViewModel.mutableImportShareRunning.postValue(false);
                Log.e(TAG, String.format("错误：%s", e.getMessage()));
                ToastMessage.show(e.getMessage());
            }
        } else {
            Log.e(TAG, "读文件类型时，文件没有找到。");
        }
    }

    /**
     * 添加浮动按钮
     */
    private void InitFloatView() {
        binding.container.addView(floatView);
        floatView.setButtonMargin(0);
        floatView.setFloatBoard(FloatView.FLOAT_BOARD.RIGHT);

        floatView.setButtonBackgroundResourceId(R.drawable.float_button_style);

        floatView.addButton(R.id.float_nav, "float_nav", R.drawable.ic_baseline_fullscreen_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        FloatViewButton button = floatView.getButtonByName("float_nav");
                        if (binding.navView.getVisibility() == View.VISIBLE) {
                            binding.navView.setVisibility(View.GONE);
                            if (button != null) {
                                button.setImageResource(R.drawable.ic_baseline_fullscreen_exit_24);
                            }
                        } else {
                            binding.navView.setVisibility(View.VISIBLE);
                            if (button != null) {
                                button.setImageResource(R.drawable.ic_baseline_fullscreen_24);
                            }
                        }
                    }
                });

        floatView.addButton(R.id.float_freq, "float_freq", R.drawable.ic_baseline_freq_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new FreqDialog(binding.container.getContext(), mainViewModel).show();
                    }
                });

        floatView.addButton(R.id.set_volume, "set_volume", R.drawable.ic_baseline_volume_up_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new SetVolumeDialog(binding.container.getContext(), mainViewModel).show();
                    }
                });

        //打开网格追踪
        floatView.addButton(R.id.grid_tracker, "grid_tracker", R.drawable.ic_baseline_grid_tracker_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent intent = new Intent(getApplicationContext(), GridTrackerMainActivity.class);
                        startActivity(intent);
                    }
                });

        floatView.initLocation();
    }

    /**
     * 初始化一些数据
     */
    private void InitData() {
        if (mainViewModel.configIsLoaded) return;

        //读取波段数据
        if (mainViewModel.operationBand == null) {
            mainViewModel.operationBand = OperationBand.getInstance(getBaseContext());
        }

        mainViewModel.databaseOpr.getQslDxccToMap();

        //获取所有的配置参数
        mainViewModel.databaseOpr.getAllConfigParameter(new OnAfterQueryConfig() {
            @Override
            public void doOnBeforeQueryConfig(String KeyName) {
            }

            @Override
            public void doOnAfterQueryConfig(String KeyName, String Value) {
                mainViewModel.configIsLoaded = true;
                String grid = MaidenheadGrid.getMyMaidenheadGrid(getApplicationContext());
                if (!grid.equals("")) {
                    GeneralVariables.setMyMaidenheadGrid(grid);
                    mainViewModel.databaseOpr.writeConfig("grid", grid, null);
                }

                mainViewModel.ft8TransmitSignal.setTimer_sec(GeneralVariables.transmitDelay);

                if (GeneralVariables.getMyMaidenheadGrid().equals("")
                        || GeneralVariables.myCallsign.equals("")) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            navController.navigate(R.id.menu_nav_config);
                        }
                    });
                }
            }
        });

        new com.bg7yoz.ft8cn.database.DatabaseOpr.GetCallsignMapGrid(mainViewModel.databaseOpr.getDb()).execute();

        mainViewModel.getFollowCallsignsFromDataBase();

        if (GeneralVariables.callsignDatabase == null) {
            GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(getBaseContext(), null, 1);
        }
    }

    /**
     * 显示生成log的对话框
     */
    private void showShareDialog() {
        dialog = new ShareLogsProgressDialog(
                binding.getRoot().getContext()
                , mainViewModel, true);

        dialog.show();
        mainViewModel.mutableSharePosition.postValue(0);
        mainViewModel.mutableShareInfo.postValue("");
        mainViewModel.mutableShareCount.postValue(0);
    }

    /**
     * 检查权限
     */
    private void checkPermission() {
        mPermissionList.clear();

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                mPermissionList.add(permission);
            }
        }

        if (!mPermissionList.isEmpty()) {
            String[] permissions = mPermissionList.toArray(new String[0]);
            ActivityCompat.requestPermissions(MainActivity.this, permissions, PERMISSION_REQUEST);
        }
    }

    /**
     * 响应授权
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * 显示串口设备列表
     */
    public void setSelectUsbDevice() {
        ArrayList<CableSerialPort.SerialPort> ports = mainViewModel.mutableSerialPorts.getValue();
        binding.selectSerialPortLinearLayout.removeAllViews();
        if (ports == null) {
            binding.selectSerialPortLayout.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < ports.size(); i++) {
            View layout = LayoutInflater.from(getApplicationContext())
                    .inflate(R.layout.select_serial_port_list_view_item, null);
            layout.setId(i);
            TextView textView = layout.findViewById(R.id.selectSerialPortListViewItemTextView);
            textView.setText(ports.get(i).information());
            binding.selectSerialPortLinearLayout.addView(layout);
            layout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mainViewModel.connectCableRig(getApplicationContext(), ports.get(view.getId()));
                    binding.selectSerialPortLayout.setVisibility(View.GONE);
                }
            });
        }

        if ((ports.size() >= 1) && (!mainViewModel.isRigConnected())) {
            binding.selectSerialPortLayout.setVisibility(View.VISIBLE);
        } else {
            binding.selectSerialPortLayout.setVisibility(View.GONE);
        }
    }

    private void animationImage() {
        ObjectAnimator navigationAnimator = ObjectAnimator.ofFloat(binding.navView, "translationY", 200);
        navigationAnimator.setDuration(3000);
        navigationAnimator.setFloatValues(200, 200, 200, 0);

        ObjectAnimator hideLogoAnimator = ObjectAnimator.ofFloat(binding.initDataLayout, "alpha", 1f, 1f, 1f, 0);
        hideLogoAnimator.setDuration(3000);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(navigationAnimator, hideLogoAnimator);
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                binding.initDataLayout.setVisibility(View.GONE);
                binding.utcProgressBar.setVisibility(View.VISIBLE);
                InitFloatView();//显示浮窗
                updateUtcProgressBarSmooth();
            }

            @Override
            public void onAnimationCancel(Animator animator) {
            }

            @Override
            public void onAnimationRepeat(Animator animator) {
            }
        });

        animatorSet.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            mainViewModel.getUsbDevice();
        } else {
            setIntent(intent);
            doReceiveShareFile(getIntent());
        }
        super.onNewIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (navController.getGraph().getStartDestination() == navController.getCurrentDestination().getId()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.exit_confirmation))
                    .setPositiveButton(getString(R.string.exit)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    if (mainViewModel.ft8TransmitSignal.isActivated()) {
                                        mainViewModel.ft8TransmitSignal.setActivated(false);
                                    }
                                    closeThisApp();
                                }
                            }).setNegativeButton(getString(R.string.cancel)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    dialogInterface.dismiss();
                                }
                            });
            builder.create().show();

        } else {
            navController.navigateUp();
        }
    }

    private void closeThisApp() {
        mainViewModel.ft8TransmitSignal.setActivated(false);
        if (mainViewModel.baseRig != null) {
            if (mainViewModel.baseRig.getConnector() != null) {
                mainViewModel.baseRig.getConnector().disconnect();
            }
        }

        mainViewModel.ft8SignalListener.stopListen();
        mainViewModel = null;
        System.exit(0);
    }

    /**
     * 注册蓝牙动作广播
     */
    private void registerBluetoothReceiver() {
        if (mReceive == null) {
            mReceive = new BluetoothStateBroadcastReceive(getApplicationContext(), mainViewModel);
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        intentFilter.addAction(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE);
        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        intentFilter.addAction(AudioManager.EXTRA_SCO_AUDIO_STATE);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothAdapter.EXTRA_CONNECTION_STATE);
        intentFilter.addAction(BluetoothAdapter.EXTRA_STATE);
        intentFilter.addAction("android.bluetooth.BluetoothAdapter.STATE_OFF");
        intentFilter.addAction("android.bluetooth.BluetoothAdapter.STATE_ON");
        registerReceiver(mReceive, intentFilter);
    }

    /**
     * 注销蓝牙动作广播
     */
    private void unregisterBluetoothReceiver() {
        if (mReceive != null) {
            unregisterReceiver(mReceive);
            mReceive = null;
        }
    }

    @Override
    protected void onDestroy() {
        progressHandler.removeCallbacks(progressRunnable);

        unregisterBluetoothReceiver();
        if (Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue())) {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }

        super.onDestroy();
    }
}