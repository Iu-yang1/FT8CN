package com.bg7yoz.ft8cn;
/**
 * Ft8Message类是用于展现FT8/FT4信号的解析结果。
 *
 * @author BG7YOZ
 * @date 2022.5.6
 */

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
import com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.google.android.gms.maps.model.LatLng;

import java.text.SimpleDateFormat;

public class Ft8Message {
    private static final String TAG = "Ft8Message";

    public int i3 = 0;
    public int n3 = 0;

    /**
     * 当前消息信号格式
     * FT8Common.FT8_MODE / FT8Common.FT4_MODE
     */
    public int signalFormat = FT8Common.FT8_MODE;

    public long utcTime;//UTC时间
    public boolean isValid;//是否是有效信息
    public int snr = 0;//信噪比
    public float time_sec = 0;//时间偏移(秒)
    public float freq_hz = 0;//频率
    public int score = 0;//得分
    public int messageHash;//消息的哈希

    public String callsignFrom = null;//发起呼叫的呼号
    public String callsignTo = null;//接收呼叫的呼号

    public String modifier = null;//目标呼号的修饰符 如CQ POTA BG7YOZ OL50中的POTA

    public String extraInfo = null;
    public String maidenGrid = null;

    public String rtty_state = null;//RTTY RU(i3=3类型)的地区名，两位字母如：CA、AL
    public int r_flag = 0;//RTTY RU,EU VHF(i3=3,i3=5类型)的R标志
    public int rtty_tu;//RTTY RU(i3=3类型)的TU;标志
    public int eu_serial;//EU VHF i3=5中的序列号
    public String arrl_rac;//Field day 消息，Arrl rac
    public String arrl_class;//Field day 发射级别
    public String dx_call_to2;//DXpediton 消息中第二个接收的呼号

    public int report = -100;//当-100时，意味着没有信号报告
    public long callFromHash10 = 0;
    public long callFromHash12 = 0;
    public long callFromHash22 = 0;
    public long callToHash10 = 0;
    public long callToHash12 = 0;
    public long callToHash22 = 0;

    public long band;//载波频率

    public String fromWhere = null;//用于显示地址
    public String toWhere = null;//用于显示地址

    public boolean isQSL_Callsign = false;//是不是通联过的呼号

    public static MessageHashMap hashList = new MessageHashMap();

    public boolean fromDxcc = false;
    public boolean fromItu = false;
    public boolean fromCq = false;
    public boolean toDxcc = false;
    public boolean toItu = false;
    public boolean toCq = false;

    public LatLng fromLatLng = null;
    public LatLng toLatLng = null;

    public boolean isWeakSignal = false;

    @NonNull
    @SuppressLint({"SimpleDateFormat", "DefaultLocale"})
    @Override
    public String toString() {
        return String.format("%s %d %+4.2f %4.0f [%s] ~ %s Hash : %#06X",
                new SimpleDateFormat("HHmmss").format(utcTime),
                snr,
                time_sec,
                freq_hz,
                FT8Common.modeToString(signalFormat),
                getMessageText(),
                messageHash);
    }

    /**
     * 创建一个解码消息对象，要确定信号的格式。
     *
     * @param signalFormat FT8/FT4 模式
     */
    public Ft8Message(int signalFormat) {
        this.signalFormat = signalFormat;
    }

    /**
     * 创建一个默认 FT8 模式的消息对象
     */
    public Ft8Message() {
        this.signalFormat = FT8Common.FT8_MODE;
    }

    /**
     * 创建一条发送消息对象，默认使用当前全局模式
     */
    public Ft8Message(String callTo, String callFrom, String extraInfo) {
        this.signalFormat = GeneralVariables.getSignalMode();
        this.callsignTo = callTo == null ? "" : callTo.toUpperCase();
        this.callsignFrom = callFrom == null ? "" : callFrom.toUpperCase();
        this.extraInfo = extraInfo == null ? "" : extraInfo.toUpperCase();
        this.utcTime = UtcTimer.getSystemTime();
    }

