package com.bg7yoz.ft8cn.ft8transmit;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.auto.AutoFlowMessageAnalyzer;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

public final class DxpeditionFoxSlotScheduler {
    public static final int MIN_TX_SLOTS = 1;
    public static final int MAX_TX_SLOTS = 5;

    private static final int STAGE_SEND_REPORT = 2;
    private static final int STAGE_SEND_RR73 = 4;
    private static final long STALE_SESSION_MS = 3 * 60 * 1000L;

    private final ArrayList<SlotSession> queue = new ArrayList<>();
    private final ArrayList<SlotSession> now = new ArrayList<>();

    private int maxTxSlots = MIN_TX_SLOTS;
    private boolean specialMessageEnabled = true;
    private boolean cqOnFreeSlotEnabled = true;

    public synchronized void setMaxTxSlots(int maxTxSlots) {
        this.maxTxSlots = clampTxSlots(maxTxSlots);
    }

    public synchronized int getMaxTxSlots() {
        return maxTxSlots;
    }

    public synchronized void setSpecialMessageEnabled(boolean enabled) {
        specialMessageEnabled = enabled;
    }

    public synchronized void setCqOnFreeSlotEnabled(boolean enabled) {
        cqOnFreeSlotEnabled = enabled;
    }

    public synchronized void clear() {
        queue.clear();
        now.clear();
    }

    public synchronized int getQueueSize() {
        return queue.size();
    }

    public synchronized int getNowSize() {
        return now.size();
    }

    public synchronized boolean hasWork() {
        return !queue.isEmpty() || !now.isEmpty();
    }

    public synchronized String getStatusText() {
        return "Now " + now.size() + "/" + maxTxSlots + " Queue " + queue.size();
    }

    public synchronized TransmitCallsign getPrimaryTransmitCallsign() {
        promoteQueueLocked();
        SlotSession session = getFirstTransmitReadySessionLocked();
        if (session == null) {
            return null;
        }
        TransmitCallsign callsign = new TransmitCallsign(
                FT8Common.FT8_MODE,
                1,
                0,
                session.callsign,
                session.rxFrequencyHz,
                session.sequence,
                session.report
        );
        return callsign;
    }

    public synchronized int getPrimaryFunctionOrder() {
        promoteQueueLocked();
        SlotSession session = getFirstTransmitReadySessionLocked();
        return session == null ? 6 : session.stage;
    }

    public synchronized void ingestMessages(ArrayList<Ft8Message> messages, String myCallsign) {
        if (messages == null) {
            pruneStaleSessionsLocked(UtcTimer.getSystemTime());
            promoteQueueLocked();
            return;
        }

        long nowMs = UtcTimer.getSystemTime();
        for (Ft8Message message : messages) {
            if (message == null || message.signalFormat != FT8Common.FT8_MODE || !message.isAutoFlowRelevant()) {
                continue;
            }
            if (isInitialHoundCall(message, myCallsign)) {
                addOrUpdateCallerLocked(message);
                continue;
            }
            updateSessionFromReplyLocked(message, myCallsign);
        }
        pruneStaleSessionsLocked(nowMs);
        promoteQueueLocked();
    }

    public synchronized MultiSlotTransmitPlan buildTransmitPlan(String myCallsign, int signalMode) {
        promoteQueueLocked();
        MultiSlotTransmitPlan plan = new MultiSlotTransmitPlan(signalMode);
        if (signalMode != FT8Common.FT8_MODE) {
            return plan;
        }

        ArrayList<SlotSession> used = new ArrayList<>();
        int nextSlot = 0;

        if (specialMessageEnabled && maxTxSlots > 0) {
            SlotSession rr73 = findFirstSessionByStageLocked(STAGE_SEND_RR73, used);
            SlotSession report = findFirstSessionByStageLocked(STAGE_SEND_REPORT, used);
            if (rr73 != null && report != null && !sameCallsign(rr73.callsign, report.callsign)) {
                MultiSlotTransmitItem special = buildSpecialMessageItem(rr73, report, myCallsign, nextSlot);
                if (special != null) {
                    plan.add(special);
                    used.add(rr73);
                    used.add(report);
                    nextSlot++;
                }
            }
        }

        for (SlotSession session : now) {
            if (nextSlot >= maxTxSlots) {
                break;
            }
            if (used.contains(session)) {
                continue;
            }
            MultiSlotTransmitItem item = buildNormalItem(session, nextSlot);
            if (item == null) {
                continue;
            }
            plan.add(item);
            nextSlot++;
        }

        if (cqOnFreeSlotEnabled) {
            while (nextSlot < maxTxSlots) {
                plan.add(buildCqItem(nextSlot));
                nextSlot++;
            }
        }
        return plan;
    }

