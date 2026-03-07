package com.bg7yoz.ft8cn.pskreporter;

import com.bg7yoz.ft8cn.GeneralVariables;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * PSKReporterPacketBuilder
 *
 * PSKReporter IPFIX 二进制报文构造器。
 *
 * 当前实现策略：
 * 1. Receiver record 固定使用带 antennaInformation 的模板（template id 0x9992, field count 4）
 * 2. Sender record 固定使用带 senderLocator、不带 sNR/iMD 的模板（template id 0x9993, field count 6）
 * 3. 不在解码线程内做任何阻塞操作；这里只负责纯内存拼包
 *
 * 说明：
 * - 如果 receiver 的 antennaInfo 为空，仍发送 0 长度字符串字段，模板保持稳定。
 * - 如果 senderGrid 为空，则该条记录会被跳过；这与当前 manager 的过滤逻辑一致。
 */
public class PSKReporterPacketBuilder {

    // IPFIX header
    private static final int IPFIX_VERSION = 0x000A;

    // Set IDs
    private static final int TEMPLATE_SET_ID_SENDER = 0x0002;
    private static final int TEMPLATE_SET_ID_RECEIVER = 0x0003;

    // Data set IDs / linkage values
    private static final int RECEIVER_DATA_SET_ID = 0x9992;
    private static final int SENDER_DATA_SET_ID = 0x9993;

    // Enterprise number 30351 = 0x768F
    private static final int ENTERPRISE_NUMBER = 30351;

    // Field IDs (enterprise specific unless noted)
    private static final int FIELD_SENDER_CALLSIGN = 0x8001;
    private static final int FIELD_RECEIVER_CALLSIGN = 0x8002;
    private static final int FIELD_SENDER_LOCATOR = 0x8003;
    private static final int FIELD_RECEIVER_LOCATOR = 0x8004;
    private static final int FIELD_FREQUENCY = 0x8005;
    private static final int FIELD_DECODER_SOFTWARE = 0x8008;
    private static final int FIELD_ANTENNA_INFORMATION = 0x8009;
    private static final int FIELD_MODE = 0x800A;
    private static final int FIELD_INFORMATION_SOURCE = 0x800B;
    private static final int FIELD_FLOW_START_SECONDS = 0x0096; // standard field 150

    // Sender informationSource
    private static final int INFORMATION_SOURCE_AUTOMATIC = 0x01;

    /**
     * 接收端本地信息，保留给调试/日志使用。
     */
    public String buildLocalInformation(PSKReporterSpot spot) {
        if (spot == null) {
            return "";
        }
        return "station_callsign," + safeUpper(spot.getReceiverCallsign())
                + ",my_gridsquare," + safeUpper(spot.getReceiverGrid())
                + ",programid,FT8CN"
                + ",programversion," + safe(GeneralVariables.VERSION)
                + ",my_antenna," + safe(spot.getAntennaInfo());
    }

    /**
     * 远端 spot 信息，保留给调试/日志使用。
     */
    public String buildRemoteInformation(PSKReporterSpot spot) {
        if (spot == null) {
            return "";
        }
        return "call," + safeUpper(spot.getSenderCallsign())
                + ",gridsquare," + safeUpper(spot.getSenderGrid())
                + ",mode," + safeUpper(spot.getMode())
                + ",freq," + spot.getFrequencyHz()
                + ",flowStartSeconds," + toUnixSeconds(spot.getUtcTime());
    }

