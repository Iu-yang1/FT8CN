package com.bg7yoz.ft8cn.pskreporter;

import com.bg7yoz.ft8cn.GeneralVariables;

/**
 * PSKReporterConfig
 *
 * 统一收口 PSKReporter 发送参数
 */
public class PSKReporterConfig {

    /**
     * 是否启用
     */
    public boolean enabled = false;

    /**
     * 接收台呼号 / 网格
     */
    public String receiverCallsign = "";
    public String receiverGrid = "";

    /**
     * 软件信息
     */
    public String programName = "FT8CN";
    public String programVersion = "";

    /**
     * 可选天线描述
     */
    public String antennaInfo = "";

    /**
     * 目标地址
     */
    public String host = "report.pskreporter.info";
    public int port = 4739;

    /**
     * 每个包携带记录条数
     */
    public int maxRecordsPerPacket = 8;

    public PSKReporterConfig() {
    }

    public static PSKReporterConfig fromGlobals() {
        PSKReporterConfig config = new PSKReporterConfig();
        config.enabled = GeneralVariables.enablePskReporter;
        config.receiverCallsign = safe(GeneralVariables.myCallsign).toUpperCase();
        config.receiverGrid = safe(GeneralVariables.getMyMaidenheadGrid()).toUpperCase();
        config.programName = "FT8CN";
        config.programVersion = safe(GeneralVariables.VERSION);
        config.antennaInfo = safe(GeneralVariables.pskReporterAntennaInfo);
        config.host = safe(GeneralVariables.pskReporterHost);
        if (config.host.length() == 0) {
            config.host = "report.pskreporter.info";
        }
        config.port = GeneralVariables.pskReporterPort > 0
                ? GeneralVariables.pskReporterPort
                : 4739;
        config.maxRecordsPerPacket = 8;
        return config;
    }

    public boolean isValid() {
        return enabled
                && receiverCallsign.length() > 0
                && receiverGrid.length() >= 4
                && host.length() > 0
                && port > 0;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}