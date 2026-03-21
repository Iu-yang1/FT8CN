package com.bg7yoz.ft8cn.ft8transmit;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 发射控制与自动通联流程。
 */
public class FT8TransmitSignal {
    private static final String TAG = "FT8TransmitSignal";

    private boolean transmitFreeText = false;
    private String freeText = "FREE TEXT";

    private final DatabaseOpr databaseOpr;
    private TransmitCallsign toCallsign;
    public MutableLiveData<TransmitCallsign> mutableToCallsign = new MutableLiveData<>();

    private int functionOrder = 6;
    public MutableLiveData<Integer> mutableFunctionOrder = new MutableLiveData<>();
    private boolean activated = false;
    public MutableLiveData<Boolean> mutableIsActivated = new MutableLiveData<>();
    public int sequential;
    public MutableLiveData<Integer> mutableSequential = new MutableLiveData<>();
    private boolean isTransmitting = false;
    private final Object transmitStateLock = new Object();
    private int lastTransmittedFunctionOrder = -1;
    private Ft8Message lastTransmittedMessage = null;
    public MutableLiveData<Boolean> mutableIsTransmitting = new MutableLiveData<>();
    public MutableLiveData<String> mutableTransmittingMessage = new MutableLiveData<>();
    private long messageStartTime = 0;
    private long messageEndTime = 0;
    private String toMaidenheadGrid = "";
    private int sendReport = 0;
    private int sentTargetReport = -100;
    private int receivedReport = 0;
    private int receiveTargetReport = -100;
    private final OnTransmitSuccess onTransmitSuccess;
    private AudioAttributes attributes = null;
    private AudioFormat myFormat = null;
    private AudioTrack audioTrack = null;
    public UtcTimer utcTimer;
    public ArrayList<FunctionOfTransmit> functionList = new ArrayList<>();
    public MutableLiveData<ArrayList<FunctionOfTransmit>> mutableFunctions = new MutableLiveData<>();
    private final OnDoTransmitted onDoTransmitted;
    private final ExecutorService doTransmitThreadPool = Executors.newCachedThreadPool();
    private final Observer<Float> volumePercentObserver = new Observer<Float>() {
        @Override
        public void onChanged(Float aFloat) {
            if (audioTrack != null) {
                audioTrack.setVolume(aFloat);
            }
        }
    };

    public FT8TransmitSignal(DatabaseOpr databaseOpr
            , OnDoTransmitted doTransmitted, OnTransmitSuccess onTransmitSuccess) {
        this.onDoTransmitted = doTransmitted;
        this.onTransmitSuccess = onTransmitSuccess;
        this.databaseOpr = databaseOpr;

        setTransmitting(false);
        setActivated(false);


        GeneralVariables.mutableVolumePercent.observeForever(volumePercentObserver);

        buildUtcTimer();
        utcTimer.start();
    }