    /**
     * 创建指定模式的发送消息对象
     */
    public Ft8Message(int signalFormat, String callTo, String callFrom, String extraInfo) {
        this.signalFormat = signalFormat;
        this.callsignTo = callTo == null ? "" : callTo.toUpperCase();
        this.callsignFrom = callFrom == null ? "" : callFrom.toUpperCase();
        this.extraInfo = extraInfo == null ? "" : extraInfo.toUpperCase();
        this.utcTime = UtcTimer.getSystemTime();
    }

    public Ft8Message(int i3, int n3, String callTo, String callFrom, String extraInfo) {
        this.signalFormat = GeneralVariables.getSignalMode();
        this.callsignTo = callTo;
        this.callsignFrom = callFrom;
        this.extraInfo = extraInfo;
        this.i3 = i3;
        this.n3 = n3;
        this.utcTime = UtcTimer.getSystemTime();//用于显示TX
    }

    public Ft8Message(int signalFormat, int i3, int n3, String callTo, String callFrom, String extraInfo) {
        this.signalFormat = signalFormat;
        this.callsignTo = callTo;
        this.callsignFrom = callFrom;
        this.extraInfo = extraInfo;
        this.i3 = i3;
        this.n3 = n3;
        this.utcTime = UtcTimer.getSystemTime();//用于显示TX
    }

    /**
     * 创建一个解码消息对象
     *
     * @param message 如果message不为null，则创建一个与message内容一样的解码消息对象
     */
    public Ft8Message(Ft8Message message) {
        if (message != null) {
            signalFormat = message.signalFormat;
            utcTime = message.utcTime;
            isValid = message.isValid;
            snr = message.snr;
            time_sec = message.time_sec;
            freq_hz = message.freq_hz;
            score = message.score;
            band = message.band;
            isWeakSignal = message.isWeakSignal;

            messageHash = message.messageHash;

            if (message.callsignFrom != null && message.callsignFrom.equals("<...>")) {
                callsignFrom = hashList.getCallsign(
                        new long[]{message.callFromHash10, message.callFromHash12, message.callFromHash22});
            } else {
                callsignFrom = message.callsignFrom;
            }

            if (message.callsignTo != null && message.callsignTo.equals("<...>")) {
                callsignTo = hashList.getCallsign(
                        new long[]{message.callToHash10, message.callToHash12, message.callToHash22});
            } else {
                callsignTo = message.callsignTo;
            }

            if (message.i3 == 4 && message.callsignFrom != null) {
                hashList.addHash(FT8Package.getHash22(message.callsignFrom), message.callsignFrom);
                hashList.addHash(FT8Package.getHash12(message.callsignFrom), message.callsignFrom);
                hashList.addHash(FT8Package.getHash10(message.callsignFrom), message.callsignFrom);
            }

            extraInfo = message.extraInfo;
            maidenGrid = message.maidenGrid;
            modifier = message.modifier;
            report = message.report;

            callToHash10 = message.callToHash10;
            callToHash12 = message.callToHash12;
            callToHash22 = message.callToHash22;
            callFromHash10 = message.callFromHash10;
            callFromHash12 = message.callFromHash12;
            callFromHash22 = message.callFromHash22;

            i3 = message.i3;
            n3 = message.n3;

            if (callsignTo != null) {
                hashList.addHash(callToHash10, callsignTo);
                hashList.addHash(callToHash12, callsignTo);
                hashList.addHash(callToHash22, callsignTo);
            }
            if (callsignFrom != null) {
                hashList.addHash(callFromHash10, callsignFrom);
                hashList.addHash(callFromHash12, callsignFrom);
                hashList.addHash(callFromHash22, callsignFrom);
            }

            rtty_tu = message.rtty_tu;
            rtty_state = message.rtty_state;
            r_flag = message.r_flag;
            eu_serial = message.eu_serial;

            arrl_class = message.arrl_class;
            arrl_rac = message.arrl_rac;
            dx_call_to2 = message.dx_call_to2;

            fromWhere = message.fromWhere;
            toWhere = message.toWhere;
            isQSL_Callsign = message.isQSL_Callsign;

            fromDxcc = message.fromDxcc;
            fromItu = message.fromItu;
            fromCq = message.fromCq;
            toDxcc = message.toDxcc;
            toItu = message.toItu;
            toCq = message.toCq;

            fromLatLng = message.fromLatLng;
            toLatLng = message.toLatLng;
        }
    }

