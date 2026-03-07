package com.bg7yoz.ft8cn.log;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.FT8Common;

import java.util.ArrayList;

/**
 * 用于计算和处理SWL消息中的QSO记录。
 * QSO的计算方法：把FT8/FT4通联的6个阶段分成3部分：
 * 1.CQ C1 grid
 * 2.C1 C2 grid
 * ---------第一部分---
 * 3.C2 C1 report
 * 4.C1 C2 r-report
 * --------第二部分----
 * 5.C2 C1 RR73(RRR)
 * 6.C1 C2 73
 * --------第三部分----
 *
 * 一个基本的QSO，必须有自己的结束点（第三部分），双方的信号报告（在第二部分判断），网格报告可有可无（第一部分）
 * 以RR73、RRR、73为检查点，符合以上第一、二部分
 *
 * 关键修改：
 * 1. FT8 / FT4 分模式处理，不混查
 * 2. 去重键加入 mode，避免 FT8/FT4 相互覆盖
 * 3. 保留原有 GeneralVariables.checkIsMyCallsign(...) 作为一侧判断逻辑
 *
 * @author BG7YOZ
 * @date 2023-03-07
 */
public class SWLQsoList {
    private static final String TAG = "SWLQsoList";

    // 通联成功的列表，防止重复
    // key1: mode + "|" + station_callsign
    // key2: call
    private final HashTable qsoList = new HashTable();

    public SWLQsoList() {
    }

    /**
     * 生成模式隔离后的key，避免同一对呼号在FT8/FT4互相覆盖
     */
    private String buildModeSideKey(Ft8Message msg) {
        return msg.getModeStr() + "|" + msg.callsignFrom;
    }

    /**
     * 检查有没有QSO消息
     *
     * @param newMessages   新的FT8/FT4消息
     * @param allMessages   全部的FT8/FT4消息
     * @param onFoundSwlQso 当有发现的回调
     */
    public void findSwlQso(ArrayList<Ft8Message> newMessages,
                           ArrayList<Ft8Message> allMessages,
                           OnFoundSwlQso onFoundSwlQso) {

        for (int i = 0; i < newMessages.size(); i++) {
            Ft8Message msg = newMessages.get(i);

            if (msg.inMyCall()) continue;// 对包含我自己的消息不处理
            if (msg.callsignFrom == null || msg.callsignTo == null) continue;

            // 结束标识：RRR / RR73 / 73
            if (GeneralVariables.checkFun4_5(msg.extraInfo)
                    && !qsoList.contains(buildModeSideKey(msg), msg.callsignTo)) {

                QSLRecord qslRecord = new QSLRecord(msg);

                // 第二部分：必须找到双方的信号报告
                if (checkPart2(allMessages, qslRecord, msg.signalFormat)) {

                    // 第一部分：网格可选，找到则更新起始时间
                    checkPart1(allMessages, qslRecord, msg.signalFormat);

                    if (onFoundSwlQso != null) {
                        qsoList.put(buildModeSideKey(msg), msg.callsignTo, true);
                        onFoundSwlQso.doFound(qslRecord);
                    }
                }
            }
        }
    }

    /**
     * 查第2部分是否存在，顺便把信号报告保存到QSLRecord中
     *
     * @param allMessages 消息列表
     * @param record      QSLRecord
     * @param signalMode  当前QSO模式，FT8/FT4 不混查
     * @return 双方信号报告是否都找到
     */
    private boolean checkPart2(ArrayList<Ft8Message> allMessages, QSLRecord record, int signalMode) {
        boolean foundFromReport = false;
        boolean foundToReport = false;
        long time_on = System.currentTimeMillis();// 先把当前时间作为最早时间

        for (int i = allMessages.size() - 1; i >= 0; i--) {
            Ft8Message msg = allMessages.get(i);

            // 只查同模式
            if (msg.signalFormat != signalMode) {
                continue;
            }

            // callsignFrom 一侧发出的报告
            if (GeneralVariables.checkIsMyCallsign(msg.callsignFrom)
                    && msg.callsignTo.equals(record.getToCallsign())
                    && !foundFromReport) {

                int report = GeneralVariables.checkFun2_3(msg.extraInfo);

                if (time_on > msg.utcTime) {
                    time_on = msg.utcTime;
                }

                if (report != -100) {
                    record.setSendReport(report);
                    foundFromReport = true;
                }
            }

            // callsignTo 一侧发出的报告
            if (msg.callsignFrom.equals(record.getToCallsign())
                    && GeneralVariables.checkIsMyCallsign(msg.callsignTo)
                    && !foundToReport) {

                int report = GeneralVariables.checkFun2_3(msg.extraInfo);

                if (time_on > msg.utcTime) {
                    time_on = msg.utcTime;
                }

                if (report != -100) {
                    record.setReceivedReport(report);
                    foundToReport = true;
                }
            }

            // 双方报告都有了，更新起始时间
            if (foundToReport && foundFromReport) {
                record.setQso_date(UtcTimer.getYYYYMMDD(time_on));
                record.setTime_on(UtcTimer.getTimeHHMMSS(time_on));
                break;
            }
        }

        return foundToReport && foundFromReport;
    }

    /**
     * 查第1部分是否存在，顺便把网格报告保存到QSLRecord中
     *
     * @param allMessages 消息列表
     * @param record      QSLRecord
     * @param signalMode  当前QSO模式，FT8/FT4 不混查
     */
    private void checkPart1(ArrayList<Ft8Message> allMessages, QSLRecord record, int signalMode) {
        boolean foundFromGrid = false;
        boolean foundToGrid = false;
        long time_on = System.currentTimeMillis();// 先把当前时间作为最早时间

        for (int i = allMessages.size() - 1; i >= 0; i--) {
            Ft8Message msg = allMessages.get(i);

            // 只查同模式
            if (msg.signalFormat != signalMode) {
                continue;
            }

            // callsignFrom 一侧的网格报告
            if (!foundFromGrid
                    && GeneralVariables.checkIsMyCallsign(msg.callsignFrom)
                    && (msg.callsignTo.equals(record.getToCallsign()) || msg.checkIsCQ())) {

                if (GeneralVariables.checkFun1_6(msg.extraInfo)) {
                    record.setMyMaidenGrid(msg.extraInfo.trim());
                    foundFromGrid = true;
                }

                if (time_on > msg.utcTime) {
                    time_on = msg.utcTime;
                }
            }

            // callsignTo 一侧的网格报告
            if (!foundToGrid
                    && msg.callsignFrom.equals(record.getToCallsign())
                    && (GeneralVariables.checkIsMyCallsign(msg.callsignTo) || msg.checkIsCQ())) {

                if (GeneralVariables.checkFun1_6(msg.extraInfo)) {
                    record.setToMaidenGrid(msg.extraInfo.trim());
                    foundToGrid = true;
                }

                if (time_on > msg.utcTime) {
                    time_on = msg.utcTime;
                }
            }

            if (foundToGrid && foundFromGrid) {
                break;
            }
        }

        // 发现任意一侧的网格报告，就把time_on尽量前推
        if (foundFromGrid || foundToGrid) {
            record.setQso_date(UtcTimer.getYYYYMMDD(time_on));
            record.setTime_on(UtcTimer.getTimeHHMMSS(time_on));
        }
    }

    public interface OnFoundSwlQso {
        void doFound(QSLRecord record);
    }
}