    private void buildUtcTimer() {
        utcTimer = new UtcTimer(FT8Common.getSlotTimeM(GeneralVariables.getSignalMode()), false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {
            }

            @Override
            public void doOnSecTimer(long utc) {

                if (GeneralVariables.isLaunchSupervisionTimeout()) {
                    setActivated(false);
                    return;
                }
                if (UtcTimer.getNowSequential(GeneralVariables.getCurrentSlotTimeM()) == sequential && activated) {
                    if (GeneralVariables.myCallsign.length() < 3) {
                        ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
                        return;
                    }
                    doTransmit();
                }
            }
        });
    }

    public void restartByCurrentMode() {
        boolean running = utcTimer != null && utcTimer.isRunning();
        if (utcTimer != null) {
            utcTimer.stop();
        }
        buildUtcTimer();
        if (running) {
            utcTimer.start();
        }
        mutableFunctions.postValue(functionList);
    }

    public void release() {
        GeneralVariables.mutableVolumePercent.removeObserver(volumePercentObserver);
        if (utcTimer != null) {
            utcTimer.stop();
        }
        setActivated(false);
        doTransmitThreadPool.shutdownNow();
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
    }

    public void transmitNow() {
        if (GeneralVariables.myCallsign.length() < 3) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
            return;
        }
        if (toCallsign == null) {
            return;
        }

        ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.adjust_call_target)
                , toCallsign.callsign));


        resetTargetReport();

        if (UtcTimer.getNowSequential(GeneralVariables.getCurrentSlotTimeM()) == sequential) {
            if ((UtcTimer.getSystemTime() % FT8Common.getSlotTimeMillisecond(GeneralVariables.getSignalMode()))
                    < FT8Common.getImmediateTxWindowMs(GeneralVariables.getSignalMode())) {
                setTransmitting(false);
                doTransmit();
            }
        }
    }


    public void doTransmit() {
        if (!activated) {
            return;
        }
        synchronized (transmitStateLock) {
            if (isTransmitting) {
                Log.w(TAG, "doTransmit ignored: transmit already in progress");
                return;
            }
        }
        if (!transmitFreeText && functionOrder != 6 && toCallsign == null) {
            Log.w(TAG, "doTransmit ignored: target callsign is null");
            return;
        }

        if (BaseRigOperation.checkIsWSPR2(
                GeneralVariables.band + Math.round(GeneralVariables.getBaseFrequency()))) {
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.use_wspr2_error)
                    , BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)));
            setActivated(false);
            return;
        }
        Log.d(TAG, "doTransmit: start transmit");
        doTransmitThreadPool.execute(new DoTransmitRunnable(this));
        mutableFunctions.postValue(functionList);
    }

    @SuppressLint("DefaultLocale")
    public void setTransmit(TransmitCallsign transmitCallsign
            , int functionOrder, String toMaidenheadGrid) {

        messageStartTime = 0;
        lastTransmittedFunctionOrder = -1;

        Log.d(TAG, "setTransmit: preparing transmit data");
        if (GeneralVariables.checkFun1(toMaidenheadGrid)) {
            this.toMaidenheadGrid = toMaidenheadGrid;
        } else {
            this.toMaidenheadGrid = "";
        }
        mutableToCallsign.postValue(transmitCallsign);
        toCallsign = transmitCallsign;

        if (functionOrder == -1) {//说明是回复消息
            this.functionOrder = normalizeFunctionOrder(
                    GeneralVariables.checkFunOrderByExtraInfo(toMaidenheadGrid) + 1);
            if (this.functionOrder == 6) {
                this.functionOrder = 1;
            }
        } else {
            this.functionOrder = normalizeFunctionOrder(functionOrder);
        }

        if (transmitCallsign.frequency == 0) {
            transmitCallsign.frequency = GeneralVariables.getBaseFrequency();
        }
        if (GeneralVariables.synFrequency) {
            setBaseFrequency(transmitCallsign.frequency);
        }

        sequential = (toCallsign.sequential + 1) % 2;
        mutableSequential.postValue(sequential);
        generateFun();
        mutableFunctionOrder.postValue(this.functionOrder);
    }

    private int normalizeFunctionOrder(int order) {
        if (order < 1 || order > 6) {
            return 2;
        }
        return order;
    }

    private int nextOrderFromIncoming(Ft8Message message) {
        int order = message.checkIsCQ()
                ? 6
                : GeneralVariables.checkFunOrderByExtraInfo(message.getAutoReplyExtraInfo());
        if (order < 1 || order > 5) {
            return 2;
        }
        return order + 1;
    }

    private int getIncomingFunctionOrder(Ft8Message message) {
        if (message == null) {
            return -1;
        }
        return message.checkIsCQ()
                ? 6
                : GeneralVariables.checkFunOrderByExtraInfo(message.getAutoReplyExtraInfo());
    }

    @SuppressLint("DefaultLocale")
    public void setBaseFrequency(float freq) {
        GeneralVariables.setBaseFrequency(freq);

        databaseOpr.writeConfig("freq", String.format("%.0f", freq), null);
    }

    public Ft8Message getFunctionCommand(int order) {
        int currentMode = GeneralVariables.getSignalMode();
        switch (order) {

            case 1:
                resetTargetReport();
                return new Ft8Message(currentMode, 1, 0, toCallsign.callsign, GeneralVariables.myCallsign
                        , GeneralVariables.getMyMaidenhead4Grid());

            case 2:
                sentTargetReport = toCallsign.snr;
                return new Ft8Message(currentMode, 1, 0, toCallsign.callsign
                        , GeneralVariables.myCallsign, toCallsign.getSnr());

            case 3:
                sentTargetReport = toCallsign.snr;
                return new Ft8Message(currentMode, 1, 0, toCallsign.callsign
                        , GeneralVariables.myCallsign, "R" + toCallsign.getSnr());

            case 4:
                return new Ft8Message(currentMode, 1, 0, toCallsign.callsign
                        , GeneralVariables.myCallsign, "RR73");

            case 5:
                return new Ft8Message(currentMode, 1, 0, toCallsign.callsign
                        , GeneralVariables.myCallsign, "73");

            case 6:
                resetTargetReport();
                Ft8Message msg = new Ft8Message(currentMode, 1, 0, "CQ", GeneralVariables.myCallsign
                        , GeneralVariables.getMyMaidenhead4Grid());
                msg.modifier = GeneralVariables.toModifier;
                return msg;
        }

        return new Ft8Message(currentMode, "CQ", GeneralVariables.myCallsign
                , GeneralVariables.getMyMaidenhead4Grid());
    }

    public void generateFun() {
        GeneralVariables.noReplyCount = 0;
        functionList.clear();
        for (int i = 1; i <= 6; i++) {
            if (functionOrder == 6) {
                functionList.add(new FunctionOfTransmit(6, getFunctionCommand(6), false));
                break;
            } else {
                functionList.add(new FunctionOfTransmit(i, getFunctionCommand(i), false));
            }
        }
        mutableFunctions.postValue(functionList);
        setCurrentFunctionOrder(functionOrder);
    }

    private short[] float2Short(float[] buffer) {
        short[] temp = new short[buffer.length + 8];
        for (int i = 0; i < buffer.length; i++) {
            float x = buffer[i];
            if (x > 1.0) {
                x = 1.0f;
            } else if (x < -1.0f) {
                x = -1.0f;
            }
            temp[i] = (short) (x * 32767.0);
        }
        return temp;
    }

    private void updateMessageStartTimeForOrder(int order) {
        if (order == 1 || order == 2) {
            messageStartTime = UtcTimer.getSystemTime();
        }
        if (messageStartTime == 0) {
            messageStartTime = UtcTimer.getSystemTime();
        }
    }

    private Ft8Message buildTransmitMessage(int order) {
        Ft8Message msg;
        if (transmitFreeText) {
            msg = new Ft8Message(GeneralVariables.getSignalMode(), "CQ",
                    GeneralVariables.myCallsign, freeText);
            msg.i3 = 0;
            msg.n3 = 0;
        } else {
            msg = getFunctionCommand(order);
        }
        msg.modifier = GeneralVariables.toModifier;
        msg.signalFormat = GeneralVariables.getSignalMode();
        return msg;
    }

    private void postTransmittingMessage(Ft8Message msg) {
        mutableTransmittingMessage.postValue(String.format("[%s] (%.0fHz) %s",
                FT8Common.modeToString(GeneralVariables.getSignalMode()),
                GeneralVariables.getBaseFrequency(),
                msg.getMessageText()));
    }

    private void rememberTransmitMessage(Ft8Message msg, int order) {
        lastTransmittedMessage = msg;
        lastTransmittedFunctionOrder = order;
    }

    private Ft8Message getAfterTransmitMessage(int order) {
        if (lastTransmittedMessage != null) {
            return lastTransmittedMessage;
        }
        try {
            return buildTransmitMessage(order);
        } catch (RuntimeException e) {
            Log.e(TAG, "getAfterTransmitMessage failed: " + e.getMessage());
            return null;
        }
    }

    private void notifyAfterTransmit(int order) {
        if (onDoTransmitted == null) {
            return;
        }
        Ft8Message message = getAfterTransmitMessage(order);
        if (message != null) {
            onDoTransmitted.onAfterTransmit(message, order);
        }
    }

    private void replaceLatestQueuedTransmitMessage(Ft8Message msg) {
        synchronized (GeneralVariables.transmitMessages) {
            int lastIndex = GeneralVariables.transmitMessages.size() - 1;
            if (lastIndex >= 0) {
                GeneralVariables.transmitMessages.set(lastIndex, msg);
            }
        }
    }

    private void updateTransmittingState(boolean transmitting) {
        synchronized (transmitStateLock) {
            isTransmitting = transmitting;
            mutableIsTransmitting.postValue(transmitting);
            if (!transmitting) {
                transmitStateLock.notifyAll();
            }
        }
    }

    private boolean waitForTransmitCompletion(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        synchronized (transmitStateLock) {
            while (isTransmitting) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    transmitStateLock.wait(Math.min(remaining, 50L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void playFT8Signal(Ft8Message msg) {
        final int currentMode = GeneralVariables.getSignalMode();
        final int currentSlotMs = FT8Common.getSlotTimeMillisecond(currentMode);
        final int currentSampleRate = GeneralVariables.audioSampleRate;

        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            Log.d(TAG, "playFT8Signal: start network audio transmit");

            if (onDoTransmitted != null) {
                onDoTransmitted.onTransmitByWifi(msg);
            }

            waitForTransmitCompletion(currentSlotMs - 200L);
            Log.d(TAG, "playFT8Signal: network audio transmit finished");
            afterPlayAudio();
            return;
        }


        if (GeneralVariables.controlMode == ControlMode.CAT) {
            Log.d(TAG, "playFT8Signal: try to transmit over CAT");

            if (onDoTransmitted != null) {
                if (onDoTransmitted.supportTransmitOverCAT()) {
                    onDoTransmitted.onTransmitOverCAT(msg);

                    waitForTransmitCompletion(currentSlotMs - 200L);
                    Log.d(TAG, "playFT8Signal: transmitting over CAT is finished.");
                    afterPlayAudio();
                    return;
                }
            }
        }

        //进入声卡模式
        float[] buffer;
        buffer = GenerateFTx.generateFtX(
                msg,
                GeneralVariables.getBaseFrequency(),
                GeneralVariables.audioSampleRate,
                GeneralVariables.getSignalMode()
        );
        if (buffer == null) {
            afterPlayAudio();
            return;
        }

        Log.d(TAG, String.format("playFT8Signal: prepare audio playback, mode=%s, format=%s, sampleRate=%d",
                FT8Common.modeToString(currentMode),
                GeneralVariables.audioOutput32Bit ? "Float32" : "Int16",
                currentSampleRate));

        attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        myFormat = new AudioFormat.Builder()
                .setSampleRate(currentSampleRate)
                .setEncoding(GeneralVariables.audioOutput32Bit ?
                        AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        int mySession = 0;
        int frameBytes = GeneralVariables.audioOutput32Bit ? 4 : 2;
        int bufferSize = currentSampleRate * currentSlotMs / 1000 * frameBytes;

        audioTrack = new AudioTrack(attributes, myFormat,
                bufferSize,
                AudioTrack.MODE_STATIC,
                mySession);


        int writeResult;
        if (GeneralVariables.audioOutput32Bit) {
            writeResult = audioTrack.write(buffer, 0, buffer.length, AudioTrack.WRITE_NON_BLOCKING);
        } else {
            short[] audio_data = float2Short(buffer);
            writeResult = audioTrack.write(audio_data, 0, audio_data.length, AudioTrack.WRITE_NON_BLOCKING);
        }

        if (buffer.length > writeResult) {
            Log.e(TAG, String.format("audio write truncated: %d -> %d", buffer.length, writeResult));
        }


        if (writeResult == AudioTrack.ERROR_INVALID_OPERATION
                || writeResult == AudioTrack.ERROR_BAD_VALUE
                || writeResult == AudioTrack.ERROR_DEAD_OBJECT
                || writeResult == AudioTrack.ERROR) {
            Log.e(TAG, String.format("audio write failed: %d", writeResult));
            afterPlayAudio();
            return;
        }

        audioTrack.setNotificationMarkerPosition(buffer.length);
        audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack audioTrack) {
                afterPlayAudio();
            }

            @Override
            public void onPeriodicNotification(AudioTrack audioTrack) {
            }
        });

        if (audioTrack != null) {
            audioTrack.play();
            audioTrack.setVolume(GeneralVariables.volumePercent);//设置播放的音量
        }
    }

    private void afterPlayAudio() {
        int transmittedOrder = lastTransmittedFunctionOrder > 0
                ? lastTransmittedFunctionOrder
                : functionOrder;
        notifyAfterTransmit(transmittedOrder);
        updateTransmittingState(false);
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        if (transmittedOrder == 5 && activated) {
            resetToCQ();
            mutableFunctionOrder.postValue(functionOrder);
            lastTransmittedFunctionOrder = -1;
        }
    }


    private void doComplete() {
        messageEndTime = UtcTimer.getSystemTime();


        toMaidenheadGrid = GeneralVariables.getGridByCallsign(toCallsign.callsign, databaseOpr);

        if (messageStartTime == 0) {
            messageStartTime = UtcTimer.getSystemTime();
        }


        for (int i = GeneralVariables.transmitMessages.size() - 1; i >= 0; i--) {
            Ft8Message message = GeneralVariables.transmitMessages.get(i);
            if ((GeneralVariables.checkFun3(message.extraInfo)
                    || GeneralVariables.checkFun2(message.extraInfo))
                    && (message.callsignFrom.equals(toCallsign.callsign)
                    && GeneralVariables.checkIsMyCallsign(message.callsignTo))) {
                receiveTargetReport = getReportFromExtraInfo(message.extraInfo);
                break;
            }
        }

        for (int i = GeneralVariables.transmitMessages.size() - 1; i >= 0; i--) {
            Ft8Message message = GeneralVariables.transmitMessages.get(i);
            if ((GeneralVariables.checkFun3(message.extraInfo)
                    || GeneralVariables.checkFun2(message.extraInfo))
                    && (message.callsignTo.equals(toCallsign.callsign)
                    && GeneralVariables.checkIsMyCallsign(message.callsignFrom))) {
                sentTargetReport = getReportFromExtraInfo(message.extraInfo);
                break;
            }
        }

        messageEndTime = UtcTimer.getSystemTime();
        if (onDoTransmitted != null) {
            onTransmitSuccess.doAfterTransmit(new QSLRecord(
                    messageStartTime,
                    messageEndTime,
                    GeneralVariables.myCallsign,
                    GeneralVariables.getMyMaidenhead4Grid(),
                    toCallsign.callsign,
                    toMaidenheadGrid,
                    sentTargetReport != -100 ? sentTargetReport : sendReport,
                    receiveTargetReport != -100 ? receiveTargetReport : receivedReport,
                    FT8Common.modeToString(GeneralVariables.getSignalMode()),
                    GeneralVariables.band,
                    Math.round(GeneralVariables.getBaseFrequency())
            ));

            GeneralVariables.addQSLCallsign(toCallsign.callsign);
            ToastMessage.show(String.format("QSO : %s , at %s", toCallsign.callsign
                    , BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)));
        }
    }

    public void setCurrentFunctionOrder(int order) {
        functionOrder = order;
        for (int i = 0; i < functionList.size(); i++) {
            functionList.get(i).setCurrentOrder(order);
        }
        if (order == 1) {
            resetTargetReport();
        }
        if (order == 4 || order == 5) {
            updateQSlRecordList(order, toCallsign);
        }
        mutableFunctions.postValue(functionList);
    }

    private boolean checkCallsignIsCallTo(String fromCall, String toCall) {
        if (fromCall == null || toCall == null) {
            return false;
        }

        String from = normalizeCallsignForMatch(fromCall);
        String to = normalizeCallsignForMatch(toCall);

        if (from.isEmpty() || to.isEmpty()) {
            return false;
        }

        if (from.equals(to)) {
            return true;
        }

        // Match by the main callsign part to support compact replies like K1ABC <-> K1ABC/P.
        return getMainCallsign(from).equals(getMainCallsign(to));
    }

    private String normalizeCallsignForMatch(String callsign) {
        return callsign.trim()
                .toUpperCase()
                .replace("<", "")
                .replace(">", "");
    }

    private String getMainCallsign(String callsign) {
        int len = callsign.length();
        if (len == 0) {
            return "";
        }

        int bestStart = 0;
        int bestLen = 0;
        int start = 0;

        while (start <= len) {
            int slash = callsign.indexOf('/', start);
            int end = (slash >= 0) ? slash : len;
            int tokenLen = end - start;
            if (tokenLen > bestLen) {
                bestLen = tokenLen;
                bestStart = start;
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }

        if (bestLen == 0) {
            return callsign;
        }
        return callsign.substring(bestStart, bestStart + bestLen);
    }

    private int checkTargetCallMe(ArrayList<Ft8Message> messages) {
        if (toCallsign == null) {
            return 1;
        }

        if (messages == null) {
            return 1;
        }

        int fromCount = 1;
        for (Ft8Message ft8Message : messages) {
            if (!ft8Message.isAutoFlowRelevant()) {
                continue;
            }
            if (GeneralVariables.checkIsMyCallsign(ft8Message.getAutoReplyCallsignTo())
                    && checkCallsignIsCallTo(ft8Message.getAutoReplyCallsignFrom(), toCallsign.callsign)) {
                return 0;
            }
            if (checkCallsignIsCallTo(ft8Message.getAutoReplyCallsignFrom(), toCallsign.callsign)) {
                fromCount++;
            }
        }
        return fromCount;
    }

    private int checkFunctionOrdFromMessages(ArrayList<Ft8Message> messages) {
        if (toCallsign == null) {
            return -1;
        }

        if (messages == null) {
            return -1;
        }
        Ft8Message bestMessage = null;
        int bestOrder = -1;
        int bestSequenceIndex = Integer.MIN_VALUE;
        for (Ft8Message ft8Message : messages) {
            if (!ft8Message.isAutoFlowRelevant()) {
                continue;
            }
            if (ft8Message.signalFormat != GeneralVariables.getSignalMode()) {
                continue;
            }

            String autoReplyTo = ft8Message.getAutoReplyCallsignTo();
            String autoReplyFrom = ft8Message.getAutoReplyCallsignFrom();
            String autoReplyExtra = ft8Message.getAutoReplyExtraInfo();
            boolean isDirectReply = GeneralVariables.checkIsMyCallsign(autoReplyTo)
                    && checkCallsignIsCallTo(autoReplyFrom, toCallsign.callsign);

            if (!isDirectReply && ft8Message.getSequence() == sequential) {
                continue;
            }

            if (!isDirectReply) {
                continue;
            }

            int order = getIncomingFunctionOrder(ft8Message);
            if (order == -1) {
                continue;
            }

            int sequenceIndex = ft8Message.getFullSequenceIndex();
            if (bestMessage == null
                    || order > bestOrder
                    || (order == bestOrder && sequenceIndex > bestSequenceIndex)
                    || (order == bestOrder
                    && sequenceIndex == bestSequenceIndex
                    && ft8Message.utcTime > bestMessage.utcTime)) {
                bestMessage = ft8Message;
                bestOrder = order;
                bestSequenceIndex = sequenceIndex;
            }
        }
        if (bestMessage == null) {
            return -1;
        }

        String bestExtraInfo = bestMessage.getAutoReplyExtraInfo();
        if (GeneralVariables.checkFun3(bestExtraInfo)
                || GeneralVariables.checkFun2(bestExtraInfo)) {
            receivedReport = getReportFromExtraInfo(bestExtraInfo);
            receiveTargetReport = receivedReport;
            if (receivedReport == -100) {
                receivedReport = bestMessage.report;
            }
        }

        sendReport = bestMessage.snr;
        return bestOrder;
    }


    private int getReportFromExtraInfo(String extraInfo) {
        if (extraInfo == null) {
            return -100;
        }
        String s = extraInfo.trim().toUpperCase();
        if (s.startsWith("R")) {
            s = s.substring(1).trim();
        }
        if (!s.matches("[+-]?[0-9]{1,2}")) {
            return -100;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -100;
        }
    }

    private boolean isExcludeMessage(Ft8Message msg) {
        if (msg == null) {
            return true;
        }
        return !msg.isAutoFlowRelevant()
                || isSameSequenceButNotCallToMe(msg)
                || msg.signalFormat != GeneralVariables.getSignalMode()
                || GeneralVariables.checkIsExcludeCallsign(msg.getAutoReplyCallsignFrom());
    }

    private boolean isSameSequenceButNotCallToMe(Ft8Message msg) {
        if (msg == null) {
            return false;
        }
        return msg.getSequence() == sequential
                && !GeneralVariables.checkIsMyCallsign(msg.getAutoReplyCallsignTo());
    }

    private boolean checkCQMeOrFollowCQMessage(ArrayList<Ft8Message> messages) {

        for (Ft8Message msg : messages) {
            if (isExcludeMessage(msg)) continue;
            if (toCallsign == null) break;

            if (GeneralVariables.checkIsMyCallsign(msg.getAutoReplyCallsignTo())
                    && checkCallsignIsCallTo(msg.getAutoReplyCallsignFrom(), toCallsign.callsign)
                    && !GeneralVariables.checkFun5(msg.getAutoReplyExtraInfo())) {
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getAutoReplyCallsignFrom(), msg.freq_hz
                                , msg.getSequence(), msg.snr)
                        , nextOrderFromIncoming(msg)
                        , msg.getAutoReplyExtraInfo());
                return true;
            }
        }

        if (toCallsign != null && toCallsign.haveTargetCallsign() && functionOrder != 6) {
            return false;
        }


        for (Ft8Message msg : messages) {
            if (isExcludeMessage(msg)) continue;
            if ((GeneralVariables.checkIsMyCallsign(msg.getAutoReplyCallsignTo())
                    && !GeneralVariables.checkFun5(msg.getAutoReplyExtraInfo()))) {
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getAutoReplyCallsignFrom(), msg.freq_hz
                                , msg.getSequence(), msg.snr)
                        , nextOrderFromIncoming(msg)
                        , msg.getAutoReplyExtraInfo());
                return true;
            }
        }

        if (!GeneralVariables.autoCallFollow) {
            return false;
        }

        if (toCallsign == null) {
            return false;
        }
        if (toCallsign.haveTargetCallsign()) {
            return false;
        }

        for (Ft8Message msg : messages) {
            if (isExcludeMessage(msg)) continue;

            if ((msg.checkIsCQ()
                    && ((GeneralVariables.autoCallFollow && GeneralVariables.autoFollowCQ)
                    || GeneralVariables.callsignInFollow(msg.getCallsignFrom()))
                    && !GeneralVariables.checkQSLCallsign(msg.getCallsignFrom())
                    && !GeneralVariables.checkIsMyCallsign(msg.callsignFrom))) {

                resetTargetReport();
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getCallsignFrom(), msg.freq_hz
                        , msg.getSequence(), msg.snr), 1, msg.extraInfo);

                return true;
            }
        }

        return false;
    }

    public void updateQSlRecordList(int order, TransmitCallsign toCall) {
        if (toCall == null) return;
        if (toCall.callsign.equals("CQ")) return;

        QSLRecord record = GeneralVariables.qslRecordList.getRecordByCallsign(toCall.callsign);
        if (record == null) {
            toMaidenheadGrid = GeneralVariables.getGridByCallsign(toCallsign.callsign, databaseOpr);
            record = GeneralVariables.qslRecordList.addQSLRecord(new QSLRecord(
                    messageStartTime,
                    messageEndTime,
                    GeneralVariables.myCallsign,
                    GeneralVariables.getMyMaidenhead4Grid(),
                    toCallsign.callsign,
                    toMaidenheadGrid,
                    sentTargetReport != -100 ? sentTargetReport : sendReport,
                    receiveTargetReport != -100 ? receiveTargetReport : receivedReport,
                    FT8Common.modeToString(GeneralVariables.getSignalMode()),
                    GeneralVariables.band,
                    Math.round(GeneralVariables.getBaseFrequency()
                    )));
        }

        switch (order) {
            case 1:
                record.setToMaidenGrid(toMaidenheadGrid);
                record.setSendReport(sentTargetReport != -100 ? sentTargetReport : sendReport);
                GeneralVariables.qslRecordList.deleteIfSaved(record);
                break;

            case 2:
            case 3:
                record.setSendReport(sentTargetReport != -100 ? sentTargetReport : sendReport);
                record.setReceivedReport(receiveTargetReport != -100 ? receiveTargetReport : receivedReport);
                GeneralVariables.qslRecordList.deleteIfSaved(record);
                break;

            case 4:
            case 5:
                if (!record.saved) {
                    doComplete();
                    record.saved = true;
                }
                break;
        }
    }

    public void parseMessageToFunction(ArrayList<Ft8Message> msgList) {
        if (GeneralVariables.myCallsign.length() < 3) {
            return;
        }
        if (msgList == null || msgList.size() == 0) {
            if (functionOrder == 5) {
                resetToCQ();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
            }
            return;
        }

        ArrayList<Ft8Message> messages = filterAutoMessages(new ArrayList<>(msgList));
        if (messages.size() == 0) {
            if (functionOrder == 5) {
                resetToCQ();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
            }
            return;
        }

        int newOrder = checkFunctionOrdFromMessages(messages);
        if (newOrder != -1) {
            GeneralVariables.noReplyCount = 0;
        }


        updateQSlRecordList(newOrder, toCallsign);

        boolean resetOnTargetCallOthers = (functionOrder == 4)
                && (GeneralVariables.getSignalMode() == FT8Common.FT8_MODE)
                && (checkTargetCallMe(messages) > 1);

        boolean rr73AlreadySent = functionOrder == 4 && lastTransmittedFunctionOrder == 4;

        if (newOrder == 5
                || (functionOrder == 5 && newOrder == -1)
                || (rr73AlreadySent && newOrder <= 3)
                || (functionOrder == 4 &&
                (GeneralVariables.noReplyCount > GeneralVariables.noReplyLimit * 2)
                && (GeneralVariables.noReplyLimit > 0))
                || resetOnTargetCallOthers
                || (functionOrder == 4 && (GeneralVariables.noReplyCount > 20)
                && (GeneralVariables.noReplyLimit == 0))) {

            resetToCQ();

            checkCQMeOrFollowCQMessage(messages);
            setCurrentFunctionOrder(functionOrder);
            mutableFunctionOrder.postValue(functionOrder);
            return;
        }

        if (newOrder != -1) {
            int nextOrder = newOrder + 1;
            if (newOrder == 1 || newOrder == 2) {
                resetTargetReport();
            }
            functionOrder = nextOrder;
            generateFun();
            mutableFunctionOrder.postValue(functionOrder);
            return;
        }

        if (checkCQMeOrFollowCQMessage(messages)) {
            return;
        }

        if (functionOrder == 6) {
            checkCQMeOrFollowCQMessage(messages);
            return;
        }

        if (hasUsableMessage(messages)) {
            GeneralVariables.noReplyCount++;
        }

        if ((GeneralVariables.noReplyCount > GeneralVariables.noReplyLimit) && (GeneralVariables.noReplyLimit > 0)) {
            if (!getNewTargetCallsign(messages)) {
                functionOrder = 6;
                if (toCallsign != null) {
                    toCallsign.callsign = "CQ";
                }
            }
            generateFun();
            setCurrentFunctionOrder(functionOrder);
            mutableToCallsign.postValue(toCallsign);
            mutableFunctionOrder.postValue(functionOrder);
        }
    }

    private ArrayList<Ft8Message> filterAutoMessages(ArrayList<Ft8Message> src) {
        ArrayList<Ft8Message> result = new ArrayList<>();
        int currentMode = GeneralVariables.getSignalMode();

        for (Ft8Message msg : src) {
            if (msg == null) continue;
            if (!msg.isAutoFlowRelevant()) continue;
            if (msg.signalFormat != currentMode) continue;


            if (isSameSequenceButNotCallToMe(msg) && !isDirectReplyToCurrentTarget(msg)) continue;
            result.add(msg);
        }
        return result;
    }

    private boolean isDirectReplyToCurrentTarget(Ft8Message msg) {
        if (msg == null || toCallsign == null) {
            return false;
        }
        return GeneralVariables.checkIsMyCallsign(msg.getAutoReplyCallsignTo())
                && checkCallsignIsCallTo(msg.getAutoReplyCallsignFrom(), toCallsign.callsign);
    }

    private boolean hasUsableMessage(ArrayList<Ft8Message> messages) {
        for (Ft8Message msg : messages) {
            if (msg == null) continue;
            if (!msg.isAutoFlowRelevant()) continue;
            if (!msg.isWeakSignal) {
                return true;
            }
        }
        return false;
    }

    public boolean getNewTargetCallsign(ArrayList<Ft8Message> messages) {
        if (toCallsign == null) return false;
        for (Ft8Message ft8Message : messages) {
            if (!ft8Message.isAutoFlowRelevant()) continue;
            if (ft8Message.signalFormat != GeneralVariables.getSignalMode()) continue;
            // Only enforce band match when decoded message carries a valid RF band.
            if (ft8Message.band > 0 && ft8Message.band != GeneralVariables.band) continue;
            if (!ft8Message.checkIsCQ()) continue;

            if ((!ft8Message.getCallsignFrom().equals(toCallsign.callsign)
                    && (!GeneralVariables.checkQSLCallsign(ft8Message.getCallsignFrom())))) {
                functionOrder = 1;
                toCallsign.callsign = ft8Message.getCallsignFrom();
                return true;
            }
        }
        return false;
    }

    public boolean isSynFrequency() {
        return GeneralVariables.synFrequency;
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
        if (!this.activated) {
            setTransmitting(false);
        }
        mutableIsActivated.postValue(activated);
    }

    public boolean isTransmitting() {
        return isTransmitting;
    }

    public void setTransmitting(boolean transmitting) {
        if (GeneralVariables.myCallsign.length() < 3 && transmitting) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
            return;
        }

        if (!transmitting) {
            if (audioTrack != null) {
                if (audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED) {
                    audioTrack.pause();
                }
                notifyAfterTransmit(lastTransmittedFunctionOrder > 0 ? lastTransmittedFunctionOrder : functionOrder);
            }
        }

        updateTransmittingState(transmitting);
    }

    public void restTransmitting() {
        if (GeneralVariables.myCallsign.length() < 3) {
            return;
        }

        int i3 = GenerateFT8.checkI3ByCallsign(GeneralVariables.myCallsign);
        setTransmit(new TransmitCallsign(i3, 0, "CQ",
                        UtcTimer.getNowSequential(GeneralVariables.getCurrentSlotTimeM()))
                , 6, "");
    }

    public void resetTargetReport() {
        receiveTargetReport = -100;
        sentTargetReport = -100;
    }

    public void resetToCQ() {
        resetTargetReport();
        lastTransmittedFunctionOrder = -1;
        if (toCallsign == null) {
            int i3 = GenerateFT8.checkI3ByCallsign(GeneralVariables.myCallsign);
            setTransmit(new TransmitCallsign(i3, 0, "CQ",
                            (UtcTimer.getNowSequential(GeneralVariables.getCurrentSlotTimeM()) + 1) % 2)
                    , 6, "");
        } else {
            functionOrder = 6;
            toCallsign.callsign = "CQ";
            mutableToCallsign.postValue(toCallsign);
            generateFun();
        }
    }

    public void setTimer_sec(int sec) {
        utcTimer.setTime_sec(sec);
    }

    public boolean isTransmitFreeText() {
        return transmitFreeText;
    }

    public void setFreeText(String freeText) {
        this.freeText = freeText;
    }

    public void setTransmitFreeText(boolean transmitFreeText) {
        this.transmitFreeText = transmitFreeText;
        if (transmitFreeText) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.trans_free_text_mode));
        } else {
            ToastMessage.show((GeneralVariables.getStringFromResource(R.string.trans_standard_messge_mode)));
        }
    }

    private static class DoTransmitRunnable implements Runnable {
        FT8TransmitSignal transmitSignal;

        public DoTransmitRunnable(FT8TransmitSignal transmitSignal) {
            this.transmitSignal = transmitSignal;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public void run() {
            int transmitOrder = transmitSignal.functionOrder;
            transmitSignal.updateMessageStartTimeForOrder(transmitOrder);
            Ft8Message msg = transmitSignal.buildTransmitMessage(transmitOrder);
            transmitSignal.rememberTransmitMessage(msg, transmitOrder);

            if (transmitSignal.onDoTransmitted != null) {
                transmitSignal.onDoTransmitted.onBeforeTransmit(msg, transmitOrder);
            }

            transmitSignal.updateTransmittingState(true);
            transmitSignal.postTransmittingMessage(msg);
            try {
                Thread.sleep(GeneralVariables.pttDelay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (!transmitSignal.transmitFreeText) {
                int latestOrder = transmitSignal.functionOrder;
                if (latestOrder != transmitOrder) {
                    transmitOrder = latestOrder;
                    transmitSignal.updateMessageStartTimeForOrder(transmitOrder);
                    msg = transmitSignal.buildTransmitMessage(transmitOrder);
                    transmitSignal.rememberTransmitMessage(msg, transmitOrder);
                    transmitSignal.replaceLatestQueuedTransmitMessage(msg);
                    transmitSignal.postTransmittingMessage(msg);
                }
            }


            transmitSignal.playFT8Signal(msg);
        }
    }
}
