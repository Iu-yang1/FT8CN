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
import com.bg7yoz.ft8cn.auto.AutoFlowMessageAnalyzer;
import com.bg7yoz.ft8cn.auto.AutoSessionState;
import com.bg7yoz.ft8cn.auto.AutoSessionType;
import com.bg7yoz.ft8cn.auto.AutoSessionUiPolicy;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.cq.CallQueueManager;
import com.bg7yoz.ft8cn.cq.CqCallEntry;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 鍙戝皠鎺у埗涓庤嚜鍔ㄩ€氳仈娴佺▼銆?
 */
public class FT8TransmitSignal {
    private static final String TAG = "FT8TransmitSignal";

    private volatile boolean transmitFreeText = false;
    private volatile String freeText = "FREE TEXT";
    private volatile String pendingDxpeditionMacroTemplate = null;
    private boolean deactivateAfterManualDxpeditionMacro = false;

    private final DatabaseOpr databaseOpr;
    private volatile TransmitCallsign toCallsign;
    public MutableLiveData<TransmitCallsign> mutableToCallsign = new MutableLiveData<>();

    private volatile int functionOrder = 6;
    public MutableLiveData<Integer> mutableFunctionOrder = new MutableLiveData<>();
    private boolean activated = false;
    public MutableLiveData<Boolean> mutableIsActivated = new MutableLiveData<>();
    public int sequential;
    public MutableLiveData<Integer> mutableSequential = new MutableLiveData<>();
    private boolean isTransmitting = false;
    private final Object transmitStateLock = new Object();
    private int lastTransmittedFunctionOrder = -1;
    private Ft8Message lastTransmittedMessage = null;
    private MultiSlotTransmitPlan lastTransmitPlan = null;
    //闃叉绔嬪嵆鍙戝皠鏃跺簭绔炰簤锛氳褰曚笂娆″彂灏勫皾璇曠殑鍛ㄦ湡绱㈠紩锛岄伩鍏嶅悓涓€鍛ㄦ湡閲嶅瑙﹀彂
    private long lastTransmitAttemptSequence = -1;
    // Ignore duplicated manual one-shot triggers caused by repeated click dispatch.
    // 閬垮厤閲嶅鐐瑰嚮瀵艰嚧瀹為獙妯″紡鍗曟鍙戝皠琚Е鍙戜袱娆°€?
    private long lastManualTransmitRequestMs = 0L;
    private volatile long lastDecodeMessageUpdateMs = 0L;
    public MutableLiveData<Boolean> mutableIsTransmitting = new MutableLiveData<>();
    public MutableLiveData<String> mutableTransmittingMessage = new MutableLiveData<>();
    public MutableLiveData<String> mutableDxpeditionFoxSlotStatus = new MutableLiveData<>();
    private long messageStartTime = 0;
    private long messageEndTime = 0;
    private String toMaidenheadGrid = "";
    private int sendReport = 0;
    private int sentTargetReport = -100;
    private int receivedReport = 0;
    private int receiveTargetReport = -100;
    private float houndReplyFrequencyHz = 0f;
    private int houndTx3SentCount = 0;
    private long foxSessionStartTimeMs = 0L;
    private int foxCallAttempts = 0;
    private int foxRr73Attempts = 0;
    private int lastNoReplySequenceIndex = Integer.MIN_VALUE;
    private String lastNoReplySessionKey = "";
    private final Object foxCandidateLock = new Object();
    private final ArrayList<DxpeditionFoxCandidate> dxpeditionFoxCandidates = new ArrayList<>();
    private final DxpeditionFoxSlotScheduler foxSlotScheduler = new DxpeditionFoxSlotScheduler();
    private String lastFoxCompletedCallsign = "";
    private final AutoSessionState autoSession = new AutoSessionState();
    private final OnTransmitSuccess onTransmitSuccess;
    private AudioAttributes attributes = null;
    private AudioFormat myFormat = null;
    private AudioTrack audioTrack = null;
    public UtcTimer utcTimer;
    public ArrayList<FunctionOfTransmit> functionList = new ArrayList<>();
    public MutableLiveData<ArrayList<FunctionOfTransmit>> mutableFunctions = new MutableLiveData<>();
    private final CallQueueManager callQueueManager = new CallQueueManager();
    public MutableLiveData<ArrayList<CqCallEntry>> mutableCqQueue = new MutableLiveData<>();
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
        callQueueManager.setDatabaseOpr(databaseOpr);
        updateCqQueueSettings();

        setTransmitting(false);
        setActivated(false);


        GeneralVariables.mutableVolumePercent.observeForever(volumePercentObserver);