    /**
     * 返回解码消息的所使用的频率
     *
     * @return String 为方便显示，返回值是字符串
     */
    @SuppressLint("DefaultLocale")
    public String getFreq_hz() {
        return String.format("%04.0f", freq_hz);
    }

    public String getMessageText(boolean showWeekSignal) {
        if (isWeakSignal && showWeekSignal) {
            return "*" + getMessageText();
        } else {
            return getMessageText();
        }
    }

    /**
     * 返回消息模式字符串
     */
    public String getSignalFormatString() {
        return FT8Common.modeToString(signalFormat);
    }

    /**
     * 兼容其他类调用的模式字符串接口
     */
    public String getModeStr() {
        return FT8Common.modeToString(signalFormat);
    }

    /**
     * 返回解码消息的文本内容
     *
     * @return String
     */
    @SuppressLint("DefaultLocale")
    public String getMessageText() {
        String safeCallTo = callsignTo == null ? "" : callsignTo;
        String safeCallFrom = callsignFrom == null ? "" : callsignFrom;
        String safeExtraInfo = extraInfo == null ? "" : extraInfo;

        if (i3 == 0 && n3 == 0) {//说明是自由文本
            if (safeExtraInfo.length() < 13) {
                return String.format("%-13s", safeExtraInfo.toUpperCase());
            } else {
                return safeExtraInfo.toUpperCase().substring(0, 13);
            }
        }

        if (i3 == 0 && (n3 == 3 || n3 == 4)) {//说明是野外日
            return String.format("%s %s %s%d%s %s",
                    safeCallTo,
                    safeCallFrom,
                    r_flag == 0 ? "" : "R ",
                    eu_serial,
                    arrl_class == null ? "" : arrl_class,
                    arrl_rac == null ? "" : arrl_rac
            ).trim();
        }

        if (i3 == 0 && (n3 == 1)) {//说明是DXpedition
            return String.format("%s RR73; %s %s %s%d",
                    safeCallTo,
                    dx_call_to2 == null ? "" : dx_call_to2,
                    hashList.getCallsign(new long[]{callFromHash10}),
                    report > 0 ? "+" : "-",
                    Math.abs(report)
            ).trim();
        }

        if (i3 == 3) {//说明是RTTY RU消息
            return String.format("%s%s %s %s%d %s",
                    rtty_tu == 0 ? "" : "TU; ",
                    safeCallTo,
                    safeCallFrom,
                    r_flag == 0 ? "" : "R ",
                    report,
                    rtty_state == null ? "" : rtty_state
            ).trim();
        }

        if (i3 == 5) {//说明是EU VHF
            return String.format("%s %s %s%d%04d %s",
                    safeCallTo,
                    safeCallFrom,
                    r_flag == 0 ? "" : "R ",
                    report,
                    eu_serial,
                    maidenGrid == null ? "" : maidenGrid
            ).trim();
        }

        if (modifier != null && checkIsCQ()) {//修饰符
            if (modifier.matches("[0-9]{3}|[A-Z]{1,4}")) {
                return String.format("%s %s %s %s",
                        safeCallTo,
                        modifier,
                        safeCallFrom,
                        safeExtraInfo).trim();
            }
        }

        return String.format("%s %s %s", safeCallTo, safeCallFrom, safeExtraInfo).trim();
    }

