package com.bg7yoz.ft8cn.pskreporter;

public class PSKReporterSpot {
    /**
     * 对方呼号（被接收方 / 发送方）
     */
    public String senderCallsign = "";

    /**
     * 对方网格
     */
    public String senderGrid = "";

    /**
     * 我方呼号（接收站）
     */
    public String receiverCallsign = "";

    /**
     * 我方网格
     */
    public String receiverGrid = "";

    /**
     * 实际射频频率，单位 Hz
     * 例如 14074000
     */
    public long frequencyHz = 0L;

    /**
     * 模式字符串，例如 FT8 / FT4
     */
    public String mode = "";

    /**
     * 信噪比
     */
    public int snr = 0;

    /**
     * UTC 时间戳，沿用 Ft8Message.utcTime 的毫秒时间
     */
    public long utcTime = 0L;

    /**
     * 可选：天线描述
     */
    public String antennaInfo = "";

    public PSKReporterSpot() {
    }

    public String getSenderCallsign() {
        return senderCallsign;
    }

    public void setSenderCallsign(String senderCallsign) {
        this.senderCallsign = safeUpper(senderCallsign);
    }

    public String getSenderGrid() {
        return senderGrid;
    }

    public void setSenderGrid(String senderGrid) {
        this.senderGrid = safeUpper(senderGrid);
    }

    public String getReceiverCallsign() {
        return receiverCallsign;
    }

    public void setReceiverCallsign(String receiverCallsign) {
        this.receiverCallsign = safeUpper(receiverCallsign);
    }

    public String getReceiverGrid() {
        return receiverGrid;
    }

    public void setReceiverGrid(String receiverGrid) {
        this.receiverGrid = safeUpper(receiverGrid);
    }

    public long getFrequencyHz() {
        return frequencyHz;
    }

    public void setFrequencyHz(long frequencyHz) {
        this.frequencyHz = frequencyHz;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = safeUpper(mode);
    }

    public int getSnr() {
        return snr;
    }

    public void setSnr(int snr) {
        this.snr = snr;
    }

    public long getUtcTime() {
        return utcTime;
    }

    public void setUtcTime(long utcTime) {
        this.utcTime = utcTime;
    }

    public String getAntennaInfo() {
        return antennaInfo;
    }

    public void setAntennaInfo(String antennaInfo) {
        this.antennaInfo = antennaInfo == null ? "" : antennaInfo.trim();
    }

    /**
     * 用于本地去重：
     * 同一呼号 + 网格 + 模式 + 频率 视为同一类上报对象
     */
    public String dedupKey() {
        return safeUpper(senderCallsign) + "|"
                + safeUpper(senderGrid) + "|"
                + safeUpper(mode) + "|"
                + frequencyHz;
    }

    /**
     * 检查这条 spot 是否具备最基本的上报条件
     */
    public boolean isValidSpot() {
        return senderCallsign.length() > 0
                && senderGrid.length() >= 4
                && receiverCallsign.length() > 0
                && receiverGrid.length() >= 4
                && frequencyHz > 0
                && mode.length() > 0
                && utcTime > 0;
    }

    private String safeUpper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    @Override
    public String toString() {
        return "PSKReporterSpot{" +
                "senderCallsign='" + senderCallsign + '\'' +
                ", senderGrid='" + senderGrid + '\'' +
                ", receiverCallsign='" + receiverCallsign + '\'' +
                ", receiverGrid='" + receiverGrid + '\'' +
                ", frequencyHz=" + frequencyHz +
                ", mode='" + mode + '\'' +
                ", snr=" + snr +
                ", utcTime=" + utcTime +
                '}';
    }
}