    /**
     * 真正的 IPFIX packet builder。
     */
    public byte[] buildPacket(PSKReporterConfig config,
                              ArrayList<PSKReporterSpot> batch,
                              boolean includeTemplates,
                              int exportTimeSeconds,
                              int sequenceNumber,
                              int observationDomainId) {

        if (config == null || batch == null || batch.isEmpty()) {
            return new byte[0];
        }

        // 先过滤有效记录
        ArrayList<PSKReporterSpot> valid = new ArrayList<>();
        for (PSKReporterSpot spot : batch) {
            if (spot == null || !spot.isValidSpot()) {
                continue;
            }
            if (safeUpper(spot.getSenderGrid()).length() < 4) {
                continue;
            }
            valid.add(spot);
        }

        if (valid.isEmpty()) {
            return new byte[0];
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();

        if (includeTemplates) {
            writeReceiverTemplateSet(body);
            writeSenderTemplateSet(body);
        }

        writeReceiverDataSet(body, config, valid.get(0));
        writeSenderDataSet(body, valid);

        byte[] bodyBytes = body.toByteArray();

        ByteArrayOutputStream packet = new ByteArrayOutputStream();

        // IPFIX header: 16 bytes
        writeU16(packet, IPFIX_VERSION);
        writeU16(packet, 16 + bodyBytes.length); // total length
        writeU32(packet, exportTimeSeconds);
        writeU32(packet, sequenceNumber);
        writeU32(packet, observationDomainId);

        packet.write(bodyBytes, 0, bodyBytes.length);

        return packet.toByteArray();
    }

    /**
     * Receiver template set (set id 3)
     * 官方 4 字段版本：
     * receiverCallsign, receiverLocator, decoderSoftware, antennaInformation
     */
    private void writeReceiverTemplateSet(ByteArrayOutputStream out) {
        ByteArrayOutputStream set = new ByteArrayOutputStream();

        // template header
        writeU16(set, RECEIVER_DATA_SET_ID); // template id / linkage value 0x9992
        writeU16(set, 4);                    // field count
        writeU16(set, 1);                    // scope field count (官方示例固定为 1)

        // receiverCallsign 30351.2 variable length
        writeEnterpriseField(set, FIELD_RECEIVER_CALLSIGN, 0xFFFF);
        // receiverLocator 30351.4 variable length
        writeEnterpriseField(set, FIELD_RECEIVER_LOCATOR, 0xFFFF);
        // decoderSoftware 30351.8 variable length
        writeEnterpriseField(set, FIELD_DECODER_SOFTWARE, 0xFFFF);
        // antennaInformation 30351.9 variable length
        writeEnterpriseField(set, FIELD_ANTENNA_INFORMATION, 0xFFFF);

        byte[] setBytes = set.toByteArray();

        writeU16(out, TEMPLATE_SET_ID_RECEIVER);
        writeU16(out, 4 + setBytes.length);
        out.write(setBytes, 0, setBytes.length);
    }

    /**
     * Sender template set (set id 2)
     * 采用官方 template 6：
     * senderCallsign, frequency, mode, informationSource(1 byte), senderLocator, flowStartSeconds
     */
    private void writeSenderTemplateSet(ByteArrayOutputStream out) {
        ByteArrayOutputStream set = new ByteArrayOutputStream();

        writeU16(set, SENDER_DATA_SET_ID); // template id / linkage value 0x9993
        writeU16(set, 6);                  // field count

        // senderCallsign 30351.1 variable length
        writeEnterpriseField(set, FIELD_SENDER_CALLSIGN, 0xFFFF);
        // frequency 30351.5 4 bytes
        writeEnterpriseField(set, FIELD_FREQUENCY, 0x0004);
        // mode 30351.10 variable length
        writeEnterpriseField(set, FIELD_MODE, 0xFFFF);
        // informationSource 30351.11 1 byte
        writeEnterpriseField(set, FIELD_INFORMATION_SOURCE, 0x0001);
        // senderLocator 30351.3 variable length
        writeEnterpriseField(set, FIELD_SENDER_LOCATOR, 0xFFFF);
        // flowStartSeconds 150 4 bytes, standard field
        writeU16(set, FIELD_FLOW_START_SECONDS);
        writeU16(set, 0x0004);

        byte[] setBytes = set.toByteArray();

        writeU16(out, TEMPLATE_SET_ID_SENDER);
        writeU16(out, 4 + setBytes.length);
        out.write(setBytes, 0, setBytes.length);
    }

    /**
     * Receiver data set (set id 0x9992)
     *
     * 数据格式：
     *   set header (4 bytes)
     *   receiverCallsign varString
     *   receiverLocator varString
     *   decoderSoftware varString
     *   antennaInformation varString
     *   padding to 4-byte boundary
     */
    private void writeReceiverDataSet(ByteArrayOutputStream out,
                                      PSKReporterConfig config,
                                      PSKReporterSpot spot) {

        ByteArrayOutputStream set = new ByteArrayOutputStream();

        writeVarString(set, safeUpper(config.receiverCallsign));
        writeVarString(set, safeUpper(config.receiverGrid));
        writeVarString(set, buildDecoderSoftware(config));
        writeVarString(set, safe(config.antennaInfo));

        padTo4(set);

        byte[] data = set.toByteArray();

        writeU16(out, RECEIVER_DATA_SET_ID);
        writeU16(out, 4 + data.length);
        out.write(data, 0, data.length);
    }

    /**
     * Sender data set (set id 0x9993)
     *
     * 数据格式按 template 6 顺序：
     *   senderCallsign varString
     *   frequency uint32
     *   mode varString
     *   informationSource uint8
     *   senderLocator varString
     *   flowStartSeconds uint32
     *
     * sender records 之间不 padding，只在整个 set 末尾 padding 到 4-byte。
     */
    private void writeSenderDataSet(ByteArrayOutputStream out,
                                    ArrayList<PSKReporterSpot> spots) {

        ByteArrayOutputStream set = new ByteArrayOutputStream();

        for (PSKReporterSpot spot : spots) {
            if (spot == null || !spot.isValidSpot()) {
                continue;
            }

            String sender = safeUpper(spot.getSenderCallsign());
            String locator = safeUpper(spot.getSenderGrid());
            String mode = safeUpper(spot.getMode());

            if (sender.length() == 0 || locator.length() < 4 || mode.length() == 0) {
                continue;
            }

            writeVarString(set, sender);
            writeU32(set, safeFrequencyTo32(spot.getFrequencyHz()));
            writeVarString(set, mode);
            writeU8(set, INFORMATION_SOURCE_AUTOMATIC);
            writeVarString(set, locator);
            writeU32(set, toUnixSeconds(spot.getUtcTime()));
        }

        padTo4(set);

        byte[] data = set.toByteArray();

        writeU16(out, SENDER_DATA_SET_ID);
        writeU16(out, 4 + data.length);
        out.write(data, 0, data.length);
    }

    private void writeEnterpriseField(ByteArrayOutputStream out, int fieldId, int fieldLength) {
        writeU16(out, fieldId);
        writeU16(out, fieldLength);
        writeU32(out, ENTERPRISE_NUMBER);
    }

    /**
     * 按 pskreporter cookie-cutter 规则：
     * 字符串采用 1 字节长度 + UTF-8。
     * 官方页面明确说每个字段长度不超过 254。超过则截断。:contentReference[oaicite:4]{index=4}
     */
    private void writeVarString(ByteArrayOutputStream out, String value) {
        byte[] raw = safe(value).getBytes(StandardCharsets.UTF_8);
        int len = Math.min(raw.length, 254);
        writeU8(out, len);
        out.write(raw, 0, len);
    }

    private void padTo4(ByteArrayOutputStream out) {
        int pad = (4 - (out.size() % 4)) % 4;
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
    }

    private String buildDecoderSoftware(PSKReporterConfig config) {
        String pn = safe(config.programName);
        String pv = safe(config.programVersion);
        if (pv.length() == 0) {
            return pn;
        }
        return pn + " " + pv;
    }

    private int toUnixSeconds(long utcTimeMs) {
        return (int) Math.max(0L, utcTimeMs / 1000L);
    }

    private long safeFrequencyTo32(long hz) {
        if (hz < 0) {
            return 0;
        }
        if (hz > 0xFFFFFFFFL) {
            return 0xFFFFFFFFL;
        }
        return hz;
    }

    private void writeU8(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
    }

    private void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private void writeU32(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replace(",", " ");
    }

    private String safeUpper(String s) {
        return safe(s).toUpperCase(Locale.US);
    }
}