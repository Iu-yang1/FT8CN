package com.bg7yoz.ft8cn.auto;

import com.bg7yoz.ft8cn.FT8Common;

public class AutoSessionState {
    private AutoSessionType sessionType = AutoSessionType.STANDARD;
    private String targetCallsign = "CQ";
    private int signalFormat = FT8Common.FT8_MODE;
    private long band = -1;
    private int noReplyCount = 0;

    public AutoSessionType getSessionType() {
        return sessionType;
    }

    public void setSessionType(AutoSessionType sessionType) {
        this.sessionType = sessionType == null ? AutoSessionType.STANDARD : sessionType;
    }

    public boolean isDxpeditionHound() {
        return sessionType == AutoSessionType.FT8_DXPEDITION_HOUND;
    }

    public String getTargetCallsign() {
        return targetCallsign;
    }

    public int getSignalFormat() {
        return signalFormat;
    }

    public long getBand() {
        return band;
    }

    public int getNoReplyCount() {
        return noReplyCount;
    }

    public void resetNoReplyCount() {
        noReplyCount = 0;
    }

    public void increaseNoReplyCount() {
        noReplyCount++;
    }

    public void bindTarget(String targetCallsign, int signalFormat, long band, AutoSessionType sessionType) {
        this.targetCallsign = targetCallsign == null || targetCallsign.trim().length() == 0
                ? "CQ"
                : targetCallsign.trim().toUpperCase();
        this.signalFormat = signalFormat;
        this.band = band;
        this.sessionType = sessionType == null ? AutoSessionType.STANDARD : sessionType;
        this.noReplyCount = 0;
    }

    public void resetToCq(int signalFormat, long band) {
        bindTarget("CQ", signalFormat, band, AutoSessionType.STANDARD);
    }
}