    /**
     * 返回消息的延迟时间
     *
     * @return String 为方便显示，返回值是字符串。
     */
    @SuppressLint("DefaultLocale")
    public String getDt() {
        return String.format("%.1f", time_sec);
    }

    /**
     * 返回解码消息的信噪比dB值
     *
     * @return String 为方便显示，返回值是字符串
     */
    public String getdB() {
        return String.valueOf(snr);
    }

    /**
     * 当前消息对应的周期长度（0.1秒单位）
     */
    public int getSlotTimeM() {
        return FT8Common.getSlotTimeM(signalFormat);
    }

    /**
     * 当前消息对应的周期长度（毫秒）
     */
    public int getSlotTimeMillisecond() {
        return FT8Common.getSlotTimeMillisecond(signalFormat);
    }

    /**
     * 检查消息处于奇数还是偶数序列。
     *
     * @return boolean 处于偶数序列true
     */
    public boolean isEvenSequence() {
        return UtcTimer.sequential(utcTime, getSlotTimeM()) == 0;
    }

    /**
     * 显示当前消息处于哪一个时间序列。
     *
     * @return int 以时间周期取模为结果
     */
    @SuppressLint("DefaultLocale")
    public int getSequence() {
        return UtcTimer.sequential(utcTime, getSlotTimeM());
    }

    @SuppressLint("DefaultLocale")
    public int getSequence4() {
        return UtcTimer.sequential4(utcTime, getSlotTimeM());
    }

    /**
     * 获取当前消息完整周期索引
     */
    public int getFullSequenceIndex() {
        long tick100ms = utcTime / 100L;
        return (int) (tick100ms / getSlotTimeM());
    }

    /**
     * 消息中含有mycall呼号的
     *
     * @return boolean
     */
    public boolean inMyCall() {
        return GeneralVariables.checkIsMyCallsign(this.callsignFrom == null ? "" : this.callsignFrom)
                || GeneralVariables.checkIsMyCallsign(this.callsignTo == null ? "" : this.callsignTo);
    }

    /**
     * 获取发送者的呼号
     *
     * @return String 返回呼号
     */
    public String getCallsignFrom() {
        if (callsignFrom == null) {
            return "";
        }
        return callsignFrom.replace("<", "").replace(">", "");
    }

    /**
     * 获取通联信息中的接收呼号
     *
     * @return String
     */
    public String getCallsignTo() {
        if (callsignTo == null) {
            return "";
        }
        if (callsignTo.length() < 2) {
            return "";
        }
        if (callsignTo.startsWith("CQ") || callsignTo.startsWith("DE") || callsignTo.startsWith("QRZ")) {
            return "";
        }
        return callsignTo.replace("<", "").replace(">", "");
    }

    /**
     * 从消息中获取梅登海德网格信息
     *
     * @return String，梅登海德网格，如果没有返回""。
     */
    public String getMaidenheadGrid(DatabaseOpr db) {
        if (i3 != 1 && i3 != 2) {
            return GeneralVariables.getGridByCallsign(callsignFrom, db);
        } else {
            String[] msg = getMessageText().split(" ");
            if (msg.length < 1) {
                return GeneralVariables.getGridByCallsign(callsignFrom, db);
            }
            String s = msg[msg.length - 1];
            if (MaidenheadGrid.checkMaidenhead(s)) {
                return s;
            } else {
                return GeneralVariables.getGridByCallsign(callsignFrom, db);
            }
        }
    }

    public String getToMaidenheadGrid(DatabaseOpr db) {
        if (checkIsCQ()) return "";
        return GeneralVariables.getGridByCallsign(callsignTo, db);
    }