        buildUtcTimer();
        utcTimer.start();
        foxSlotScheduler.setMaxTxSlots(GeneralVariables.dxpeditionFoxTxSlots);
        foxSlotScheduler.setSpecialMessageEnabled(GeneralVariables.dxpeditionFoxAutoSpecialMessage);
        foxSlotScheduler.setCqOnFreeSlotEnabled(GeneralVariables.dxpeditionFoxCqOnFreeSlot);
        updateDxpeditionFoxSlotStatus();
        syncNoReplyCount();
    }

    private void syncNoReplyCount() {
        GeneralVariables.noReplyCount = autoSession.getNoReplyCount();
    }

    private boolean isDxpeditionHoundAutoEnabled() {
        return GeneralVariables.autoDxpeditionHound
                && GeneralVariables.getSignalMode() == FT8Common.FT8_MODE
                && !transmitFreeText
                && !isExperimentalManualTxMode();
    }

    private boolean isManualDxpeditionHoundEnabled() {
        return GeneralVariables.manualDxpeditionHoundMode
                && GeneralVariables.getSignalMode() == FT8Common.FT8_MODE
                && !transmitFreeText
                && !isExperimentalManualTxMode();
    }

    private boolean isManualDxpeditionFoxEnabled() {
        return GeneralVariables.manualDxpeditionFoxMode
                && GeneralVariables.getSignalMode() == FT8Common.FT8_MODE
                && !transmitFreeText
                && !isExperimentalManualTxMode();
    }

    private static final class DxpeditionFoxCandidate {
        String callsign;
        int snr;
        int sequenceIndex;
        long utcTime;
    }

    public boolean canUseManualDxpeditionMacro() {
        return isManualDxpeditionHoundEnabled() || isManualDxpeditionFoxEnabled();
    }

    public String previewManualDxpeditionMacro(String template) {
        return DxpeditionMacroSupport.renderTemplate(
                template,
                getCurrentTargetCallsign(),
                GeneralVariables.myCallsign,
                getCurrentMacroReport()
        );
    }

    private String normalizeCallsignToken(String callsign) {
        if (callsign == null) {
            return "";
        }
        return callsign.trim().toUpperCase()
                .replace("<", "")
                .replace(">", "");
    }

    private void addUniqueCallsign(ArrayList<String> list, String callsign) {
        String normalized = normalizeCallsignToken(callsign);
        if (normalized.length() == 0) {
            return;
        }
        for (String existing : list) {
            if (AutoFlowMessageAnalyzer.callsignMatches(existing, normalized)) {
                return;
            }
        }
        list.add(normalized);
    }

    private void addOrUpdateFoxCandidate(String callsign, int snr, int sequenceIndex, long utcTime) {
        String normalized = normalizeCallsignToken(callsign);
        if (normalized.length() == 0) {
            return;
        }
        if (GeneralVariables.checkIsMyCallsign(normalized) || "CQ".equalsIgnoreCase(normalized)) {
            return;
        }
        synchronized (foxCandidateLock) {
            DxpeditionFoxCandidate target = null;
            for (DxpeditionFoxCandidate candidate : dxpeditionFoxCandidates) {
                if (AutoFlowMessageAnalyzer.callsignMatches(candidate.callsign, normalized)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                target = new DxpeditionFoxCandidate();
                target.callsign = normalized;
                dxpeditionFoxCandidates.add(target);
            } else if (target.callsign.length() < normalized.length()) {
                target.callsign = normalized;
            }
            target.snr = snr;
            target.sequenceIndex = sequenceIndex;
            target.utcTime = utcTime;

            Collections.sort(dxpeditionFoxCandidates, new Comparator<DxpeditionFoxCandidate>() {
                @Override
                public int compare(DxpeditionFoxCandidate left, DxpeditionFoxCandidate right) {
                    if (left.sequenceIndex != right.sequenceIndex) {
                        return right.sequenceIndex - left.sequenceIndex;
                    }
                    if (left.utcTime == right.utcTime) {
                        return 0;
                    }
                    return left.utcTime < right.utcTime ? 1 : -1;
                }
            });

            if (dxpeditionFoxCandidates.size() > 24) {
                dxpeditionFoxCandidates.subList(24, dxpeditionFoxCandidates.size()).clear();
            }
        }
    }

    private void updateFoxCandidatesFromMessages(ArrayList<Ft8Message> messages) {
        if (messages == null || messages.size() == 0 || GeneralVariables.getSignalMode() != FT8Common.FT8_MODE) {
            return;
        }
        for (Ft8Message msg : messages) {
            if (msg == null || msg.signalFormat != FT8Common.FT8_MODE || !msg.isAutoFlowRelevant()) {
                continue;
            }
            if (msg.band > 0 && msg.band != GeneralVariables.band) {
                continue;
            }
            String from = normalizeCallsignToken(msg.getAutoReplyCallsignFrom());
            if (from.length() == 0 || GeneralVariables.checkIsMyCallsign(from)) {
                continue;
            }

            boolean directedToMe = GeneralVariables.checkIsMyCallsign(msg.getAutoReplyCallsignTo());
            boolean likelyFoxHoundCall = directedToMe && GeneralVariables.checkFun1(msg.getAutoReplyExtraInfo());
            boolean currentTargetTraffic = toCallsign != null
                    && toCallsign.haveTargetCallsign()
                    && AutoFlowMessageAnalyzer.callsignMatches(from, toCallsign.callsign);
            if (!directedToMe && !likelyFoxHoundCall && !currentTargetTraffic) {
                continue;
            }
            addOrUpdateFoxCandidate(from, msg.snr, msg.getFullSequenceIndex(), msg.utcTime);
        }
    }

    public ArrayList<String> getDxpeditionFoxCandidateCallsigns() {
        ArrayList<String> result = new ArrayList<>();
        if (toCallsign != null && toCallsign.haveTargetCallsign()) {
            addUniqueCallsign(result, toCallsign.callsign);
        }
        addUniqueCallsign(result, lastFoxCompletedCallsign);
        synchronized (foxCandidateLock) {
            for (DxpeditionFoxCandidate candidate : dxpeditionFoxCandidates) {
                addUniqueCallsign(result, candidate.callsign);
            }
        }
        return result;
    }

    private String pickAutoCompoundCall2(ArrayList<String> candidates) {
        if (toCallsign != null && toCallsign.haveTargetCallsign()) {
            String normalized = normalizeCallsignToken(toCallsign.callsign);
            if (normalized.length() > 0 && !"CQ".equalsIgnoreCase(normalized)) {
                return normalized;
            }
        }
        if (candidates.size() > 0) {
            return normalizeCallsignToken(candidates.get(0));
        }
        return "";
    }

    private String pickAutoCompoundCall1(ArrayList<String> candidates, String call2) {
        String completed = normalizeCallsignToken(lastFoxCompletedCallsign);
        if (completed.length() > 0 && !AutoFlowMessageAnalyzer.callsignMatches(completed, call2)) {
            return completed;
        }
        for (String candidate : candidates) {
            if (!AutoFlowMessageAnalyzer.callsignMatches(candidate, call2)) {
                return normalizeCallsignToken(candidate);
            }
        }
        return "";
    }

    private int clampReportForCompound(int report) {
        if (report < -30) {
            return -30;
        }
        if (report > 32) {
            return 32;
        }
        return report;
    }

    public int getSuggestedDxpeditionCompoundReport() {
        return clampReportForCompound(getCurrentMacroReport());
    }

    public String previewManualDxpeditionCompoundMessage(boolean autoSelect,
                                                         String manualCall1,
                                                         String manualCall2,
                                                         int report) {
        if (!isManualDxpeditionFoxEnabled()) {
            return "";
        }
        ArrayList<String> candidates = getDxpeditionFoxCandidateCallsigns();

        String call2 = normalizeCallsignToken(manualCall2);
        if (autoSelect || call2.length() == 0) {
            call2 = pickAutoCompoundCall2(candidates);
        }

        String call1 = normalizeCallsignToken(manualCall1);
        if (autoSelect || call1.length() == 0) {
            call1 = pickAutoCompoundCall1(candidates, call2);
        }

        if (call1.length() == 0 || call2.length() == 0
                || AutoFlowMessageAnalyzer.callsignMatches(call1, call2)) {
            return "";
        }

        String myCallsign = normalizeCallsignToken(GeneralVariables.myCallsign);
        if (myCallsign.length() == 0) {
            return "";
        }

        int clampedReport = clampReportForCompound(report);
        return String.format("%s RR73; %s <%s> %+03d",
                call1,
                call2,
                myCallsign,
                clampedReport);
    }

    public boolean sendManualDxpeditionCompoundMessage(boolean autoSelect,
                                                       String manualCall1,
                                                       String manualCall2,
                                                       int report) {
        if (!isManualDxpeditionFoxEnabled()) {
            return false;
        }
        String message = previewManualDxpeditionCompoundMessage(autoSelect, manualCall1, manualCall2, report);
        if (message.length() == 0) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.dxpedition_compound_pick_invalid));
            return false;
        }

        String typeInfo = GenerateFT8.getPackedTypeInfo(message);
        if (!typeInfo.startsWith("0.1:")) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.dxpedition_compound_encode_failed));
            return false;
        }
        return sendManualDxpeditionMacro(message);
    }

    public boolean sendManualDxpeditionMacro(String template) {
        if (!canUseManualDxpeditionMacro()) {
            return false;
        }
        String normalizedTemplate = DxpeditionMacroSupport.normalizeTemplate(template);
        if (normalizedTemplate.length() == 0) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.dxpedition_macro_empty));
            return false;
        }
        if (DxpeditionMacroSupport.requiresTarget(normalizedTemplate)
                && (toCallsign == null || !toCallsign.haveTargetCallsign())) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.dxpedition_macro_requires_target));
            return false;
        }

        String rendered = previewManualDxpeditionMacro(normalizedTemplate);
        if (rendered.length() == 0 || GenerateFT8.getPackedTypeInfo(rendered).length() == 0) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.dxpedition_macro_invalid));
            return false;
        }

        pendingDxpeditionMacroTemplate = normalizedTemplate;
        deactivateAfterManualDxpeditionMacro = !activated;
        if (!activated) {
            setActivated(true);
        }
        transmitNow();
        return true;
    }

    private AutoSessionType resolveBoundSessionType(TransmitCallsign transmitCallsign) {
        if (transmitCallsign == null) {
            return AutoSessionType.STANDARD;
        }
        if (isManualDxpeditionFoxEnabled()) {
            return AutoSessionType.FT8_DXPEDITION_FOX;
        }
        if (isManualDxpeditionHoundEnabled()
                && transmitCallsign.haveTargetCallsign()) {
            return AutoSessionType.FT8_DXPEDITION_HOUND;
        }
        if (isDxpeditionHoundAutoEnabled()
                && transmitCallsign.signalFormat == FT8Common.FT8_MODE
                && transmitCallsign.i3 == 0
                && transmitCallsign.n3 == 1) {
            return AutoSessionType.FT8_DXPEDITION_HOUND;
        }
        return AutoSessionType.STANDARD;
    }

    private void buildUtcTimer() {
        utcTimer = new UtcTimer(FT8Common.getSlotTimeM(GeneralVariables.getSignalMode()), false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {
            }

            @Override
            public void doOnSecTimer(long utc) {
                if (isExperimentalManualTxMode()) {
                    return;
                }

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
            utcTimer.destroy();
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
            utcTimer.destroy();
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

        if (isExperimentalManualTxMode()) {
            if (!activated) {
                setActivated(true);
            }
            long now = System.currentTimeMillis();
            if (now - lastManualTransmitRequestMs < 300L) {
                Log.w(TAG, "transmitNow ignored: duplicate manual trigger");
                return;
            }
            lastManualTransmitRequestMs = now;
            doTransmit();
            return;
        }

        ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.adjust_call_target)
                , toCallsign.callsign));


        resetTargetReport();

        // 绔嬪嵆鍙戝皠鏃舵坊鍔犲幓閲嶆鏌ワ紝闃叉鍛ㄦ湡杈圭晫閲嶅瑙﹀彂
        int currentSeq = UtcTimer.getNowSequential(GeneralVariables.getCurrentSlotTimeM());
        long currentFullSeq = UtcTimer.getSystemTime() / FT8Common.getSlotTimeMillisecond(GeneralVariables.getSignalMode());

        if (currentSeq == sequential && currentFullSeq != lastTransmitAttemptSequence) {
            if ((UtcTimer.getSystemTime() % FT8Common.getSlotTimeMillisecond(GeneralVariables.getSignalMode()))
                    < FT8Common.getImmediateTxWindowMs(GeneralVariables.getSignalMode())) {
                lastTransmitAttemptSequence = currentFullSeq;
                setTransmitting(false);
                doTransmit();
            }
        }
    }


    public void doTransmit() {
        if (!activated && !isExperimentalManualTxMode()) {
            return;
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
        boolean reserveBeforeQueue = isExperimentalManualTxMode();
        synchronized (transmitStateLock) {
            if (isTransmitting) {
                Log.w(TAG, "doTransmit ignored: transmit already in progress");
                return;
            }
            if (reserveBeforeQueue) {
                // For manual experimental TX we reserve the state before queueing
                // to prevent duplicate click events from dispatching two jobs.
                // 瀹為獙鎵嬪姩鍙戝皠鍦ㄥ叆闃熷墠鍏堝崰浣嶏紝閬垮厤閲嶅鐐瑰嚮骞跺彂鍏ラ槦銆?
                isTransmitting = true;
                mutableIsTransmitting.postValue(true);
            }
        }
        Log.d(TAG, "doTransmit: start transmit");
        try {
            doTransmitThreadPool.execute(new DoTransmitRunnable(this));
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "doTransmit rejected: " + e.getMessage());
            if (reserveBeforeQueue) {
                updateTransmittingState(false);
            }
            return;
        }
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

        if (functionOrder == -1) {//璇存槑鏄洖澶嶆秷鎭?
            this.functionOrder = normalizeFunctionOrder(
                    GeneralVariables.checkFunOrderByExtraInfo(toMaidenheadGrid) + 1);
            if (this.functionOrder == 6) {
                this.functionOrder = 1;
            }
        } else {
            this.functionOrder = normalizeFunctionOrder(functionOrder);
        }

        AutoSessionType boundSessionType = resolveBoundSessionType(transmitCallsign);

        if (transmitCallsign.frequency == 0) {
            transmitCallsign.frequency = GeneralVariables.getBaseFrequency();
        }
        if (GeneralVariables.synFrequency && boundSessionType != AutoSessionType.FT8_DXPEDITION_FOX) {
            setBaseFrequency(transmitCallsign.frequency);
        }

        sequential = (toCallsign.sequential + 1) % 2;
        autoSession.bindTarget(
                toCallsign.callsign,
                GeneralVariables.getSignalMode(),
                GeneralVariables.band,
                boundSessionType
        );
        resetDxpeditionCountersForNewTarget(boundSessionType, toCallsign, this.functionOrder);
        syncNoReplyCount();
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

    @SuppressLint("DefaultLocale")
    public void setBaseFrequency(float freq) {
        GeneralVariables.setBaseFrequency(freq);

        databaseOpr.writeConfig("freq", String.format("%.0f", freq), null);
    }

    private void setRuntimeBaseFrequency(float freq) {
        GeneralVariables.setBaseFrequency(freq);
    }

    private void resetDxpeditionCountersForNewTarget(AutoSessionType sessionType,
                                                     TransmitCallsign target,
                                                     int currentOrder) {
        if (sessionType == AutoSessionType.FT8_DXPEDITION_HOUND) {
            houndTx3SentCount = 0;
            if (target != null && target.frequency > 0) {
                houndReplyFrequencyHz = target.frequency;
            }
            foxSessionStartTimeMs = 0L;
            foxCallAttempts = 0;
            foxRr73Attempts = 0;
            return;
        }

        if (sessionType == AutoSessionType.FT8_DXPEDITION_FOX) {
            houndReplyFrequencyHz = 0f;
            houndTx3SentCount = 0;
            foxRr73Attempts = 0;
            if (target != null && target.haveTargetCallsign() && currentOrder != 6) {
                if (foxSessionStartTimeMs == 0L) {
                    foxSessionStartTimeMs = UtcTimer.getSystemTime();
                }
            } else {
                foxSessionStartTimeMs = 0L;
                foxCallAttempts = 0;
                foxRr73Attempts = 0;
            }
            return;
        }

        houndReplyFrequencyHz = 0f;
        houndTx3SentCount = 0;
        foxSessionStartTimeMs = 0L;
        foxCallAttempts = 0;
        foxRr73Attempts = 0;
    }

    private void applyDxpeditionFrequencyPolicyForOrder(int order) {
        if (GeneralVariables.getSignalMode() != FT8Common.FT8_MODE || transmitFreeText || isExperimentalManualTxMode()) {
            return;
        }

        if (autoSession.isDxpeditionFox()) {
            float targetFrequency;
            if (order == 6 && !GeneralVariables.dxpeditionFoxHoldFrequency) {
                targetFrequency = DxpeditionFrequencyPolicy.pickFoxCqFrequency(UtcTimer.getSystemTime() / 1000L);
            } else {
                targetFrequency = GeneralVariables.getBaseFrequency();
            }
            targetFrequency = DxpeditionFrequencyPolicy.clampFoxTxFrequency(targetFrequency);
            if (Math.abs(targetFrequency - GeneralVariables.getBaseFrequency()) > 0.5f) {
                setRuntimeBaseFrequency(targetFrequency);
            }
            return;
        }

        if (!autoSession.isDxpeditionHound()) {
            return;
        }

        if (order == 1) {
            float clamped = DxpeditionFrequencyPolicy.clampHoundInitialFrequency(GeneralVariables.getBaseFrequency());
            if (Math.abs(clamped - GeneralVariables.getBaseFrequency()) > 0.5f) {
                setRuntimeBaseFrequency(clamped);
            }
            houndTx3SentCount = 0;
            return;
        }

        if (order == 3) {
            float base = houndReplyFrequencyHz > 0
                    ? houndReplyFrequencyHz
                    : GeneralVariables.getBaseFrequency();
            float txFreq = DxpeditionFrequencyPolicy.resolveHoundRReportFrequency(base, houndTx3SentCount);
            if (Math.abs(txFreq - GeneralVariables.getBaseFrequency()) > 0.5f) {
                setRuntimeBaseFrequency(txFreq);
            }
            return;
        }

        if (order != 3) {
            houndTx3SentCount = 0;
        }
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
        autoSession.resetNoReplyCount();
        syncNoReplyCount();
        functionList.clear();
        int sanitizedOrder = AutoSessionUiPolicy.sanitizeFunctionOrder(
                autoSession.getSessionType(),
                functionOrder,
                functionOrder
        );
        if (sanitizedOrder != functionOrder) {
            functionOrder = sanitizedOrder;
        }
        int[] availableOrders = AutoSessionUiPolicy.getAvailableFunctionOrders(
                autoSession.getSessionType(),
                functionOrder
        );
        for (int order : availableOrders) {
            functionList.add(new FunctionOfTransmit(order, getFunctionCommand(order), false));
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

    private String buildBufferStats(float[] buffer, int sampleRate) {
        if (buffer == null || buffer.length == 0) {
            return "samples=0, durationMs=0.0, peak=0.000000, rms=0.000000";
        }
        float peak = 0.0f;
        double energy = 0.0;
        for (float sample : buffer) {
            float abs = Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
            energy += sample * sample;
        }
        double rms = Math.sqrt(energy / buffer.length);
        float durationMs = sampleRate > 0 ? buffer.length * 1000.0f / sampleRate : 0.0f;
        return String.format(java.util.Locale.US,
                "samples=%d, durationMs=%.1f, peak=%.6f, rms=%.6f",
                buffer.length,
                durationMs,
                peak,
                rms);
    }

    private int writeAudioTrackFully(AudioTrack track, float[] buffer) {
        if (track == null || buffer == null) {
            return AudioTrack.ERROR_BAD_VALUE;
        }
        int totalWritten = 0;
        while (totalWritten < buffer.length) {
            int written = track.write(
                    buffer,
                    totalWritten,
                    buffer.length - totalWritten,
                    AudioTrack.WRITE_BLOCKING
            );
            if (written <= 0) {
                Log.e(TAG, String.format(java.util.Locale.US,
                        "audio float write stopped: offset=%d, remaining=%d, result=%d",
                        totalWritten,
                        buffer.length - totalWritten,
                        written));
                return written;
            }
            totalWritten += written;
        }
        return totalWritten;
    }

    private int writeAudioTrackFully(AudioTrack track, short[] buffer) {
        if (track == null || buffer == null) {
            return AudioTrack.ERROR_BAD_VALUE;
        }
        int totalWritten = 0;
        while (totalWritten < buffer.length) {
            int written = track.write(
                    buffer,
                    totalWritten,
                    buffer.length - totalWritten,
                    AudioTrack.WRITE_BLOCKING
            );
            if (written <= 0) {
                Log.e(TAG, String.format(java.util.Locale.US,
                        "audio short write stopped: offset=%d, remaining=%d, result=%d",
                        totalWritten,
                        buffer.length - totalWritten,
                        written));
                return written;
            }
            totalWritten += written;
        }
        return totalWritten;
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
        if (hasPendingDxpeditionMacro()) {
            msg = buildPendingDxpeditionMacroMessage();
        } else if (transmitFreeText) {
            msg = new Ft8Message(GeneralVariables.getSignalMode(), "CQ",
                    GeneralVariables.myCallsign, freeText);
            msg.setTransmitRawText(freeText);
            msg.i3 = 0;
            msg.n3 = 0;
        } else {
            msg = getFunctionCommand(order);
        }
        msg.modifier = GeneralVariables.toModifier;
        msg.signalFormat = GeneralVariables.getSignalMode();
        return msg;
    }

    private boolean hasPendingDxpeditionMacro() {
        return pendingDxpeditionMacroTemplate != null
                && pendingDxpeditionMacroTemplate.trim().length() > 0;
    }

    private String getCurrentTargetCallsign() {
        if (toCallsign == null || toCallsign.callsign == null) {
            return "";
        }
        return toCallsign.callsign;
    }

    private int getCurrentMacroReport() {
        if (toCallsign != null) {
            return toCallsign.snr;
        }
        if (sentTargetReport != -100) {
            return sentTargetReport;
        }
        if (sendReport != 0) {
            return sendReport;
        }
        return -1;
    }

    private Ft8Message buildPendingDxpeditionMacroMessage() {
        String rendered = DxpeditionMacroSupport.renderTemplate(
                pendingDxpeditionMacroTemplate,
                getCurrentTargetCallsign(),
                GeneralVariables.myCallsign,
                getCurrentMacroReport()
        );
        Ft8Message msg = new Ft8Message(GeneralVariables.getSignalMode(), "CQ",
                GeneralVariables.myCallsign, rendered);
        msg.setTransmitRawText(rendered);
        msg.i3 = 0;
        msg.n3 = 0;
        return msg;
    }

    private void clearPendingDxpeditionMacro() {
        pendingDxpeditionMacroTemplate = null;
    }

    private MultiSlotTransmitPlan buildTransmitPlan(int order) {
        int currentMode = GeneralVariables.getSignalMode();
        if (shouldUseFoxSlotScheduler()) {
            MultiSlotTransmitPlan foxPlan = foxSlotScheduler.buildTransmitPlan(
                    GeneralVariables.myCallsign,
                    currentMode
            );
            if (!foxPlan.isEmpty()) {
                return foxPlan;
            }
        }

        Ft8Message msg = buildTransmitMessage(order);
        float frequency = GeneralVariables.getBaseFrequency();
        if (autoSession.isDxpeditionFox() && order != 6) {
            frequency = DxpeditionFoxSlotFrequencyConfig.resolveSlotFrequency(0);
        }
        return MultiSlotTransmitPlan.single(msg, order, frequency, currentMode);
    }

    private boolean shouldUseFoxSlotScheduler() {
        return isManualDxpeditionFoxEnabled()
                && !transmitFreeText
                && !hasPendingDxpeditionMacro()
                && !isExperimentalManualTxMode();
    }

    private MultiSlotTransmitPlan enforceTransportLimit(MultiSlotTransmitPlan plan) {
        if (plan == null || plan.size() <= 1) {
            return plan;
        }
        boolean externalWaveTransport = GeneralVariables.connectMode == ConnectMode.NETWORK
                || (GeneralVariables.controlMode == ControlMode.CAT
                && onDoTransmitted != null
                && onDoTransmitted.supportTransmitOverCAT());
        if (!externalWaveTransport) {
            return plan;
        }

        MultiSlotTransmitItem primary = plan.getPrimaryItem();
        Log.w(TAG, "external wave transport supports single message only, use primary slot");
        return MultiSlotTransmitPlan.single(
                primary.message,
                primary.functionOrder,
                primary.frequencyHz,
                plan.getSignalMode());
    }

    private void postTransmittingMessage(MultiSlotTransmitPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return;
        }
        mutableTransmittingMessage.postValue(
                plan.getDisplayText(GeneralVariables.getActiveModeLabel()));
    }

    private void rememberTransmitMessage(Ft8Message msg, int order) {
        lastTransmittedMessage = msg;
        lastTransmittedFunctionOrder = order;
        lastTransmitPlan = MultiSlotTransmitPlan.single(
                msg,
                order,
                GeneralVariables.getBaseFrequency(),
                GeneralVariables.getSignalMode());
        rememberTransmitCounters(order);
    }

    private void rememberTransmitPlan(MultiSlotTransmitPlan plan, int fallbackOrder) {
        lastTransmitPlan = plan;
        Ft8Message primaryMessage = plan == null ? null : plan.getPrimaryMessage();
        int primaryOrder = plan == null ? fallbackOrder : plan.getPrimaryFunctionOrder(fallbackOrder);
        lastTransmittedMessage = primaryMessage;
        lastTransmittedFunctionOrder = primaryOrder;
        rememberTransmitCounters(primaryOrder);
    }

    private void rememberTransmitCounters(int order) {
        if (autoSession.isDxpeditionHound()) {
            if (order == 3) {
                houndTx3SentCount++;
            } else {
                houndTx3SentCount = 0;
            }
        } else {
            houndTx3SentCount = 0;
        }

        if (autoSession.isDxpeditionFox()) {
            if (order == 2) {
                foxCallAttempts++;
                if (foxSessionStartTimeMs == 0L) {
                    foxSessionStartTimeMs = UtcTimer.getSystemTime();
                }
            } else if (order == 4) {
                foxRr73Attempts++;
            }
        } else {
            foxCallAttempts = 0;
            foxRr73Attempts = 0;
            foxSessionStartTimeMs = 0L;
        }
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
        if (lastTransmitPlan != null && !lastTransmitPlan.isEmpty()) {
            for (MultiSlotTransmitItem item : lastTransmitPlan.getItems()) {
                onDoTransmitted.onAfterTransmit(item.message, item.functionOrder);
            }
            return;
        }
        Ft8Message message = getAfterTransmitMessage(order);
        if (message != null) {
            onDoTransmitted.onAfterTransmit(message, order);
        }
    }

    private void notifyBeforeTransmit(MultiSlotTransmitPlan plan) {
        if (onDoTransmitted == null || plan == null || plan.isEmpty()) {
            return;
        }
        for (MultiSlotTransmitItem item : plan.getItems()) {
            onDoTransmitted.onBeforeTransmit(item.message, item.functionOrder);
        }
    }

    private void replaceLatestQueuedTransmitMessage(Ft8Message msg) {
        synchronized (GeneralVariables.transmitHistoryMessages) {
            int lastHistoryIndex = GeneralVariables.transmitHistoryMessages.size() - 1;
            if (lastHistoryIndex >= 0) {
                GeneralVariables.transmitHistoryMessages.set(lastHistoryIndex, msg);
            }
        }
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

    private void playTransmitPlan(MultiSlotTransmitPlan plan) {
        if (plan == null || plan.isEmpty()) {
            afterPlayAudio();
            return;
        }

        final int currentMode = plan.getSignalMode();
        final int currentSlotMs = FT8Common.getSlotTimeMillisecond(currentMode);
        final int currentSampleRate = GeneralVariables.audioSampleRate;
        final MultiSlotTransmitItem primaryItem = plan.getPrimaryItem();
        final Ft8Message primaryMessage = primaryItem.message;

        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            Log.d(TAG, "playFT8Signal: start network audio transmit");

            if (onDoTransmitted != null) {
                if (plan.size() > 1) {
                    Log.w(TAG, "network transmit supports single message only, use primary slot");
                }
                setRuntimeBaseFrequency(primaryItem.frequencyHz);
                onDoTransmitted.onTransmitByWifi(primaryMessage);
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
                    if (plan.size() > 1) {
                        Log.w(TAG, "CAT wave transmit supports single message only, use primary slot");
                    }
                    setRuntimeBaseFrequency(primaryItem.frequencyHz);
                    onDoTransmitted.onTransmitOverCAT(primaryMessage);

                    waitForTransmitCompletion(currentSlotMs - 200L);
                    Log.d(TAG, "playFT8Signal: transmitting over CAT is finished.");
                    afterPlayAudio();
                    return;
                }
            }
        }

        //杩涘叆澹板崱妯″紡
        Log.d(TAG, String.format(java.util.Locale.US,
                "playTransmitPlan build-wave start: mode=%s, submode=%s, trPeriod=%d, sampleRate=%d, slots=%d, freq=%.1f, text=%s",
                FT8Common.modeToString(currentMode),
                FT8Common.getQ65SubmodeLabel(GeneralVariables.getQ65Submode()),
                GeneralVariables.getQ65TrPeriodSeconds(),
                currentSampleRate,
                plan.size(),
                primaryItem == null ? 0.0f : primaryItem.frequencyHz,
                primaryMessage == null ? "" : primaryMessage.getMessageText()));
        float[] buffer = MultiSlotAudioMixer.build(plan, GeneralVariables.audioSampleRate);
        if (buffer == null) {
            Log.e(TAG, String.format(java.util.Locale.US,
                    "playTransmitPlan failed: reason=mixer-returned-null, mode=%s, submode=%s, trPeriod=%d, sampleRate=%d, freq=%.1f, text=%s",
                    FT8Common.modeToString(currentMode),
                    FT8Common.getQ65SubmodeLabel(GeneralVariables.getQ65Submode()),
                    GeneralVariables.getQ65TrPeriodSeconds(),
                    currentSampleRate,
                    primaryItem == null ? 0.0f : primaryItem.frequencyHz,
                    primaryMessage == null ? "" : primaryMessage.getMessageText()));
            afterPlayAudio();
            return;
        }
        Log.d(TAG, String.format(java.util.Locale.US,
                "playTransmitPlan buffer ready: mode=%s, submode=%s, trPeriod=%d, sampleRate=%d, freq=%.1f, volume=%.2f, %s",
                FT8Common.modeToString(currentMode),
                FT8Common.getQ65SubmodeLabel(GeneralVariables.getQ65Submode()),
                GeneralVariables.getQ65TrPeriodSeconds(),
                currentSampleRate,
                primaryItem == null ? 0.0f : primaryItem.frequencyHz,
                GeneralVariables.volumePercent,
                buildBufferStats(buffer, currentSampleRate)));

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
        int slotBufferSize = currentSampleRate * currentSlotMs / 1000 * frameBytes;
        int waveBufferSize = buffer.length * frameBytes;
        int bufferSize = Math.max(slotBufferSize, waveBufferSize + frameBytes * 8);

        audioTrack = new AudioTrack(attributes, myFormat,
                bufferSize,
                AudioTrack.MODE_STATIC,
                mySession);
        if (audioTrack.getState() == AudioTrack.STATE_UNINITIALIZED) {
            Log.e(TAG, String.format(java.util.Locale.US,
                    "audio track init failed: mode=%s, format=%s, sampleRate=%d, trackState=%d, bufferBytes=%d, %s",
                    FT8Common.modeToString(currentMode),
                    GeneralVariables.audioOutput32Bit ? "Float32" : "Int16",
                    currentSampleRate,
                    audioTrack.getState(),
                    bufferSize,
                    buildBufferStats(buffer, currentSampleRate)));
            afterPlayAudio();
            return;
        }


        int writeResult;
        if (GeneralVariables.audioOutput32Bit) {
            writeResult = writeAudioTrackFully(audioTrack, buffer);
        } else {
            short[] audio_data = float2Short(buffer);
            writeResult = writeAudioTrackFully(audioTrack, audio_data);
        }

        if (buffer.length > writeResult) {
            Log.e(TAG, String.format("audio write truncated: %d -> %d", buffer.length, writeResult));
        }
        Log.d(TAG, String.format(java.util.Locale.US,
                "audio write result: mode=%s, format=%s, sampleRate=%d, trackState=%d, writeResult=%d, requestedSamples=%d",
                FT8Common.modeToString(currentMode),
                GeneralVariables.audioOutput32Bit ? "Float32" : "Int16",
                currentSampleRate,
                audioTrack.getState(),
                writeResult,
                buffer.length));


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
            audioTrack.setVolume(GeneralVariables.volumePercent);
            audioTrack.play();
            Log.d(TAG, String.format(java.util.Locale.US,
                    "audio playback started: mode=%s, playState=%d, marker=%d, volume=%.2f",
                    FT8Common.modeToString(currentMode),
                    audioTrack.getPlayState(),
                    buffer.length,
                    GeneralVariables.volumePercent));
        }
    }

    /**
     * 銆愪紭鍖栥€戞挱鏀惧畬鎴愬悗鐨勬竻鐞嗗伐浣?
     * 鏀硅繘鐐癸細绔嬪嵆閲婃斁 AudioTrack 璧勬簮锛岄伩鍏嶉暱鏃堕棿杩愯鏃惰祫婧愮疮绉?
     */
    private void afterPlayAudio() {
        int transmittedOrder = lastTransmittedFunctionOrder > 0
                ? lastTransmittedFunctionOrder
                : functionOrder;
        notifyAfterTransmit(transmittedOrder);
        ArrayList<DxpeditionFoxSlotScheduler.CompletedContact> completedContacts =
                foxSlotScheduler.markTransmitted(lastTransmitPlan);
        for (DxpeditionFoxSlotScheduler.CompletedContact contact : completedContacts) {
            doCompleteDxpeditionFoxContact(contact);
        }
        updateDxpeditionFoxSlotStatus();
        clearPendingDxpeditionMacro();

        // 銆愪紭鍖栥€戝厛閲婃斁闊抽璧勬簮鍐嶆洿鏂扮姸鎬侊紝纭繚璧勬簮鍙婃椂鍥炴敹
        if (audioTrack != null) {
            try {
                audioTrack.stop();     // 鍋滄鎾斁
                audioTrack.release();   // 閲婃斁璧勬簮
            } catch (Exception e) {
                Log.w(TAG, "Error releasing AudioTrack: " + e.getMessage());
            } finally {
                audioTrack = null;      // 娓呯┖寮曠敤
            }
        }

        updateTransmittingState(false);

        if (isExperimentalManualTxMode() && activated) {
            // Experimental chain uses one-shot manual TX: stop right after each frame.
            // 瀹為獙閾捐矾鏄竴甯т竴鍙戯紝鍙戝畬绔嬪嵆鍋滄銆?
            setActivated(false);
        }

        if (deactivateAfterManualDxpeditionMacro) {
            deactivateAfterManualDxpeditionMacro = false;
            setActivated(false);
            return;
        }

        if (transmittedOrder == 5 && activated) {
            resetToCQ();
            mutableFunctionOrder.postValue(functionOrder);
            lastTransmittedFunctionOrder = -1;
        }
    }


    private void doComplete() {
        messageEndTime = UtcTimer.getSystemTime();

        if (autoSession.isDxpeditionFox() && toCallsign != null) {
            lastFoxCompletedCallsign = normalizeCallsignToken(toCallsign.callsign);
        }

        toMaidenheadGrid = GeneralVariables.getGridByCallsign(toCallsign.callsign, databaseOpr);

        if (messageStartTime == 0) {
            messageStartTime = UtcTimer.getSystemTime();
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

    private void doCompleteDxpeditionFoxContact(DxpeditionFoxSlotScheduler.CompletedContact contact) {
        if (contact == null || contact.callsign == null || contact.callsign.length() == 0) {
            return;
        }

        lastFoxCompletedCallsign = normalizeCallsignToken(contact.callsign);
        long startTime = messageStartTime == 0 ? UtcTimer.getSystemTime() : messageStartTime;
        long endTime = UtcTimer.getSystemTime();
        String grid = GeneralVariables.getGridByCallsign(contact.callsign, databaseOpr);
        int received = contact.receivedReport != -100 ? contact.receivedReport : receivedReport;

        if (onTransmitSuccess != null) {
            onTransmitSuccess.doAfterTransmit(new QSLRecord(
                    startTime,
                    endTime,
                    GeneralVariables.myCallsign,
                    GeneralVariables.getMyMaidenhead4Grid(),
                    contact.callsign,
                    grid,
                    contact.sentReport,
                    received,
                    FT8Common.modeToString(GeneralVariables.getSignalMode()),
                    GeneralVariables.band,
                    Math.round(contact.frequencyHz)
            ));
            GeneralVariables.addQSLCallsign(contact.callsign);
            ToastMessage.show(String.format("QSO : %s , at %s",
                    contact.callsign,
                    BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)));
        }
    }

    public void setCurrentFunctionOrder(int order) {
        order = AutoSessionUiPolicy.sanitizeFunctionOrder(
                autoSession.getSessionType(),
                functionOrder,
                order
        );
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
        return AutoFlowMessageAnalyzer.callsignMatches(fromCall, toCall);
    }

    public int getFunctionSelectionIndex(int order) {
        for (int i = 0; i < functionList.size(); i++) {
            if (functionList.get(i).getFunctionOrder() == order) {
                return i;
            }
        }
        return 0;
    }

    public int getFunctionOrderAt(int index) {
        if (index < 0 || index >= functionList.size()) {
            return functionOrder;
        }
        return functionList.get(index).getFunctionOrder();
    }

    public String getAutoSessionStatusText() {
        if (isManualDxpeditionFoxEnabled()) {
            return GeneralVariables.getStringFromResource(R.string.dxpedition_fox_status)
                    + " / " + foxSlotScheduler.getStatusText();
        }
        if (toCallsign == null || !toCallsign.haveTargetCallsign()) {
            int cqQueueSize = callQueueManager.size();
            if (GeneralVariables.cqQueueEnabled && cqQueueSize > 0) {
                return String.format("CQ闃熷垪 %d", cqQueueSize);
            }
            return "";
        }
        if (autoSession.isDxpeditionHound()) {
            String status = GeneralVariables.getStringFromResource(
                    isManualDxpeditionHoundEnabled()
                            ? R.string.dxpedition_manual_arm_status
                            : isDxpeditionHoundAutoEnabled()
                            ? R.string.dxpedition_auto_status
                            : R.string.dxpedition_manual_status
            );
            if (hasPendingDxpeditionMacro()) {
                return status + " / " + GeneralVariables.getStringFromResource(R.string.dxpedition_macro_armed);
            }
            return status;
        }
        return "";
    }

    public boolean isManualDxpeditionHoundMode() {
        return isManualDxpeditionHoundEnabled();
    }

    public boolean isManualDxpeditionFoxMode() {
        return isManualDxpeditionFoxEnabled();
    }

    public int getDxpeditionFoxTxSlots() {
        return foxSlotScheduler.getMaxTxSlots();
    }

    public String getDxpeditionFoxSlotFrequencyLabel() {
        return DxpeditionFoxSlotFrequencyConfig.getModeLabel();
    }

    public String getDxpeditionFoxSlotFrequencyPreview() {
        return DxpeditionFoxSlotFrequencyConfig.buildPreview(getDxpeditionFoxTxSlots());
    }

    public void setDxpeditionFoxTxSlots(int slots) {
        int sanitized = DxpeditionFoxSlotScheduler.clampTxSlots(slots);
        GeneralVariables.dxpeditionFoxTxSlots = sanitized;
        foxSlotScheduler.setMaxTxSlots(sanitized);
        updateDxpeditionFoxSlotStatus();
    }

    public void setDxpeditionFoxSlotFrequencyConfig(boolean manual, int startHz, int stepHz) {
        DxpeditionFoxSlotFrequencyConfig.setManual(manual, startHz, stepHz);
        updateDxpeditionFoxSlotStatus();
    }

    public String getDxpeditionFoxSlotStatusText() {
        if (!isManualDxpeditionFoxEnabled()) {
            return "";
        }
        return foxSlotScheduler.getStatusText();
    }

    private void updateDxpeditionFoxSlotStatus() {
        mutableDxpeditionFoxSlotStatus.postValue(getDxpeditionFoxSlotStatusText());
    }

    public void refreshSessionModeByCurrentTarget() {
        if (toCallsign == null) {
            autoSession.resetToCq(GeneralVariables.getSignalMode(), GeneralVariables.band);
            foxSlotScheduler.clear();
            updateDxpeditionFoxSlotStatus();
            resetDxpeditionCountersForNewTarget(AutoSessionType.STANDARD, null, 6);
            syncNoReplyCount();
            generateFun();
            mutableFunctionOrder.postValue(functionOrder);
            return;
        }

        autoSession.bindTarget(
                toCallsign.callsign,
                GeneralVariables.getSignalMode(),
                GeneralVariables.band,
                resolveBoundSessionType(toCallsign)
        );
        if (!autoSession.isDxpeditionFox()) {
            foxSlotScheduler.clear();
        }
        updateDxpeditionFoxSlotStatus();
        resetDxpeditionCountersForNewTarget(autoSession.getSessionType(), toCallsign, functionOrder);
        generateFun();
        mutableFunctionOrder.postValue(functionOrder);
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
            if (AutoFlowMessageAnalyzer.isDirectedReplyToCurrentTarget(
                    ft8Message,
                    GeneralVariables.myCallsign,
                    toCallsign.callsign,
                    isDxpeditionHoundAutoEnabled())) {
                return 0;
            }
            if (AutoFlowMessageAnalyzer.callsignMatches(
                    ft8Message.getAutoReplyCallsignFrom(),
                    toCallsign.callsign)
                    || AutoFlowMessageAnalyzer.callsignMatches(
                    ft8Message.getDxpeditionFoxCallsign(),
                    toCallsign.callsign)) {
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

            int order = AutoFlowMessageAnalyzer.resolveIncomingOrder(
                    ft8Message,
                    GeneralVariables.myCallsign,
                    toCallsign.callsign,
                    isDxpeditionHoundAutoEnabled()
            );

            boolean isDirectReply = order != -1;

            if (!isDirectReply && ft8Message.getSequence() == sequential) {
                continue;
            }

            if (!isDirectReply) {
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

        autoSession.setSessionType(AutoFlowMessageAnalyzer.resolveSessionType(
                bestMessage,
                GeneralVariables.myCallsign,
                toCallsign.callsign,
                autoSession.getSessionType(),
                isDxpeditionHoundAutoEnabled()
        ));
        if (autoSession.isDxpeditionHound() && bestOrder == 2 && bestMessage.freq_hz > 0) {
            houndReplyFrequencyHz = bestMessage.freq_hz;
            houndTx3SentCount = 0;
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

    public void updateCqQueueSettings() {
        callQueueManager.configure(
                GeneralVariables.cqMaxQueueSize,
                GeneralVariables.cqRankMethod,
                GeneralVariables.cqDirectedCqPrefixes
        );
        publishCqQueue();
    }

    public int getCqQueueSize() {
        return callQueueManager.size();
    }

    public ArrayList<CqCallEntry> getCqQueueSnapshot() {
        return callQueueManager.snapshot();
    }

    public void clearCqQueue() {
        callQueueManager.clear();
        publishCqQueue();
    }

    public boolean removeCqQueueEntry(String callsign) {
        boolean removed = callQueueManager.remove(callsign);
        publishCqQueue();
        return removed;
    }

    public boolean promoteCqQueueEntry(String callsign) {
        boolean promoted = callQueueManager.promote(callsign);
        publishCqQueue();
        return promoted;
    }

    public boolean startCqQueueEntryNow(String callsign) {
        updateCqQueueSettings();
        CqCallEntry entry = callQueueManager.poll(callsign);
        publishCqQueue();
        if (!prepareCqQueueEntry(entry)) {
            return false;
        }
        setActivated(true);
        return true;
    }

    @SuppressLint("DefaultLocale")
    public String getCqQueueNowText() {
        TransmitCallsign target = toCallsign;
        if (target == null || !target.haveTargetCallsign()) {
            return "CQ";
        }
        String frequency = target.frequency > 0
                ? String.format(" %.0fHz", target.frequency)
                : "";
        return String.format("%s TX%d%s NR%d",
                target.callsign,
                functionOrder,
                frequency,
                autoSession.getNoReplyCount());
    }

    private void publishCqQueue() {
        mutableCqQueue.postValue(callQueueManager.snapshot());
    }

    private void ingestCqQueue(ArrayList<Ft8Message> messages) {
        updateCqQueueSettings();
        if (!GeneralVariables.cqQueueEnabled) {
            callQueueManager.clear();
            publishCqQueue();
            return;
        }
        if (messages == null || messages.size() == 0) {
            return;
        }
        if (callQueueManager.addCandidates(messages) > 0) {
            publishCqQueue();
        } else {
            publishCqQueue();
        }
    }

    private boolean startNextCqFromQueue() {
        if (!GeneralVariables.cqQueueEnabled || !GeneralVariables.autoCallFollow) {
            return false;
        }
        updateCqQueueSettings();
        CqCallEntry entry = null;
        while (entry == null) {
            CqCallEntry candidate = callQueueManager.pollNext();
            if (candidate == null) {
                publishCqQueue();
                return false;
            }
            if (GeneralVariables.checkIsExcludeCallsign(candidate.callsign)
                    || GeneralVariables.checkQSLCallsign(candidate.callsign)
                    || (!GeneralVariables.autoFollowCQ && !candidate.followed && !candidate.directed && !candidate.manual)) {
                continue;
            }
            entry = candidate;
        }
        publishCqQueue();
        return prepareCqQueueEntry(entry);
    }

    private boolean prepareCqQueueEntry(CqCallEntry entry) {
        if (entry == null || entry.message == null) {
            return false;
        }
        resetTargetReport();
        setTransmit(new TransmitCallsign(
                        entry.message.i3,
                        entry.message.n3,
                        entry.callsign,
                        entry.freqHz,
                        entry.sequence,
                        entry.snr),
                1,
                entry.message.getAutoReplyExtraInfo());
        return true;
    }

    private boolean startImmediateCqFromMessages(ArrayList<Ft8Message> messages) {
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

        if (startNextCqFromQueue()) {
            return true;
        }

        return startImmediateCqFromMessages(messages);
    }

    private boolean isDxpeditionFoxInitialCall(Ft8Message msg) {
        if (msg == null || msg.checkIsCQ()) {
            return false;
        }
        if (!GeneralVariables.checkIsMyCallsign(msg.getAutoReplyCallsignTo())) {
            return false;
        }
        if (!GeneralVariables.checkFun1(msg.getAutoReplyExtraInfo())) {
            return false;
        }
        if (!DxpeditionFrequencyPolicy.isHoundInitialFrequency(msg.freq_hz)) {
            return false;
        }
        String from = msg.getAutoReplyCallsignFrom();
        if (from.length() == 0 || GeneralVariables.checkIsMyCallsign(from)) {
            return false;
        }
        return !GeneralVariables.checkIsExcludeCallsign(from);
    }

    private Ft8Message pickFoxCaller(ArrayList<Ft8Message> messages) {
        Ft8Message best = null;
        int bestSequenceIndex = Integer.MIN_VALUE;
        for (Ft8Message msg : messages) {
            if (!isDxpeditionFoxInitialCall(msg)) {
                continue;
            }
            int seqIndex = msg.getFullSequenceIndex();
            if (best == null
                    || seqIndex > bestSequenceIndex
                    || (seqIndex == bestSequenceIndex && msg.utcTime > best.utcTime)) {
                best = msg;
                bestSequenceIndex = seqIndex;
            }
        }
        return best;
    }

    private Ft8Message findFoxTargetReply(ArrayList<Ft8Message> messages) {
        if (toCallsign == null || !toCallsign.haveTargetCallsign()) {
            return null;
        }
        Ft8Message best = null;
        int bestSequenceIndex = Integer.MIN_VALUE;
        for (Ft8Message msg : messages) {
            if (!GeneralVariables.checkIsMyCallsign(msg.getAutoReplyCallsignTo())) {
                continue;
            }
            if (!AutoFlowMessageAnalyzer.callsignMatches(msg.getAutoReplyCallsignFrom(), toCallsign.callsign)) {
                continue;
            }
            String extra = msg.getAutoReplyExtraInfo();
            if (!GeneralVariables.checkFun3(extra) && !GeneralVariables.checkFun4_5(extra)) {
                continue;
            }
            int seqIndex = msg.getFullSequenceIndex();
            if (best == null
                    || seqIndex > bestSequenceIndex
                    || (seqIndex == bestSequenceIndex && msg.utcTime > best.utcTime)) {
                best = msg;
                bestSequenceIndex = seqIndex;
            }
        }
        return best;
    }

    private void parseDxpeditionFoxMessages(ArrayList<Ft8Message> messages) {
        if (toCallsign == null) {
            return;
        }

        foxSlotScheduler.setMaxTxSlots(GeneralVariables.dxpeditionFoxTxSlots);
        foxSlotScheduler.setSpecialMessageEnabled(GeneralVariables.dxpeditionFoxAutoSpecialMessage);
        foxSlotScheduler.setCqOnFreeSlotEnabled(GeneralVariables.dxpeditionFoxCqOnFreeSlot);
        foxSlotScheduler.ingestMessages(messages, GeneralVariables.myCallsign);
        if (foxSlotScheduler.hasWork()) {
            TransmitCallsign primary = foxSlotScheduler.getPrimaryTransmitCallsign();
            if (primary != null) {
                toCallsign = primary;
                mutableToCallsign.postValue(toCallsign);
                sequential = (primary.sequential + 1) % 2;
                autoSession.bindTarget(
                        primary.callsign,
                        GeneralVariables.getSignalMode(),
                        GeneralVariables.band,
                        AutoSessionType.FT8_DXPEDITION_FOX
                );
                functionOrder = foxSlotScheduler.getPrimaryFunctionOrder();
                if (foxSessionStartTimeMs == 0L) {
                    foxSessionStartTimeMs = UtcTimer.getSystemTime();
                }
                generateFun();
                mutableSequential.postValue(sequential);
                mutableFunctionOrder.postValue(functionOrder);
                updateDxpeditionFoxSlotStatus();
            }
            return;
        }
        updateDxpeditionFoxSlotStatus();

        Ft8Message targetReply = findFoxTargetReply(messages);

        if (toCallsign.haveTargetCallsign() && functionOrder != 6) {
            if (targetReply != null) {
                autoSession.resetNoReplyCount();
                syncNoReplyCount();
                String extra = targetReply.getAutoReplyExtraInfo();
                if (GeneralVariables.checkFun3(extra)) {
                    receivedReport = getReportFromExtraInfo(extra);
                    receiveTargetReport = receivedReport;
                    functionOrder = 4;
                    generateFun();
                    mutableFunctionOrder.postValue(functionOrder);
                    return;
                }
                if (GeneralVariables.checkFun4_5(extra)) {
                    resetToCQ();
                    setCurrentFunctionOrder(functionOrder);
                    mutableFunctionOrder.postValue(functionOrder);
                    return;
                }
            }

            if (functionOrder == 4 && lastTransmittedFunctionOrder == 4) {
                resetToCQ();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
                return;
            }

            increaseNoReplyCountForSlot(messages);

            boolean timeout = foxCallAttempts >= 3 || foxRr73Attempts >= 3;
            if (foxSessionStartTimeMs > 0
                    && UtcTimer.getSystemTime() - foxSessionStartTimeMs > 3 * 60 * 1000L) {
                timeout = true;
            }
            if (timeout) {
                resetToCQ();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
            }
            return;
        }

        Ft8Message caller = pickFoxCaller(messages);
        if (caller == null) {
            return;
        }

        resetTargetReport();
        setTransmit(new TransmitCallsign(
                        caller.i3,
                        caller.n3,
                        caller.getAutoReplyCallsignFrom(),
                        caller.freq_hz,
                        caller.getSequence(),
                        caller.snr),
                2,
                caller.getAutoReplyExtraInfo());
        foxSessionStartTimeMs = UtcTimer.getSystemTime();
        foxCallAttempts = 0;
        foxRr73Attempts = 0;
        setCurrentFunctionOrder(functionOrder);
        mutableFunctionOrder.postValue(functionOrder);
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
        if (isExperimentalManualTxMode()) {
            return;
        }
        if (GeneralVariables.myCallsign.length() < 3) {
            return;
        }
        if (msgList != null && msgList.size() > 0) {
            lastDecodeMessageUpdateMs = UtcTimer.getSystemTime();
            updateFoxCandidatesFromMessages(msgList);
        }
        if (msgList == null || msgList.size() == 0) {
            if (isManualDxpeditionFoxEnabled()
                    && !foxSlotScheduler.hasWork()
                    && functionOrder == 4
                    && lastTransmittedFunctionOrder == 4) {
                resetToCQ();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
            }
            if (functionOrder == 5) {
                resetToCQ();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
            }
            return;
        }

        ArrayList<Ft8Message> messages = filterAutoMessages(new ArrayList<>(msgList));
        if (messages.size() == 0) {
            if (isManualDxpeditionFoxEnabled()
                    && !foxSlotScheduler.hasWork()
                    && functionOrder == 4
                    && lastTransmittedFunctionOrder == 4) {
                resetToCQ();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
            }
            if (functionOrder == 5) {
                resetToCQ();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
            }
            return;
        }

        if (isManualDxpeditionFoxEnabled()) {
            parseDxpeditionFoxMessages(messages);
            return;
        }

        ingestCqQueue(messages);

        int newOrder = checkFunctionOrdFromMessages(messages);
        if (newOrder != -1) {
            autoSession.resetNoReplyCount();
            syncNoReplyCount();
        }


        updateQSlRecordList(newOrder, toCallsign);

        boolean resetOnTargetCallOthers = (functionOrder == 4)
                && !autoSession.isDxpeditionHound()
                && (GeneralVariables.getSignalMode() == FT8Common.FT8_MODE)
                && (checkTargetCallMe(messages) > 1);

        boolean rr73AlreadySent = functionOrder == 4 && lastTransmittedFunctionOrder == 4;

        if (newOrder == 5
                || (functionOrder == 5 && newOrder == -1)
                || (rr73AlreadySent && newOrder <= 3)
                || (functionOrder == 4 &&
                (autoSession.getNoReplyCount() > GeneralVariables.noReplyLimit * 2)
                && (GeneralVariables.noReplyLimit > 0))
                || resetOnTargetCallOthers
                || (functionOrder == 4 && (autoSession.getNoReplyCount() > 20)
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

        increaseNoReplyCountForSlot(messages);

        if ((autoSession.getNoReplyCount() > GeneralVariables.noReplyLimit) && (GeneralVariables.noReplyLimit > 0)) {
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
        return AutoFlowMessageAnalyzer.isDirectedReplyToCurrentTarget(
                msg,
                GeneralVariables.myCallsign,
                toCallsign.callsign,
                isDxpeditionHoundAutoEnabled()
        );
    }

    private boolean hasCurrentSessionActivity(ArrayList<Ft8Message> messages) {
        for (Ft8Message msg : messages) {
            if (AutoFlowMessageAnalyzer.isCurrentSessionActivity(
                    msg,
                    autoSession.getTargetCallsign(),
                    autoSession.getSignalFormat(),
                    autoSession.getBand(),
                    isDxpeditionHoundAutoEnabled())) {
                return true;
            }
        }
        return false;
    }

    private int latestStrongSequenceIndex(ArrayList<Ft8Message> messages) {
        int result = Integer.MIN_VALUE;
        if (messages == null) {
            return result;
        }
        for (Ft8Message msg : messages) {
            if (msg == null || msg.isWeakSignal) {
                continue;
            }
            result = Math.max(result, msg.getFullSequenceIndex());
        }
        return result;
    }

    private void increaseNoReplyCountForSlot(ArrayList<Ft8Message> messages) {
        if (!hasCurrentSessionActivity(messages)) {
            return;
        }
        int sequenceIndex = latestStrongSequenceIndex(messages);
        if (sequenceIndex == Integer.MIN_VALUE) {
            return;
        }
        String target = normalizeCallsignToken(autoSession.getTargetCallsign());
        if (target.length() == 0 || "CQ".equals(target)) {
            return;
        }
        String sessionKey = autoSession.getSessionType()
                + "|" + autoSession.getSignalFormat()
                + "|" + autoSession.getBand()
                + "|" + target;
        if (sequenceIndex == lastNoReplySequenceIndex
                && sessionKey.equals(lastNoReplySessionKey)) {
            return;
        }
        lastNoReplySequenceIndex = sequenceIndex;
        lastNoReplySessionKey = sessionKey;
        autoSession.increaseNoReplyCount();
        syncNoReplyCount();
    }

    public boolean getNewTargetCallsign(ArrayList<Ft8Message> messages) {
        if (toCallsign == null) return false;
        if (!GeneralVariables.autoCallFollow) return false;
        if (startNextCqFromQueue()) {
            return true;
        }
        for (Ft8Message ft8Message : messages) {
            if (!ft8Message.isAutoFlowRelevant()) continue;
            if (ft8Message.signalFormat != GeneralVariables.getSignalMode()) continue;
            // Only enforce band match when decoded message carries a valid RF band.
            // 浠呭湪瑙ｇ爜娑堟伅甯︽湁鏈夋晥棰戞鏃舵墠鍋氶娈典竴鑷存€х害鏉熴€?
            if (ft8Message.band > 0 && ft8Message.band != GeneralVariables.band) continue;
            if (!ft8Message.checkIsCQ()) continue;

            if ((!ft8Message.getCallsignFrom().equals(toCallsign.callsign)
                    && (!GeneralVariables.checkQSLCallsign(ft8Message.getCallsignFrom())))) {
                functionOrder = 1;
                toCallsign.callsign = ft8Message.getCallsignFrom();
                autoSession.bindTarget(
                        toCallsign.callsign,
                        GeneralVariables.getSignalMode(),
                        GeneralVariables.band,
                        AutoSessionType.STANDARD
                );
                syncNoReplyCount();
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
            clearPendingDxpeditionMacro();
            deactivateAfterManualDxpeditionMacro = false;
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
        clearPendingDxpeditionMacro();
        deactivateAfterManualDxpeditionMacro = false;
        foxSlotScheduler.clear();
        updateDxpeditionFoxSlotStatus();
        autoSession.resetToCq(GeneralVariables.getSignalMode(), GeneralVariables.band);
        resetDxpeditionCountersForNewTarget(AutoSessionType.STANDARD, null, 6);
        syncNoReplyCount();
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
        this.freeText = freeText == null ? "" : freeText;
    }

    public String getFreeText() {
        return freeText == null ? "" : freeText;
    }

    public void setTransmitFreeText(boolean transmitFreeText) {
        this.transmitFreeText = transmitFreeText;
        if (transmitFreeText) {
            clearPendingDxpeditionMacro();
        }
        if (transmitFreeText) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.trans_free_text_mode));
        } else {
            ToastMessage.show((GeneralVariables.getStringFromResource(R.string.trans_standard_messge_mode)));
        }
    }

    private boolean isExperimentalManualTxMode() {
        return GeneralVariables.isExperimentalCodecEnabled();
    }

    private long calculateLateDecodeHoldMs() {
        int mode = GeneralVariables.getSignalMode();
        int slotMs = FT8Common.getSlotTimeMillisecond(mode);
        long nowMs = UtcTimer.getSystemTime();
        long elapsedInSlotMs = nowMs % slotMs;
        int baseStartOffsetMs = Math.max(
                GeneralVariables.pttDelay,
                FT8Common.getPreferredTxLeadInMs(mode)
        );

        int targetStartOffsetMs = Math.max(
                0,
                baseStartOffsetMs - FT8Common.getTxPipelineCompensationMs(mode)
        );

        // Late-decode override is only enabled for a short time after fresh decode updates.
        // 鍙湁鍦ㄢ€滃垰鏀跺埌鏂拌В鐮佲€濈殑鐭獥鍙ｉ噷锛屾墠鍏佽鏅氬埌瑙ｇ爜瑕嗙洊鍙戝皠鍐呭銆?
        if (lastDecodeMessageUpdateMs > 0L) {
            long sinceDecodeMs = nowMs - lastDecodeMessageUpdateMs;
            if (sinceDecodeMs >= 0
                    && sinceDecodeMs <= FT8Common.getLateDecodeRecentWindowMs(mode)) {
                int overrideOffsetMs = Math.min(
                        FT8Common.getLateDecodeOverrideWindowMs(mode),
                        FT8Common.getLateDecodeHoldCapMs(mode)
                );
                targetStartOffsetMs = Math.max(targetStartOffsetMs, overrideOffsetMs);
            }
        }

        long holdMs = targetStartOffsetMs - elapsedInSlotMs;
        return Math.max(0L, holdMs);
    }

    private static class DoTransmitRunnable implements Runnable {
        FT8TransmitSignal transmitSignal;

        public DoTransmitRunnable(FT8TransmitSignal transmitSignal) {
            this.transmitSignal = transmitSignal;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public void run() {
            if (transmitSignal.onDoTransmitted != null) {
                transmitSignal.onDoTransmitted.onPrepareTransmit();
            }

            if (!transmitSignal.isExperimentalManualTxMode()) {
                transmitSignal.updateTransmittingState(true);
            }
            long holdWindowMs = transmitSignal.calculateLateDecodeHoldMs();
            if (holdWindowMs > 0L) {
                try {
                    Thread.sleep(holdWindowMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            int transmitOrder = transmitSignal.functionOrder;
            MultiSlotTransmitPlan plan;
            try {
                transmitSignal.applyDxpeditionFrequencyPolicyForOrder(transmitOrder);
                transmitSignal.updateMessageStartTimeForOrder(transmitOrder);
                plan = transmitSignal.buildTransmitPlan(transmitOrder);
                plan = transmitSignal.enforceTransportLimit(plan);
                transmitSignal.rememberTransmitPlan(plan, transmitOrder);
                transmitSignal.notifyBeforeTransmit(plan);
                transmitSignal.postTransmittingMessage(plan);
            } catch (RuntimeException e) {
                Log.e(TAG, "DoTransmitRunnable: failed to build final transmit message", e);
                transmitSignal.afterPlayAudio();
                return;
            }
            transmitSignal.playTransmitPlan(plan);
        }
    }
}