    public synchronized ArrayList<CompletedContact> markTransmitted(MultiSlotTransmitPlan plan) {
        ArrayList<CompletedContact> completed = new ArrayList<>();
        if (plan == null || plan.isEmpty()) {
            return completed;
        }

        for (MultiSlotTransmitItem item : plan.getItems()) {
            for (String callsign : item.getReportCallsigns()) {
                SlotSession session = findSessionLocked(callsign);
                if (session != null) {
                    session.reportTxCount++;
                    session.lastTransmitTimeMs = UtcTimer.getSystemTime();
                }
            }
            for (String callsign : item.getRr73Callsigns()) {
                SlotSession session = findSessionLocked(callsign);
                if (session != null) {
                    session.rr73TxCount++;
                    completed.add(new CompletedContact(
                            session.callsign,
                            session.report,
                            session.receivedReport,
                            item.frequencyHz
                    ));
                    removeSessionLocked(session.callsign);
                }
            }
        }
        promoteQueueLocked();
        return completed;
    }

    private MultiSlotTransmitItem buildNormalItem(SlotSession session, int slotIndex) {
        if (session == null) {
            return null;
        }
        float frequency = DxpeditionFoxSlotFrequencyConfig.resolveSlotFrequency(slotIndex);
        Ft8Message message;
        if (session.stage == STAGE_SEND_RR73) {
            message = new Ft8Message(FT8Common.FT8_MODE, 1, 0,
                    session.callsign,
                    getMyCallsignForMessage(),
                    "RR73");
            return new MultiSlotTransmitItem(slotIndex, message, 4, frequency)
                    .addRr73Callsign(session.callsign);
        }

        message = new Ft8Message(FT8Common.FT8_MODE, 1, 0,
                session.callsign,
                getMyCallsignForMessage(),
                formatReport(session.report));
        return new MultiSlotTransmitItem(slotIndex, message, 2, frequency)
                .addReportCallsign(session.callsign);
    }

    private MultiSlotTransmitItem buildSpecialMessageItem(SlotSession rr73,
                                                          SlotSession report,
                                                          String myCallsign,
                                                          int slotIndex) {
        String my = normalizeCallsign(myCallsign);
        if (my.length() == 0) {
            return null;
        }
        String text = String.format(Locale.US,
                "%s RR73; %s <%s> %+03d",
                rr73.callsign,
                report.callsign,
                my,
                clampReport(report.report));
        String typeInfo = GenerateFT8.getPackedTypeInfo(text);
        if (!typeInfo.startsWith("0.1:")) {
            return null;
        }

        Ft8Message message = new Ft8Message(FT8Common.FT8_MODE, "CQ", my, text);
        message.setTransmitRawText(text);
        message.i3 = 0;
        message.n3 = 0;
        return new MultiSlotTransmitItem(
                slotIndex,
                message,
                4,
                DxpeditionFoxSlotFrequencyConfig.resolveSlotFrequency(slotIndex),
                true)
                .addRr73Callsign(rr73.callsign)
                .addReportCallsign(report.callsign);
    }

    private String getMyCallsignForMessage() {
        return GeneralVariables.myCallsign;
    }

    private MultiSlotTransmitItem buildCqItem(int slotIndex) {
        Ft8Message message = new Ft8Message(
                FT8Common.FT8_MODE,
                1,
                0,
                "CQ",
                getMyCallsignForMessage(),
                GeneralVariables.getMyMaidenhead4Grid()
        );
        return new MultiSlotTransmitItem(
                slotIndex,
                message,
                6,
                DxpeditionFoxSlotFrequencyConfig.resolveSlotFrequency(slotIndex)
        );
    }

    private void addOrUpdateCallerLocked(Ft8Message message) {
        String callsign = normalizeCallsign(message.getAutoReplyCallsignFrom());
        if (callsign.length() == 0) {
            return;
        }

        SlotSession session = findSessionLocked(callsign);
        if (session == null) {
            session = findQueuedSessionLocked(callsign);
        }
        if (session == null) {
            session = new SlotSession();
            session.callsign = callsign;
            session.createdTimeMs = UtcTimer.getSystemTime();
            queue.add(session);
        }

        session.i3 = message.i3;
        session.n3 = message.n3;
        session.rxFrequencyHz = message.freq_hz;
        session.sequence = message.getSequence();
        session.report = clampReport(message.snr);
        session.lastHeardTimeMs = UtcTimer.getSystemTime();
    }

    private void updateSessionFromReplyLocked(Ft8Message message, String myCallsign) {
        if (!AutoFlowMessageAnalyzer.isMyCallsign(myCallsign, message.getAutoReplyCallsignTo())) {
            return;
        }
        String callsign = normalizeCallsign(message.getAutoReplyCallsignFrom());
        SlotSession session = findSessionLocked(callsign);
        if (session == null) {
            return;
        }

        String extra = message.getAutoReplyExtraInfo();
        if (GeneralVariables.checkFun3(extra)) {
            session.stage = STAGE_SEND_RR73;
            session.receivedReport = parseReport(extra, message.report);
            session.lastHeardTimeMs = UtcTimer.getSystemTime();
            return;
        }
        if (GeneralVariables.checkFun4_5(extra)) {
            removeSessionLocked(callsign);
        }
    }