    /**
     * 查看消息是不是CQ
     *
     * @return boolean 是CQ返回true
     */
    public boolean checkIsCQ() {
        if (callsignTo == null || callsignTo.trim().length() == 0) {
            return false;
        }
        String[] arr = callsignTo.trim().split(" ");
        if (arr.length == 0) {
            return false;
        }
        String s = arr[0];
        return s.equals("CQ") || s.equals("DE") || s.equals("QRZ");
    }

    /**
     * 查消息的类型。i3.n3。
     *
     * @return 消息类型
     */
    public String getCommandInfo() {
        return getCommandInfoByI3N3(i3, n3);
    }

    /**
     * 查消息的类型。i3.n3。
     *
     * @param i i3
     * @param n n3
     * @return 消息类型
     */
    @SuppressLint("DefaultLocale")
    public static String getCommandInfoByI3N3(int i, int n) {
        String format = "%d.%d:%s";
        switch (i) {
            case 1:
            case 2:
                return String.format(format, i, 0, GeneralVariables.getStringFromResource(R.string.std_msg));
            case 5:
                return String.format(format, i, 0, "EU VHF");
            case 3:
                return String.format(format, i, 0, GeneralVariables.getStringFromResource(R.string.rtty_ru_msg));
            case 4:
                return String.format(format, i, 0, GeneralVariables.getStringFromResource(R.string.none_std_msg));
            case 0:
                switch (n) {
                    case 0:
                        return String.format(format, i, n, GeneralVariables.getStringFromResource(R.string.free_text));
                    case 1:
                        return String.format(format, i, n, GeneralVariables.getStringFromResource(R.string.dXpedition));
                    case 3:
                    case 4:
                        return String.format(format, i, n, GeneralVariables.getStringFromResource(R.string.field_day));
                    case 5:
                        return String.format(format, i, n, GeneralVariables.getStringFromResource(R.string.telemetry));
                }
        }
        return "";
    }

    // 获取发送者的传输对象
    public TransmitCallsign getFromCallTransmitCallsign() {
        TransmitCallsign result = new TransmitCallsign(
                this.i3,
                this.n3,
                this.callsignFrom,
                freq_hz,
                this.getSequence(),
                snr
        );
        result.signalFormat = this.signalFormat;
        return result;
    }

    /**
     * 获取接收对象，注意与发送者时序相反
     * 不再固定 %2，而是按当前模式周期切换
     */
    public TransmitCallsign getToCallTransmitCallsign() {
        int nextSequence = (this.getSequence() + 1) % getSlotSequenceCount();
        TransmitCallsign result;

        if (report == -100) {
            result = new TransmitCallsign(
                    this.i3,
                    this.n3,
                    this.callsignTo,
                    freq_hz,
                    nextSequence,
                    snr
            );
        } else {
            result = new TransmitCallsign(
                    this.i3,
                    this.n3,
                    this.callsignTo,
                    freq_hz,
                    nextSequence,
                    report
            );
        }

        result.signalFormat = this.signalFormat;
        return result;
    }

    /**
     * 当前模式下双时隙轮换数量
     * 目前 FT8 / FT4 都仍然是双时隙交替，只是周期长度不同
     */
    public int getSlotSequenceCount() {
        return 2;
    }

    @SuppressLint("DefaultLocale")
    public String toHtml() {
        StringBuilder result = new StringBuilder();

        result.append("<td class=\"default\" >");
        result.append(UtcTimer.getDatetimeStr(utcTime));
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(getdB());
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(String.format("%.1f", time_sec));
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(String.format("%.0f", freq_hz));
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(getMessageText());
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(BaseRigOperation.getFrequencyStr(band));
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(getSignalFormatString());
        result.append("</td>\n");

        return result.toString();
    }

    /**
     * 是否与另一条消息模式一致
     */
    public boolean isSameSignalFormat(Ft8Message other) {
        return other != null && this.signalFormat == other.signalFormat;
    }

    /**
     * 切换消息模式
     */
    public void setSignalFormat(int signalFormat) {
        this.signalFormat = signalFormat;
    }
}