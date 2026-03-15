package com.bg7yoz.ft8cn;
/**
 * -----2022.5.6-----
 * MainViewModel类，用于解码FT8信号以及保存与解码有关的变量数据。生存于APP的整个生命周期。
 *
 * 已修改：
 * 1. 增加 NTP 同步状态 LiveData
 * 2. 增加 syncNtpTime() 方法
 * 3. 启动时按配置自动同步一次
 *
 * @author BG7YOZ
 * @date 2022.8.22
 */

import static com.bg7yoz.ft8cn.GeneralVariables.getStringFromResource;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;

import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.callsign.CallsignInfo;
import com.bg7yoz.ft8cn.callsign.OnAfterQueryCallsignLocation;
import com.bg7yoz.ft8cn.connector.BluetoothRigConnector;
import com.bg7yoz.ft8cn.connector.CableConnector;
import com.bg7yoz.ft8cn.connector.CableSerialPort;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.connector.FlexConnector;
import com.bg7yoz.ft8cn.connector.IComWifiConnector;
import com.bg7yoz.ft8cn.connector.X6100Connector;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.database.OnAfterQueryFollowCallsigns;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.flex.FlexRadio;
import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener;
import com.bg7yoz.ft8cn.ft8listener.OnFt8Listen;
import com.bg7yoz.ft8cn.ft8transmit.FT8TransmitSignal;
import com.bg7yoz.ft8cn.ft8transmit.OnDoTransmitted;
import com.bg7yoz.ft8cn.ft8transmit.OnTransmitSuccess;
import com.bg7yoz.ft8cn.html.LogHttpServer;
import com.bg7yoz.ft8cn.icom.WifiRig;
import com.bg7yoz.ft8cn.log.QSLCallsignRecord;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.log.SWLQsoList;
import com.bg7yoz.ft8cn.log.ThirdPartyService;
import com.bg7yoz.ft8cn.rigs.BaseRig;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.rigs.ElecraftRig;
import com.bg7yoz.ft8cn.rigs.Flex6000Rig;
import com.bg7yoz.ft8cn.rigs.FlexNetworkRig;
import com.bg7yoz.ft8cn.rigs.GuoHeQ900Rig;
import com.bg7yoz.ft8cn.rigs.IcomRig;
import com.bg7yoz.ft8cn.rigs.InstructionSet;
import com.bg7yoz.ft8cn.rigs.KenwoodKT90Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS2000Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS570Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS590Rig;
import com.bg7yoz.ft8cn.rigs.OnRigStateChanged;
import com.bg7yoz.ft8cn.rigs.TrUSDXRig;
import com.bg7yoz.ft8cn.rigs.Wolf_sdr_450Rig;
import com.bg7yoz.ft8cn.rigs.XieGu6100NetRig;
import com.bg7yoz.ft8cn.rigs.XieGu6100Rig;
import com.bg7yoz.ft8cn.rigs.XieGuRig;
import com.bg7yoz.ft8cn.rigs.Yaesu2Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu2_847Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu38Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu38_450Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu39Rig;
import com.bg7yoz.ft8cn.rigs.YaesuDX10Rig;
import com.bg7yoz.ft8cn.spectrum.SpectrumListener;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.ToastMessage;
import com.bg7yoz.ft8cn.wave.HamRecorder;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.x6100.X6100Radio;
import com.bg7yoz.ft8cn.pskreporter.PSKReporterManager;
import com.bg7yoz.ft8cn.pskreporter.PSKReporterSender;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainViewModel extends ViewModel {
    String TAG = "ft8cn MainViewModel";
    public boolean configIsLoaded = false;

    private static MainViewModel viewModel = null;

    public final ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
    public UtcTimer utcTimer;

    public DatabaseOpr databaseOpr;

    public MutableLiveData<Integer> mutable_Decoded_Counter = new MutableLiveData<>();
    public int currentDecodeCount = 0;
    public MutableLiveData<ArrayList<Ft8Message>> mutableFt8MessageList = new MutableLiveData<>();
    public MutableLiveData<Long> timerSec = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsRecording = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableHamRecordIsRunning = new MutableLiveData<>();
    public MutableLiveData<Float> mutableTimerOffset = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsDecoding = new MutableLiveData<>();
    public ArrayList<Ft8Message> currentMessages = null;

    public MutableLiveData<Boolean> mutableIsFlexRadio = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsXieguRadio = new MutableLiveData<>();

    /**
     * NTP 同步状态
     */
    public MutableLiveData<Integer> mutableNtpOffsetMs = new MutableLiveData<>(0);
    public MutableLiveData<Integer> mutableNtpAlignedOffsetMs = new MutableLiveData<>(0);
    public MutableLiveData<Long> mutableNtpDelayMs = new MutableLiveData<>(-1L);
    public MutableLiveData<Long> mutableNtpSyncTime = new MutableLiveData<>(0L);
    public MutableLiveData<String> mutableNtpServer = new MutableLiveData<>("");
    public MutableLiveData<String> mutableNtpSyncInfo = new MutableLiveData<>("");
    public MutableLiveData<Boolean> mutableNtpSyncSuccess = new MutableLiveData<>(false);

    private final ExecutorService getQTHThreadPool = Executors.newCachedThreadPool();
    private final ExecutorService sendWaveDataThreadPool = Executors.newCachedThreadPool();
    private final GetQTHRunnable getQTHRunnable = new GetQTHRunnable(this);
    private final SendWaveDataRunnable sendWaveDataRunnable = new SendWaveDataRunnable();

    //用于显示生成共享日志过程的变量
    public MutableLiveData<String> mutableShareInfo = new MutableLiveData<>("");
    public MutableLiveData<Integer> mutableSharePosition = new MutableLiveData<>(0);
    public MutableLiveData<Boolean> mutableShareRunning = new MutableLiveData<>(false);
    public MutableLiveData<Integer> mutableShareCount = new MutableLiveData<>(0);
    public MutableLiveData<Boolean> mutableImportShareRunning = new MutableLiveData<>(false);

    public HamRecorder hamRecorder;
    public FT8SignalListener ft8SignalListener;
    public FT8TransmitSignal ft8TransmitSignal;
    public SpectrumListener spectrumListener;
    public PSKReporterManager pskReporterManager;
    public PSKReporterSender pskReporterSender;
    public boolean markMessage = true;

    public OperationBand operationBand = null;

    private SWLQsoList swlQsoList = new SWLQsoList();

    public MutableLiveData<ArrayList<CableSerialPort.SerialPort>> mutableSerialPorts = new MutableLiveData<>();
    private ArrayList<CableSerialPort.SerialPort> serialPorts;
    public BaseRig baseRig;
    private final OnRigStateChanged onRigStateChanged = new OnRigStateChanged() {
        @Override
        public void onDisconnected() {
            ToastMessage.show(getStringFromResource(R.string.disconnect_rig));
        }

        @Override
        public void onConnected() {
            ToastMessage.show(getStringFromResource(R.string.connected_rig));
        }

        @Override
        public void onPttChanged(boolean isOn) {
        }

        @Override
        public void onFreqChanged(long freq) {
            ToastMessage.show(String.format(getStringFromResource(R.string.current_frequency)
                    , BaseRigOperation.getFrequencyAllInfo(freq)));
            GeneralVariables.band = freq;
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
            databaseOpr.getAllQSLCallsigns();
        }

        @Override
        public void onRunError(String message) {
            ToastMessage.show(String.format(getStringFromResource(R.string.radio_communication_error)
                    , message));
        }
    };

    public MutableLiveData<Integer> mutableTransmitMessagesCount = new MutableLiveData<>();

    public boolean deNoise = false;

    //*********日志查询需要的变量********************
    public boolean logListShowCallsign = false;
    public String queryKey = "";
    public int queryFilter = 0;
    public MutableLiveData<Integer> mutableQueryFilter = new MutableLiveData<>();
    public ArrayList<QSLCallsignRecord> callsignRecords = new ArrayList<>();
    //********************************************

    //日志管理HTTP SERVER
    private final LogHttpServer httpServer;

    public static MainViewModel getInstance(ViewModelStoreOwner owner) {
        if (viewModel == null) {
            viewModel = new ViewModelProvider(owner).get(MainViewModel.class);
        }
        return viewModel;
    }

    public Ft8Message getFt8Message(int position) {
        return Objects.requireNonNull(ft8Messages.get(position));
    }

    public MainViewModel() {
        databaseOpr = DatabaseOpr.getInstance(GeneralVariables.getMainContext(), "data.db");

        // PSKReporter：仅初始化后台收集/发送链路，不参与解码主线程。
        pskReporterManager = new PSKReporterManager(databaseOpr);
        pskReporterSender = new PSKReporterSender(pskReporterManager);
        pskReporterSender.start();

        mutableIsDecoding.postValue(false);

        hamRecorder = new HamRecorder(null);
        hamRecorder.startRecord();

        mutableIsFlexRadio.setValue(false);
        mutableIsXieguRadio.setValue(false);

        mutableNtpServer.postValue(GeneralVariables.getCurrentNtpServer());
        mutableNtpOffsetMs.postValue(GeneralVariables.lastNtpOffset);
        mutableNtpAlignedOffsetMs.postValue(GeneralVariables.lastNtpAlignedOffset);
        mutableNtpDelayMs.postValue(GeneralVariables.lastNtpDelay);
        mutableNtpSyncTime.postValue(GeneralVariables.lastNtpSyncTime);

        GeneralVariables.mutableNtpConfigChanged.observeForever(new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                mutableNtpServer.postValue(GeneralVariables.getCurrentNtpServer());
            }
        });

        //创建用于显示时间的计时器
        utcTimer = new UtcTimer(10, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {
            }

            @Override
            public void doOnSecTimer(long utc) {
                timerSec.postValue(utc);
                mutableIsRecording.postValue(hamRecorder.isRunning());
                mutableHamRecordIsRunning.postValue(hamRecorder.isRunning());
            }
        });
        utcTimer.start();

        // 启动时按当前配置同步一次
        if (GeneralVariables.ntpEnable) {
            syncNtpTime();
        }

        mutableFt8MessageList.setValue(ft8Messages);

        ft8SignalListener = new FT8SignalListener(databaseOpr, new OnFt8Listen() {
            @Override
            public void beforeListen(long utc) {
                mutableIsDecoding.postValue(true);
            }

            @Override
            public void afterDecode(long utc, float time_sec, int sequential
                    , ArrayList<Ft8Message> messages, boolean isDeep) {

                if (messages == null || messages.size() == 0) {
                    mutableIsDecoding.postValue(false);
                    currentMessages = messages;
                    if (!isDeep) {
                        currentDecodeCount = 0;
                        mutable_Decoded_Counter.postValue(currentDecodeCount);
                    }
                    return;
                }

                synchronized (ft8Messages) {
                    ft8Messages.addAll(messages);
                }
                GeneralVariables.deleteArrayListMore(ft8Messages);

                mutableFt8MessageList.postValue(ft8Messages);
                mutableTimerOffset.postValue(time_sec);

                findIncludedCallsigns(messages);

                if (ft8TransmitSignal != null && !ft8TransmitSignal.isTransmitting()) {
                    ft8TransmitSignal.parseMessageToFunction(messages);
                }

                currentMessages = messages;

                if (isDeep) {
                    currentDecodeCount += messages.size();
                } else {
                    currentDecodeCount = messages.size();
                }

                mutableIsDecoding.postValue(false);

                getQTHRunnable.messages = messages;
                getQTHThreadPool.execute(getQTHRunnable);

                mutable_Decoded_Counter.postValue(currentDecodeCount);

                if (GeneralVariables.saveSWLMessage) {
                    databaseOpr.writeMessage(messages);
                }

                if (GeneralVariables.saveSWL_QSO) {
                    swlQsoList.findSwlQso(messages, ft8Messages, new SWLQsoList.OnFoundSwlQso() {
                        @Override
                        public void doFound(QSLRecord record) {
                            databaseOpr.addSWL_QSO(record);
                            ToastMessage.show(record.swlQSOInfo());
                        }
                    });
                }

                getCallsignAndGrid(messages);

                if (pskReporterManager != null) {
                    pskReporterManager.collectAndQueue(messages);
                    Log.d(TAG, "PSKReporter queue size=" + pskReporterManager.getQueueSize());
                } //PSK
            }
        });

        ft8SignalListener.setOnWaveDataListener(new FT8SignalListener.OnWaveDataListener() {
            @Override
            public void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone) {
                hamRecorder.getVoiceData(duration, afterDoneRemove, getVoiceDataDone);
            }
        });

        ft8SignalListener.startListen();

        spectrumListener = new SpectrumListener(hamRecorder);

        ft8TransmitSignal = new FT8TransmitSignal(databaseOpr, new OnDoTransmitted() {
            private boolean needControlSco() {
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                    return false;
                }
                if (GeneralVariables.controlMode != ControlMode.CAT) {
                    return true;
                }
                return baseRig != null && !baseRig.supportWaveOverCAT();
            }

            @Override
            public void onBeforeTransmit(Ft8Message message, int functionOder) {
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        if (needControlSco()) stopSco();
                        baseRig.setPTT(true);
                    }
                }
                if (ft8TransmitSignal.isActivated()) {
                    GeneralVariables.transmitMessages.add(message);
                    mutableTransmitMessagesCount.postValue(1);
                }
            }

            @Override
            public void onAfterTransmit(Ft8Message message, int functionOder) {
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        baseRig.setPTT(false);
                        if (needControlSco()) startSco();
                    }
                }
            }

            @Override
            public void onTransmitByWifi(Ft8Message msg) {
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                    if (baseRig != null) {
                        if (baseRig.isConnected()) {
                            sendWaveDataRunnable.baseRig = baseRig;
                            sendWaveDataRunnable.message = msg;
                            sendWaveDataThreadPool.execute(sendWaveDataRunnable);
                        }
                    }
                }
            }

            @Override
            public boolean supportTransmitOverCAT() {
                if (GeneralVariables.controlMode != ControlMode.CAT) {
                    return false;
                }
                if (baseRig == null) {
                    return false;
                }
                return baseRig.isConnected() && baseRig.supportWaveOverCAT();
            }

            @Override
            public void onTransmitOverCAT(Ft8Message msg) {
                if (!supportTransmitOverCAT()) {
                    return;
                }
                sendWaveDataRunnable.baseRig = baseRig;
                sendWaveDataRunnable.message = msg;
                sendWaveDataThreadPool.execute(sendWaveDataRunnable);
            }

        }, new OnTransmitSuccess() {
            @Override
            public void doAfterTransmit(QSLRecord qslRecord) {
                databaseOpr.addQSL_Callsign(qslRecord);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (GeneralVariables.enableCloudlog) {
                            ThirdPartyService.UploadToCloudLog(qslRecord);
                        }
                        if (GeneralVariables.enableQRZ) {
                            ThirdPartyService.UploadToQRZ(qslRecord);
                        }
                    }
                }).start();

                if (qslRecord.getToCallsign() != null) {
                    GeneralVariables.callsignDatabase.getCallsignInformation(qslRecord.getToCallsign()
                            , new OnAfterQueryCallsignLocation() {
                                @Override
                                public void doOnAfterQueryCallsignLocation(CallsignInfo callsignInfo) {
                                    GeneralVariables.addDxcc(callsignInfo.DXCC);
                                    GeneralVariables.addItuZone(callsignInfo.ITUZone);
                                    GeneralVariables.addCqZone(callsignInfo.CQZone);
                                }
                            });
                }
            }
        });

        httpServer = new LogHttpServer(this, LogHttpServer.DEFAULT_PORT);
        try {
            httpServer.start();
        } catch (IOException e) {
            Log.e(TAG, "http server error:" + e.getMessage());
        }
    }

    /**
     * 立即进行一次 NTP 同步，使用当前配置的服务器
     */
    public void syncNtpTime() {
        if (!GeneralVariables.ntpEnable) {
            mutableNtpSyncSuccess.postValue(false);
            mutableNtpSyncInfo.postValue("NTP 已关闭");
            return;
        }
        syncNtpTime(GeneralVariables.getCurrentNtpServer());
    }

    /**
     * 指定服务器进行一次 NTP 同步
     */
    public void syncNtpTime(String server) {
        if (!GeneralVariables.ntpEnable) {
            mutableNtpSyncSuccess.postValue(false);
            mutableNtpSyncInfo.postValue("NTP 已关闭");
            return;
        }

        final String targetServer = (server == null || server.trim().length() == 0)
                ? GeneralVariables.getCurrentNtpServer()
                : server.trim();

        if (targetServer.length() == 0) {
            mutableNtpSyncSuccess.postValue(false);
            mutableNtpSyncInfo.postValue("NTP同步失败: server empty");
            return;
        }

        mutableNtpServer.postValue(targetServer);
        mutableNtpSyncSuccess.postValue(false);
        mutableNtpSyncInfo.postValue("正在同步 " + targetServer + " ...");

        UtcTimer.syncTime(targetServer, new UtcTimer.AfterSyncTimeDetail() {
            @Override
            public void doAfterSyncTimer(UtcTimer.NtpSyncResult result) {
                GeneralVariables.updateNtpSyncResult(
                        result.server,
                        result.realOffsetMs,
                        result.alignedOffsetMs,
                        result.roundTripDelayMs,
                        result.syncTimeMs
                );

                mutableNtpOffsetMs.postValue(result.realOffsetMs);
                mutableNtpAlignedOffsetMs.postValue(result.alignedOffsetMs);
                mutableNtpDelayMs.postValue(result.roundTripDelayMs);
                mutableNtpSyncTime.postValue(result.syncTimeMs);
                mutableNtpServer.postValue(result.server);
                mutableNtpSyncSuccess.postValue(true);

                String info = "NTP同步成功  server=" + result.server
                        + "  offset=" + result.realOffsetMs + "ms"
                        + "  aligned=" + result.alignedOffsetMs + "ms"
                        + "  delay=" + result.roundTripDelayMs + "ms";
                mutableNtpSyncInfo.postValue(info);
                GeneralVariables.mutableDebugMessage.postValue(info);
            }

            @Override
            public void syncFailed(IOException e) {
                mutableNtpSyncSuccess.postValue(false);
                String info = "NTP同步失败: " + e.getMessage();
                mutableNtpSyncInfo.postValue(info);
                GeneralVariables.mutableDebugMessage.postValue(info);
            }
        });
    }

    public void setTransmitIsFreeText(boolean isFreeText) {
        if (ft8TransmitSignal != null) {
            ft8TransmitSignal.setTransmitFreeText(isFreeText);
        }
    }

    public boolean getTransitIsFreeText() {
        if (ft8TransmitSignal != null) {
            return ft8TransmitSignal.isTransmitFreeText();
        }
        return false;
    }

    /**
     * 查找符合条件的消息，放到呼叫列表中
     *
     * @param messages 消息
     */
    private synchronized void findIncludedCallsigns(ArrayList<Ft8Message> messages) {
        Log.d(TAG, "findIncludedCallsigns: 查找关注的呼号");

        if (ft8TransmitSignal != null
                && ft8TransmitSignal.isActivated()
                && ft8TransmitSignal.sequential != UtcTimer.getNowSequential(GeneralVariables.getCurrentSlotTimeM())) {
            return;
        }

        int count = 0;
        for (Ft8Message msg : messages) {
            String rawFrom = msg.getCallsignFrom();
            String rawTo = msg.getCallsignTo();
            String autoFrom = msg.getAutoReplyCallsignFrom();
            String autoTo = msg.getAutoReplyCallsignTo();

            if (GeneralVariables.checkIsMyCallsign(rawFrom)
                    || GeneralVariables.checkIsMyCallsign(rawTo)
                    || GeneralVariables.checkIsMyCallsign(autoFrom)
                    || GeneralVariables.checkIsMyCallsign(autoTo)
                    || GeneralVariables.callsignInFollow(rawFrom)
                    || GeneralVariables.callsignInFollow(rawTo)
                    || GeneralVariables.callsignInFollow(autoFrom)
                    || GeneralVariables.callsignInFollow(autoTo)
                    || (GeneralVariables.autoFollowCQ && msg.checkIsCQ())) {
                String qslCallsign = autoFrom.length() > 0 ? autoFrom : rawFrom;
                msg.isQSL_Callsign = GeneralVariables.checkQSLCallsign(qslCallsign);
                String excludeCallsign = autoFrom.length() > 0 ? autoFrom : rawFrom;
                if (!GeneralVariables.checkIsExcludeCallsign(excludeCallsign)) {
                    count++;
                    GeneralVariables.transmitMessages.add(msg);
                }
            }
        }
        GeneralVariables.deleteArrayListMore(GeneralVariables.transmitMessages);
        mutableTransmitMessagesCount.postValue(count);
    }

    /**
     * 清除传输消息列表
     */
    public void clearTransmittingMessage() {
        GeneralVariables.transmitMessages.clear();
        mutableTransmitMessagesCount.postValue(0);
    }

    /**
     * 从消息列表中查找呼号和网格的对应关系
     *
     * @param messages 消息列表
     */
    private void getCallsignAndGrid(ArrayList<Ft8Message> messages) {
        for (Ft8Message msg : messages) {
            if (GeneralVariables.checkFun1(msg.extraInfo)) {
                if (!GeneralVariables.getCallsignHasGrid(msg.getCallsignFrom(), msg.maidenGrid)) {
                    databaseOpr.addCallsignQTH(msg.getCallsignFrom(), msg.maidenGrid);
                }
                GeneralVariables.addCallsignAndGrid(msg.getCallsignFrom(), msg.maidenGrid);
            }
        }
    }

    /**
     * 清除消息列表
     */
    public void clearFt8MessageList() {
        ft8Messages.clear();
        mutable_Decoded_Counter.postValue(ft8Messages.size());
        mutableFt8MessageList.postValue(ft8Messages);
    }

    /**
     * 删除单个文件
     *
     * @param fileName 要删除的文件的文件名
     */
    public static void deleteFile(String fileName) {
        File file = new File(fileName);
        if (file.exists() && file.isFile()) {
            file.delete();
        }
    }

    /**
     * 向关注的呼号列表添加呼号
     *
     * @param callsign 呼号
     */
    public void addFollowCallsign(String callsign) {
        if (!GeneralVariables.followCallsign.contains(callsign)) {
            GeneralVariables.followCallsign.add(callsign);
            databaseOpr.addFollowCallsign(callsign);
        }
    }

    /**
     * 从数据库中获取关注的呼号列表
     */
    public void getFollowCallsignsFromDataBase() {
        databaseOpr.getFollowCallsigns(new OnAfterQueryFollowCallsigns() {
            @Override
            public void doOnAfterQueryFollowCallsigns(ArrayList<String> callsigns) {
                for (String s : callsigns) {
                    if (!GeneralVariables.followCallsign.contains(s)) {
                        GeneralVariables.followCallsign.add(s);
                    }
                }
            }
        });
    }

    /**
     * 设置操作载波频率。如果电台没有连接，就有操作
     */
    public void setOperationBand() {
        if (!isRigConnected()) {
            return;
        }

        baseRig.setUsbModeToRig();

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                baseRig.setFreq(GeneralVariables.band);
                baseRig.setFreqToRig();
            }
        }, 800);
    }

    public void setCivAddress() {
        if (baseRig != null) {
            baseRig.setCivAddress(GeneralVariables.civAddress);
        }
    }

    public void setControlMode() {
        if (baseRig != null) {
            baseRig.setControlMode(GeneralVariables.controlMode);
        }
    }

    /**
     * 通过USB连接电台
     *
     * @param context context
     * @param port    串口
     */
    public void connectCableRig(Context context, CableSerialPort.SerialPort port) {
        if (GeneralVariables.controlMode == ControlMode.VOX) {
            GeneralVariables.controlMode = ControlMode.CAT;
        }
        connectRig();

        if (baseRig == null) {
            return;
        }
        baseRig.setControlMode(GeneralVariables.controlMode);
        CableConnector connector = new CableConnector(context, port, GeneralVariables.baudRate
                , GeneralVariables.controlMode, baseRig);

        connector.setOnCableDataReceived(new CableConnector.OnCableDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                Log.i(TAG, "call hamRecorder.doOnWaveDataReceived");
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);
        connector.connect();

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                setOperationBand();
            }
        }, 1000);
    }

    public void connectBluetoothRig(Context context, BluetoothDevice device) {
        GeneralVariables.controlMode = ControlMode.CAT;
        connectRig();
        if (baseRig == null) {
            return;
        }
        baseRig.setControlMode(GeneralVariables.controlMode);
        BluetoothRigConnector connector = BluetoothRigConnector.getInstance(context, device.getAddress()
                , GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                setOperationBand();
            }
        }, 5000);
    }

    /**
     * 以网络方式连接到ICOM、协谷X6100系列电台
     *
     * @param wifiRig ICom,XieGu Wifi模式的电台
     */
    public void connectWifiRig(WifiRig wifiRig) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }

        GeneralVariables.controlMode = ControlMode.CAT;
        IComWifiConnector iComWifiConnector = new IComWifiConnector(GeneralVariables.controlMode, wifiRig);
        iComWifiConnector.setOnWifiDataReceived(new IComWifiConnector.OnWifiDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }

            @Override
            public void OnCivReceived(byte[] data) {
            }
        });

        iComWifiConnector.connect();
        connectRig();

        baseRig.setControlMode(GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(iComWifiConnector);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                setOperationBand();
            }
        }, 1000);
    }

    /**
     * 连接到flexRadio
     *
     * @param context   context
     * @param flexRadio flexRadio对象
     */
    public void connectFlexRadioRig(Context context, FlexRadio flexRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }
        GeneralVariables.controlMode = ControlMode.CAT;
        FlexConnector flexConnector = new FlexConnector(context, flexRadio, GeneralVariables.controlMode);
        flexConnector.setOnWaveDataReceived(new FlexConnector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        flexConnector.connect();
        connectRig();

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(flexConnector);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                setOperationBand();
            }
        }, 3000);
    }

    /**
     * 连接到协谷Radio
     *
     * @param context    context
     * @param xieguRadio X6100Radio对象
     */
    public void connectXieguRadioRig(Context context, X6100Radio xieguRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }
        GeneralVariables.controlMode = ControlMode.CAT;
        X6100Connector xieguConnector = new X6100Connector(context, xieguRadio, GeneralVariables.controlMode);
        xieguConnector.setOnWaveDataReceived(new X6100Connector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });

        xieguConnector.connect();
        connectRig();
        xieguConnector.setBaseRig(baseRig);

        xieguRadio.setOnReceiveDataListener(new X6100Radio.OnReceiveDataListener() {
            @Override
            public void onDataReceive(byte[] data) {
                baseRig.onReceiveData(data);
            }
        });

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(xieguConnector);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                setOperationBand();
            }
        }, 3000);
    }

    /**
     * 根据指令集创建不同型号的电台
     */
    private void connectRig() {
        baseRig = null;
        switch (GeneralVariables.instructionSet) {
            case InstructionSet.ICOM:
                baseRig = new IcomRig(GeneralVariables.civAddress, true);
                break;
            case InstructionSet.ICOM_756:
                baseRig = new IcomRig(GeneralVariables.civAddress, false);
                break;
            case InstructionSet.YAESU_2:
                baseRig = new Yaesu2Rig();
                break;
            case InstructionSet.YAESU_847:
                baseRig = new Yaesu2_847Rig();
                break;
            case InstructionSet.YAESU_3_9:
                baseRig = new Yaesu39Rig(false);
                break;
            case InstructionSet.YAESU_3_9_U_DIG:
                baseRig = new Yaesu39Rig(true);
                break;
            case InstructionSet.YAESU_3_8:
                baseRig = new Yaesu38Rig();
                break;
            case InstructionSet.YAESU_3_450:
                baseRig = new Yaesu38_450Rig();
                break;
            case InstructionSet.KENWOOD_TK90:
                baseRig = new KenwoodKT90Rig();
                break;
            case InstructionSet.YAESU_DX10:
                baseRig = new YaesuDX10Rig();
                break;
            case InstructionSet.KENWOOD_TS590:
                baseRig = new KenwoodTS590Rig();
                break;
            case InstructionSet.GUOHE_Q900:
                baseRig = new GuoHeQ900Rig();
                break;
            case InstructionSet.XIEGUG90S:
                baseRig = new XieGuRig(GeneralVariables.civAddress);
                break;
            case InstructionSet.ELECRAFT:
                baseRig = new ElecraftRig();
                break;
            case InstructionSet.FLEX_CABLE:
                baseRig = new Flex6000Rig();
                break;
            case InstructionSet.FLEX_NETWORK:
                baseRig = new FlexNetworkRig();
                break;
            case InstructionSet.XIEGU_6100_FT8CNS:
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                    baseRig = new XieGu6100NetRig(GeneralVariables.civAddress);
                } else {
                    baseRig = new XieGu6100Rig(GeneralVariables.civAddress);
                }
                break;
            case InstructionSet.XIEGU_6100:
                baseRig = new XieGu6100Rig(GeneralVariables.civAddress);
                break;
            case InstructionSet.KENWOOD_TS2000:
                baseRig = new KenwoodTS2000Rig();
                break;
            case InstructionSet.WOLF_SDR_DIGU:
                baseRig = new Wolf_sdr_450Rig(false);
                break;
            case InstructionSet.WOLF_SDR_USB:
                baseRig = new Wolf_sdr_450Rig(true);
                break;
            case InstructionSet.TRUSDX:
                baseRig = new TrUSDXRig();
                break;
            case InstructionSet.KENWOOD_TS570:
                baseRig = new KenwoodTS570Rig();
                break;
        }

        if ((GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK)
                || ((GeneralVariables.instructionSet == InstructionSet.ICOM
                || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100
                || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS)
                && GeneralVariables.connectMode == ConnectMode.NETWORK)) {
            hamRecorder.setDataFromLan();
        } else {
            if (GeneralVariables.controlMode != ControlMode.CAT || baseRig == null
                    || !baseRig.supportWaveOverCAT()) {
                hamRecorder.setDataFromMic();
            } else {
                hamRecorder.setDataFromLan();
            }
        }

        mutableIsFlexRadio.postValue(GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK);
        mutableIsXieguRadio.postValue(GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS);
    }

    /**
     * 检察电台是否处于连接状态
     *
     * @return 是否连接
     */
    public boolean isRigConnected() {
        if (baseRig == null) {
            return false;
        } else {
            return baseRig.isConnected();
        }
    }

    /**
     * 获取串口设备列表
     */
    public void getUsbDevice() {
        serialPorts = CableSerialPort.listSerialPorts(GeneralVariables.getMainContext());
        mutableSerialPorts.postValue(serialPorts);
    }

    public void startSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        audioManager.setBluetoothScoOn(true);
        audioManager.startBluetoothSco();
        audioManager.setSpeakerphoneOn(false);
    }

    public void stopSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);
        }
    }

    public void setBlueToothOn() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
        }

        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setBluetoothScoOn(true);
        audioManager.stopBluetoothSco();
        audioManager.startBluetoothSco();
        audioManager.setSpeakerphoneOn(false);

        ToastMessage.show(getStringFromResource(R.string.bluetooth_headset_mode));
    }

    public void setBlueToothOff() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);
        }
        ToastMessage.show(getStringFromResource(R.string.bluetooth_Headset_mode_cancelled));
    }

    /**
     * 查询蓝牙是否连接
     *
     * @return 是否
     */
    @SuppressLint("MissingPermission")
    public boolean isBTConnected() {
        BluetoothAdapter blueAdapter = BluetoothAdapter.getDefaultAdapter();
        if (blueAdapter == null) return false;

        int headset = blueAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
        int a2dp = blueAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
        return headset == BluetoothAdapter.STATE_CONNECTED || a2dp == BluetoothAdapter.STATE_CONNECTED;
    }

    private static class GetQTHRunnable implements Runnable {
        MainViewModel mainViewModel;
        ArrayList<Ft8Message> messages;

        public GetQTHRunnable(MainViewModel mainViewModel) {
            this.mainViewModel = mainViewModel;
        }

        @Override
        public void run() {
            CallsignDatabase.getMessagesLocation(
                    GeneralVariables.callsignDatabase.getDb(), messages);
            mainViewModel.mutableFt8MessageList.postValue(mainViewModel.ft8Messages);
        }
    }

    private static class SendWaveDataRunnable implements Runnable {
        BaseRig baseRig;
        Ft8Message message;

        @Override
        public void run() {
            if (baseRig != null && message != null) {
                baseRig.sendWaveData(message);
            }
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (pskReporterSender != null) {
            pskReporterSender.stop();
        }
    }
}
