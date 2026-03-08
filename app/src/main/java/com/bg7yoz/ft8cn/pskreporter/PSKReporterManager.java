package com.bg7yoz.ft8cn.pskreporter;

import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * PSKReporterManager
 *
 * 确保 PSKReporter 上传链路不会阻塞解码流程
 */
public class PSKReporterManager {
    private static final String TAG = "PSKReporterManager";
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000L;

    private final DatabaseOpr databaseOpr;
    private final ConcurrentLinkedQueue<PSKReporterSpot> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Long> dedupMap = new ConcurrentHashMap<>();

    public PSKReporterManager(DatabaseOpr databaseOpr) {
        this.databaseOpr = databaseOpr;
    }

    public void collectAndQueue(ArrayList<Ft8Message> messages) {
        // 这里只做收集，不做网络发送控制
        if (messages == null || messages.isEmpty()) {
            return;
        }

        if (GeneralVariables.myCallsign == null || GeneralVariables.myCallsign.trim().length() == 0) {
            Log.d(TAG, "skip collect: my callsign empty");
            return;
        }

        if (GeneralVariables.getMyMaidenheadGrid() == null
                || GeneralVariables.getMyMaidenheadGrid().trim().length() < 4) {
            Log.d(TAG, "skip collect: my grid empty");
            return;
        }

        cleanupDedup();

        for (Ft8Message msg : messages) {
            PSKReporterSpot spot = convertToSpot(msg);
            if (spot == null || !spot.isValidSpot()) {
                continue;
            }

            String key = spot.dedupKey();
            long now = System.currentTimeMillis();
            Long lastTime = dedupMap.get(key);
            if (lastTime != null && now - lastTime < DEDUP_WINDOW_MS) {
                continue;
            }

            dedupMap.put(key, now);
            queue.offer(spot);
            Log.d(TAG, "enqueue spot: " + spot.toString());
        }
    }

    private void cleanupDedup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = dedupMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > DEDUP_WINDOW_MS) {
                it.remove();
            }
        }
    }

    private PSKReporterSpot convertToSpot(Ft8Message msg) {
        if (!shouldReport(msg)) {
            return null;
        }

        String senderCallsign = upper(msg.getCallsignFrom());
        String senderGrid = upper(resolveGrid(msg));
        String receiverCallsign = upper(GeneralVariables.myCallsign);
        String receiverGrid = upper(GeneralVariables.getMyMaidenheadGrid());

        if (senderCallsign.length() == 0
                || senderGrid.length() < 4
                || receiverCallsign.length() == 0
                || receiverGrid.length() < 4) {
            return null;
        }

        PSKReporterSpot spot = new PSKReporterSpot();
        spot.setSenderCallsign(senderCallsign);
        spot.setSenderGrid(senderGrid);
        spot.setReceiverCallsign(receiverCallsign);
        spot.setReceiverGrid(receiverGrid);
        spot.setFrequencyHz(msg.band);
        spot.setMode(msg.getModeStr());
        spot.setSnr(msg.snr);
        spot.setUtcTime(msg.utcTime);
        spot.setAntennaInfo(GeneralVariables.pskReporterAntennaInfo);
        return spot;
    }

    /**
     * 第一版策略：
     * - 只上报 FT8/FT4 标准消息
     * - 只上报明确带 4 位网格的消息
     * - 不上报自己
     */
    private boolean shouldReport(Ft8Message msg) {
        if (msg == null || !msg.isValid) {
            return false;
        }

        if (!(msg.signalFormat == FT8Common.FT8_MODE || msg.signalFormat == FT8Common.FT4_MODE)) {
            return false;
        }

        if (!(msg.i3 == 1 || msg.i3 == 2)) {
            return false;
        }

        if (!GeneralVariables.checkFun1_6(msg.extraInfo)) {
            return false;
        }

        String sender = msg.getCallsignFrom();
        if (sender == null || sender.trim().length() == 0) {
            return false;
        }

        if (GeneralVariables.checkIsMyCallsign(sender)) {
            return false;
        }

        String grid = resolveGrid(msg);
        if (grid == null || !MaidenheadGrid.checkMaidenhead(grid)) {
            return false;
        }

        return msg.band > 0;
    }

    private String resolveGrid(Ft8Message msg) {
        if (msg.maidenGrid != null && MaidenheadGrid.checkMaidenhead(msg.maidenGrid)) {
            return msg.maidenGrid;
        }
        return msg.getMaidenheadGrid(databaseOpr);
    }

    private String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US);
    }

    public int getQueueSize() {
        return queue.size();
    }

    public ArrayList<PSKReporterSpot> drainBatch(int maxCount) {
        if (maxCount <= 0) {
            maxCount = 1;
        }

        ArrayList<PSKReporterSpot> result = new ArrayList<>();
        while (result.size() < maxCount) {
            PSKReporterSpot spot = queue.poll();
            if (spot == null) {
                break;
            }
            result.add(spot);
        }
        return result;
    }

    /**
     * 发送失败时把本次 batch 放回队尾。
     */
    public void requeue(ArrayList<PSKReporterSpot> spots) {
        if (spots == null || spots.isEmpty()) {
            return;
        }

        for (PSKReporterSpot spot : spots) {
            if (spot != null) {
                queue.offer(spot);
            }
        }
    }

    public void clearQueue() {
        queue.clear();
    }
}