    private boolean isInitialHoundCall(Ft8Message message, String myCallsign) {
        if (message.checkIsCQ()) {
            return false;
        }
        if (!AutoFlowMessageAnalyzer.isMyCallsign(myCallsign, message.getAutoReplyCallsignTo())) {
            return false;
        }
        if (!GeneralVariables.checkFun1(message.getAutoReplyExtraInfo())) {
            return false;
        }
        if (!DxpeditionFrequencyPolicy.isHoundInitialFrequency(message.freq_hz)) {
            return false;
        }
        String from = normalizeCallsign(message.getAutoReplyCallsignFrom());
        return from.length() > 0
                && !AutoFlowMessageAnalyzer.isMyCallsign(myCallsign, from)
                && !GeneralVariables.checkIsExcludeCallsign(from);
    }

    private SlotSession getFirstTransmitReadySessionLocked() {
        for (SlotSession session : now) {
            if (session.stage == STAGE_SEND_RR73 || session.stage == STAGE_SEND_REPORT) {
                return session;
            }
        }
        return null;
    }

    private SlotSession findFirstSessionByStageLocked(int stage, ArrayList<SlotSession> used) {
        for (SlotSession session : now) {
            if (used.contains(session)) {
                continue;
            }
            if (session.stage == stage) {
                return session;
            }
        }
        return null;
    }

    private void promoteQueueLocked() {
        while (now.size() < maxTxSlots && !queue.isEmpty()) {
            now.add(queue.remove(0));
        }
    }

    private void pruneStaleSessionsLocked(long nowMs) {
        pruneListLocked(now, nowMs);
        pruneListLocked(queue, nowMs);
    }

    private void pruneListLocked(ArrayList<SlotSession> sessions, long nowMs) {
        Iterator<SlotSession> iterator = sessions.iterator();
        while (iterator.hasNext()) {
            SlotSession session = iterator.next();
            if (session.lastHeardTimeMs > 0 && nowMs - session.lastHeardTimeMs > STALE_SESSION_MS) {
                iterator.remove();
            }
            if (session.stage == STAGE_SEND_REPORT && session.reportTxCount >= 3) {
                iterator.remove();
            }
        }
    }

    private SlotSession findSessionLocked(String callsign) {
        String normalized = normalizeCallsign(callsign);
        for (SlotSession session : now) {
            if (sameCallsign(session.callsign, normalized)) {
                return session;
            }
        }
        return null;
    }

    private SlotSession findQueuedSessionLocked(String callsign) {
        String normalized = normalizeCallsign(callsign);
        for (SlotSession session : queue) {
            if (sameCallsign(session.callsign, normalized)) {
                return session;
            }
        }
        return null;
    }

    private void removeSessionLocked(String callsign) {
        removeSessionFromListLocked(now, callsign);
        removeSessionFromListLocked(queue, callsign);
    }

    private void removeSessionFromListLocked(ArrayList<SlotSession> sessions, String callsign) {
        Iterator<SlotSession> iterator = sessions.iterator();
        while (iterator.hasNext()) {
            SlotSession session = iterator.next();
            if (sameCallsign(session.callsign, callsign)) {
                iterator.remove();
            }
        }
    }

    private boolean sameCallsign(String left, String right) {
        return AutoFlowMessageAnalyzer.callsignMatches(left, right);
    }

    private String normalizeCallsign(String callsign) {
        if (callsign == null) {
            return "";
        }
        return callsign.trim().toUpperCase(Locale.US)
                .replace("<", "")
                .replace(">", "");
    }

    private String formatReport(int report) {
        return String.format(Locale.US, "%+d", clampReport(report));
    }

    private int clampReport(int report) {
        if (report < -30) {
            return -30;
        }
        if (report > 32) {
            return 32;
        }
        return report;
    }

    private int parseReport(String extra, int fallback) {
        if (extra == null) {
            return fallback;
        }
        String value = extra.trim().toUpperCase(Locale.US);
        if (value.startsWith("R")) {
            value = value.substring(1).trim();
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static int clampTxSlots(int value) {
        if (value < MIN_TX_SLOTS) {
            return MIN_TX_SLOTS;
        }
        if (value > MAX_TX_SLOTS) {
            return MAX_TX_SLOTS;
        }
        return value;
    }

    public static final class CompletedContact {
        public final String callsign;
        public final int sentReport;
        public final int receivedReport;
        public final float frequencyHz;

        CompletedContact(String callsign, int sentReport, int receivedReport, float frequencyHz) {
            this.callsign = callsign;
            this.sentReport = sentReport;
            this.receivedReport = receivedReport;
            this.frequencyHz = frequencyHz;
        }
    }

    private static final class SlotSession {
        String callsign;
        int i3 = 1;
        int n3 = 0;
        int sequence = 0;
        int report = -1;
        int receivedReport = -100;
        int stage = STAGE_SEND_REPORT;
        int reportTxCount = 0;
        int rr73TxCount = 0;
        float rxFrequencyHz = 0f;
        long createdTimeMs = 0L;
        long lastHeardTimeMs = 0L;
        long lastTransmitTimeMs = 0L;
    }
}

