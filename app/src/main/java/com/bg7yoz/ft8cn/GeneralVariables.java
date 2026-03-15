package com.bg7yoz.ft8cn;
/**
 * 常用变量。
 * 1. mainContext 改为保存 ApplicationContext，降低内存泄露风险
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8transmit.QslRecordList;
import com.bg7yoz.ft8cn.html.HtmlContext;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeneralVariables {
    private static final String TAG = "GeneralVariables";

    public static String VERSION = BuildConfig.VERSION_NAME;
    public static String BUILD_DATE = BuildConfig.apkBuildTime;

    public static int MESSAGE_COUNT = 3000;
    public static boolean saveSWLMessage = false;
    public static boolean saveSWL_QSO = false;
    public static boolean enableCloudlog = false;
    public static boolean enableQRZ = false;

    /**
     * PSKreporter
     */

    /**
     * PSKReporter 配置
     * enablePskReporter: 前端设置页开关
     * pskReporterHost / Port: 默认官方接收地址
     * pskReporterFlushIntervalMs: sender tickle 周期，默认 30 秒
     */
    public static boolean enablePskReporter = false;
    public static String pskReporterHost = "report.pskreporter.info";
    public static int pskReporterPort = 4739;
    public static String pskReporterAntennaInfo = "";
    public static int pskReporterFlushIntervalMs = 15000;

    /**
     * 是否开启深度解码
     */
    public static boolean deepDecodeMode = true;

    /**
     * 当前数字模式
     * 0 = FT8
     * 1 = FT4
     */
    public static int signalMode = FT8Common.FT8_MODE;

    /**
     * 模式切换 LiveData
     */
    public static MutableLiveData<Integer> mutableSignalMode =
            new MutableLiveData<>(FT8Common.FT8_MODE);

    public static boolean audioOutput32Bit = true;
    public static int audioSampleRate = 12000;

    public static MutableLiveData<Float> mutableVolumePercent = new MutableLiveData<>();
    public static float volumePercent = 0.5f;

    public static int flexMaxRfPower = 10;
    public static int flexMaxTunePower = 10;

    /**
     * 使用 ApplicationContext，降低持有 Activity Context 的风险
     */
    private Context mainContext;
    public static CallsignDatabase callsignDatabase = null;

    public void setMainContext(Context context) {
        if (context == null) {
            mainContext = null;
        } else {
            mainContext = context.getApplicationContext();
        }
    }

    public static boolean isChina = true;
    public static boolean isTraditionalChinese = true;

    //各已经通联的分区列表
    public static final Map<String, String> dxccMap = new HashMap<>();
    public static final Map<Integer, Integer> cqMap = new HashMap<>();
    public static final Map<Integer, Integer> ituMap = new HashMap<>();

    private static final Map<String, Integer> excludedCallsigns = new HashMap<>();

    /**
     * NTP 配置与结果
     */
    public static final String[] NTP_SERVER_ITEMS = new String[]{
            "自动",
            "time.windows.com",
            "time.google.com",
            "pool.ntp.org",
            "ntp.aliyun.com",
            "cn.pool.ntp.org",
            "自定义"
    };

    public static final int NTP_SERVER_INDEX_AUTO = 0;
    public static final int NTP_SERVER_INDEX_CUSTOM = NTP_SERVER_ITEMS.length - 1;

    /**
     * 是否启用 NTP 同步
     */
    public static boolean ntpEnable = true;

    /**
     * 当前服务器选择下标
     * 0=自动
     * 1=time.windows.com
     * 2=time.google.com
     * 3=pool.ntp.org
     * 4=ntp.aliyun.com
     * 5=cn.pool.ntp.org
     * 6=自定义
     */
    public static int ntpServerIndex = NTP_SERVER_INDEX_AUTO;

    /**
     * 自定义服务器
     */
    public static String ntpCustomServer = "ntp.aliyun.com";

    /**
     * 最近一次同步实际使用的服务器
     */
    public static String lastNtpServer = "";

    /**
     * 最近一次同步结果
     */
    public static int lastNtpOffset = 0;          // 真实 offset(ms)
    public static int lastNtpAlignedOffset = 0;   // 内部对齐后 offset(ms)
    public static long lastNtpDelay = -1;         // round-trip delay(ms)
    public static long lastNtpSyncTime = 0;       // 本地同步完成时间戳

    public static MutableLiveData<Integer> mutableNtpConfigChanged = new MutableLiveData<>();

    /**
     * 添加排除的字头
     *
     * @param callsigns 呼号
     */
    public static synchronized void addExcludedCallsigns(String callsigns) {
        excludedCallsigns.clear();
        String[] s = callsigns.toUpperCase().replace(" ", ",")
                .replace("|", ",")
                .replace("，", ",").split(",");
        for (int i = 0; i < s.length; i++) {
            if (s[i].length() > 0) {
                excludedCallsigns.put(s[i], 0);
            }
        }
    }

    /**
     * 查找是否含有排除的字头
     *
     * @param callsign 呼号
     * @return 是否
     */
    public static synchronized boolean checkIsExcludeCallsign(String callsign) {
        if (callsign == null) return false;
        Iterator<String> iterator = excludedCallsigns.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (callsign.toUpperCase().indexOf(key) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取排除呼号前缀的列表
     *
     * @return 列表
     */
    public static synchronized String getExcludeCallsigns() {
        StringBuilder calls = new StringBuilder();
        Iterator<String> iterator = excludedCallsigns.keySet().iterator();
        int i = 0;
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (i == 0) {
                calls.append(key);
            } else {
                calls.append(",").append(key);
            }
            i++;
        }
        return calls.toString();
    }

    //通联记录列表，包括成功与不成功的
    public static QslRecordList qslRecordList = new QslRecordList();

    @SuppressLint("StaticFieldLeak")
    private static GeneralVariables generalVariables = null;

    public static GeneralVariables getInstance() {
        if (generalVariables == null) {
            generalVariables = new GeneralVariables();
        }
        return generalVariables;
    }

    public static Context getMainContext() {
        return GeneralVariables.getInstance().mainContext;
    }

    public static MutableLiveData<String> mutableDebugMessage = new MutableLiveData<>();
    public static int QUERY_FREQ_TIMEOUT = 2000;
    public static int START_QUERY_FREQ_DELAY = 2000;

    public static final int DEFAULT_LAUNCH_SUPERVISION = 10 * 60 * 1000;
    private static String myMaidenheadGrid = "";
    public static MutableLiveData<String> mutableMyMaidenheadGrid = new MutableLiveData<>();

    public static int connectMode = ConnectMode.USB_CABLE;

    //用于记录呼号于网格的对应关系
    public static final Map<String, String> callsignAndGrids = new ConcurrentHashMap<>();

    public static String myCallsign = "";
    public static String toModifier = "";
    private static float baseFrequency = 1000;

    public static boolean simpleCallItemMode = false;

    public static boolean swr_switch_on = true;
    public static boolean alc_switch_on = true;

    public static MutableLiveData<Float> mutableBaseFrequency = new MutableLiveData<>();

    /**
     * 当前模式对应的基础声频变化
     * 用于切换 FT8 / FT4 时通知 UI / 业务层刷新
     */
    public static MutableLiveData<Integer> mutableSignalModeChanged = new MutableLiveData<>();

    public static String cloudlogServerAddress = "";
    public static String cloudlogApiKey = "";
    public static String cloudlogStationID = "";
    public static String qrzApiKey = "";
    public static boolean synFrequency = false;
    public static int transmitDelay = 500;
    public static int pttDelay = 100;
    public static int civAddress = 0xa4;
    public static int baudRate = 19200;
    public static long band = 14074000;
    public static int serialDataBits = 8;
    public static int serialParity = 0;
    public static int serialStopBits = 1;
    public static int instructionSet = 0;
    public static int bandListIndex = -1;
    public static MutableLiveData<Integer> mutableBandChange = new MutableLiveData<>();
    public static int controlMode = ControlMode.VOX;
    public static int modelNo = 0;
    public static int launchSupervision = DEFAULT_LAUNCH_SUPERVISION;
    public static long launchSupervisionStart = UtcTimer.getSystemTime();
    public static int noReplyLimit = 0;
    public static int noReplyCount = 0;

    //下面4个参数是ICOM网络方式连接的参数
    public static String icomIp = "255.255.255.255";
    public static int icomUdpPort = 50001;
    public static String icomUserName = "ic705";
    public static String icomPassword = "";

    public static boolean autoFollowCQ = true;
    public static boolean autoCallFollow = true;
    public static ArrayList<String> QSL_Callsign_list = new ArrayList<>();
    public static ArrayList<String> QSL_Callsign_list_other_band = new ArrayList<>();

    public static final ArrayList<String> followCallsign = new ArrayList<>();

    public static ArrayList<Ft8Message> transmitMessages = new ArrayList<>();

    public static void setMyMaidenheadGrid(String grid) {
        myMaidenheadGrid = grid;
        mutableMyMaidenheadGrid.postValue(grid);
    }

    public static String getMyMaidenheadGrid() {
        return myMaidenheadGrid;
    }

    public static float getBaseFrequency() {
        return baseFrequency;
    }

    public static void setBaseFrequency(float baseFrequency) {
        mutableBaseFrequency.postValue(baseFrequency);
        GeneralVariables.baseFrequency = baseFrequency;
    }

    /**
     * 设置当前模式
     */
    public static void setSignalMode(int mode) {
        if (mode != FT8Common.FT8_MODE && mode != FT8Common.FT4_MODE) {
            return;
        }
        signalMode = mode;
        mutableSignalMode.postValue(mode);
        mutableSignalModeChanged.postValue(mode);
    }

    /**
     * 获取当前模式
     */
    public static int getSignalMode() {
        return signalMode;
    }

    /**
     * 当前模式是否 FT8
     */
    public static boolean isFt8Mode() {
        return signalMode == FT8Common.FT8_MODE;
    }

    /**
     * 当前模式是否 FT4
     */
    public static boolean isFt4Mode() {
        return signalMode == FT8Common.FT4_MODE;
    }

    /**
     * 获取当前模式的周期毫秒数
     */
    public static int getCurrentSlotTimeMillisecond() {
        return FT8Common.getSlotTimeMillisecond(signalMode);
    }

    /**
     * 获取当前模式的 UtcTimer 周期参数
     */
    public static int getCurrentSlotTimeM() {
        return FT8Common.getSlotTimeM(signalMode);
    }

    /**
     * 获取当前模式一整个周期采样点数
     */
    public static int getCurrentSamplesPerSlot() {
        return FT8Common.getSamplesPerSlot(signalMode);
    }

    /**
     * 获取当前模式立即发射窗口
     */
    public static int getCurrentImmediateTxWindowMs() {
        return FT8Common.getImmediateTxWindowMs(signalMode);
    }

    public static String getCloudlogServerAddress() {
        return cloudlogServerAddress;
    }

    public static String getCloudlogStationID() {
        return cloudlogStationID;
    }

    public static String getCloudlogServerApiKey() {
        return cloudlogApiKey;
    }

    public static String getQrzApiKey() {
        return qrzApiKey;
    }

    @SuppressLint("DefaultLocale")
    public static String getBaseFrequencyStr() {
        return String.format("%.0f", baseFrequency);
    }

    public static String getCivAddressStr() {
        return String.format("%2X", civAddress);
    }

    public static String getTransmitDelayStr() {
        return String.valueOf(transmitDelay);
    }

    /**
     * 获取当前模式字符串
     */
    public static String getSignalModeString() {
        return FT8Common.modeToString(signalMode);
    }

    public static String getBandString() {
        return BaseRigOperation.getFrequencyAllInfo(band);
    }

    /**
     * 获取当前实际应使用的 NTP 服务器
     */
    public static String getCurrentNtpServer() {
        if (!ntpEnable) {
            return "";
        }

        if (ntpServerIndex == NTP_SERVER_INDEX_CUSTOM) {
            if (ntpCustomServer != null && ntpCustomServer.trim().length() > 0) {
                return ntpCustomServer.trim();
            }
            return "pool.ntp.org";
        }

        if (ntpServerIndex <= NTP_SERVER_INDEX_AUTO) {
            if (lastNtpServer != null && lastNtpServer.trim().length() > 0) {
                return lastNtpServer.trim();
            }
            return "pool.ntp.org";
        }

        if (ntpServerIndex >= 0 && ntpServerIndex < NTP_SERVER_ITEMS.length) {
            String server = NTP_SERVER_ITEMS[ntpServerIndex];
            if (!"自动".equals(server) && !"自定义".equals(server)) {
                return server;
            }
        }

        return "pool.ntp.org";
    }

    public static void setNtpEnable(boolean enable) {
        ntpEnable = enable;
        mutableNtpConfigChanged.postValue(1);
    }

    public static void setNtpServerIndex(int index) {
        if (index < 0 || index >= NTP_SERVER_ITEMS.length) {
            index = NTP_SERVER_INDEX_AUTO;
        }
        ntpServerIndex = index;
        mutableNtpConfigChanged.postValue(1);
    }

    public static void setNtpCustomServer(String server) {
        ntpCustomServer = server == null ? "" : server.trim();
        mutableNtpConfigChanged.postValue(1);
    }

    public static void updateNtpSyncResult(String server, int realOffset, int alignedOffset,
                                           long roundTripDelay, long syncTime) {
        lastNtpServer = server == null ? "" : server;
        lastNtpOffset = realOffset;
        lastNtpAlignedOffset = alignedOffset;
        lastNtpDelay = roundTripDelay;
        lastNtpSyncTime = syncTime;
    }

    /**
     * 查有没有通联成功的呼号
     *
     * @param callsign 呼号
     * @return 是否存在
     */
    public static boolean checkQSLCallsign(String callsign) {
        return QSL_Callsign_list.contains(callsign);
    }

    /**
     * 查别的波段有没有通联成功的呼号
     *
     * @param callsign 呼号
     * @return 是否存在
     */
    public static boolean checkQSLCallsign_OtherBand(String callsign) {
        return QSL_Callsign_list_other_band.contains(callsign);
    }

    /**
     * 检查呼号中是不是含有我的呼号
     *
     * @param callsign 呼号
     * @return boolean
     */
    static public boolean checkIsMyCallsign(String callsign) {
        if (callsign == null) return false;
        if (GeneralVariables.myCallsign.length() == 0) return false;
        String temp = getShortCallsign(GeneralVariables.myCallsign);
        return callsign.contains(temp);
    }

    /**
     * 对于复合呼号，获取去掉前缀或后缀的呼号
     *
     * @return 呼号
     */
    static public String getShortCallsign(String callsign) {
        if (callsign == null) return "";
        if (callsign.contains("/")) {
            String[] temp = callsign.split("/");
            int max = 0;
            int max_index = 0;
            for (int i = 0; i < temp.length; i++) {
                if (temp[i].length() > max) {
                    max = temp[i].length();
                    max_index = i;
                }
            }
            return temp[max_index];
        } else {
            return callsign;
        }
    }

    /**
     * 查该呼号是不是在关注的呼号列表中
     *
     * @param callsign 呼号
     * @return 是否存在
     */
    public static boolean callsignInFollow(String callsign) {
        return followCallsign.contains(callsign);
    }

    /**
     * 向通联成功的呼号列表添加
     *
     * @param callsign 呼号
     */
    public static void addQSLCallsign(String callsign) {
        if (!checkQSLCallsign(callsign)) {
            QSL_Callsign_list.add(callsign);
        }
    }

    public static String getMyMaidenhead4Grid() {
        if (myMaidenheadGrid.length() > 4) {
            return myMaidenheadGrid.substring(0, 4);
        }
        return myMaidenheadGrid;
    }

    /**
     * 自动程序运行起始时间
     */
    public static void resetLaunchSupervision() {
        launchSupervisionStart = UtcTimer.getSystemTime();
    }

    /**
     * 获取自动程序的运行时长
     *
     * @return 毫秒
     */
    public static int launchSupervisionCount() {
        return (int) (UtcTimer.getSystemTime() - launchSupervisionStart);
    }

    public static boolean isLaunchSupervisionTimeout() {
        if (launchSupervision == 0) return false;
        return launchSupervisionCount() > launchSupervision;
    }

    /**
     * 从extraInfo中查消息顺序
     *
     * @param extraInfo 消息中的扩展内容
     * @return 返回消息序号
     */
    public static int checkFunOrderByExtraInfo(String extraInfo) {
        if (extraInfo == null) return -1;
        if (checkFun5(extraInfo)) return 5;
        if (checkFun4(extraInfo)) return 4;
        if (checkFun3(extraInfo)) return 3;
        if (checkFun2(extraInfo)) return 2;
        if (checkFun1(extraInfo)) return 1;
        return -1;
    }

    /**
     * 检查消息的序号，如果解析不出来，就-1
     *
     * @param message 消息
     * @return 消息序号
     */
    public static int checkFunOrder(Ft8Message message) {
        if (message.checkIsCQ()) return 6;
        return checkFunOrderByExtraInfo(message.extraInfo);
    }

    //是不是网格报告
    public static boolean checkFun1(String extraInfo) {
        if (extraInfo == null) return false;
        return (extraInfo.trim().matches("[A-Z][A-Z][0-9][0-9]") && !extraInfo.equals("RR73"))
                || (extraInfo.trim().length() == 0);
    }

    //是不是信号报告,如-10
    public static boolean checkFun2(String extraInfo) {
        if (extraInfo == null) return false;
        String value = extraInfo.trim().toUpperCase();
        if (value.equals("73")) {
            return false;
        }
        return value.matches("[+-]?[0-9]{1,2}");
    }

    //是不是带R的信号报告,如R-10
    public static boolean checkFun3(String extraInfo) {
        if (extraInfo == null) return false;
        String value = extraInfo.trim().toUpperCase();
        if (value.length() < 2) {
            return false;
        }
        if (value.startsWith("RR")) {
            return false;
        }
        return value.matches("R[+-]?[0-9]{1,2}");
    }

    //是不是RRR或RR73值
    public static boolean checkFun4(String extraInfo) {
        if (extraInfo == null) return false;
        return extraInfo.trim().equals("RR73") || extraInfo.trim().equals("RRR");
    }

    //是不是73值
    public static boolean checkFun5(String extraInfo) {
        if (extraInfo == null) return false;
        return extraInfo.trim().equals("73");
    }

    /**
     * 判断是不是信号报告，如果是，把值赋给 report
     *
     * @param extraInfo 消息扩展
     * @return 信号报告值, 没找到是-100
     */
    public static int checkFun2_3(String extraInfo) {
        if (extraInfo == null) return -100;
        if (extraInfo.equals("73")) return -100;
        if (extraInfo.matches("[R]?[+-]?[0-9]{1,2}")) {
            try {
                return Integer.parseInt(extraInfo.replace("R", ""));
            } catch (Exception e) {
                return -100;
            }
        }
        return -100;
    }

    /**
     * 判断是不是网格报告，如果是，把值赋给 report
     *
     * @param extraInfo 消息扩展
     * @return 信号报告
     */
    public static boolean checkFun1_6(String extraInfo) {
        if (extraInfo == null) return false;
        return extraInfo.trim().matches("[A-Z][A-Z][0-9][0-9]")
                && !extraInfo.trim().equals("RR73");
    }

    /**
     * 检查是否是通联结束：RRR、RR73、73
     *
     * @param extraInfo 消息后缀
     * @return 是否
     */
    public static boolean checkFun4_5(String extraInfo) {
        if (extraInfo == null) return false;
        return extraInfo.trim().equals("RR73")
                || extraInfo.trim().equals("RRR")
                || extraInfo.trim().equals("73");
    }

    /**
     * 从String.xml中提取字符串
     *
     * @param id id
     * @return 字符串
     */
    public static String getStringFromResource(int id) {
        if (getMainContext() != null) {
            return getMainContext().getString(id);
        } else {
            return "";
        }
    }

    /**
     * 把已经通联的DXCC分区添加到集合中
     *
     * @param dxccPrefix DXCC前缀
     */
    public static void addDxcc(String dxccPrefix) {
        dxccMap.put(dxccPrefix, dxccPrefix);
    }

    /**
     * 查看是不是已经通联的DXCC分区
     *
     * @param dxccPrefix DXCC前缀
     * @return 是否
     */
    public static boolean getDxccByPrefix(String dxccPrefix) {
        return dxccMap.containsKey(dxccPrefix);
    }

    /**
     * 把CQ分区加到列表里
     *
     * @param cqZone cq分区编号
     */
    public static void addCqZone(int cqZone) {
        cqMap.put(cqZone, cqZone);
    }

    /**
     * 查是否存在已经通联的CQ分区
     *
     * @param cq cq分区编号
     * @return 是否存在
     */
    public static boolean getCqZoneById(int cq) {
        return cqMap.containsKey(cq);
    }

    /**
     * 把itu分区添加到已通联的ITU列表中
     *
     * @param itu itu编号
     */
    public static void addItuZone(int itu) {
        ituMap.put(itu, itu);
    }

    /**
     * 查Itu分区在不在已通联的列表中
     *
     * @param itu itu编号
     * @return 是否存在
     */
    public static boolean getItuZoneById(int itu) {
        return ituMap.containsKey(itu);
    }

    //用于触发新的网格
    public static MutableLiveData<String> mutableNewGrid = new MutableLiveData<>();

    /**
     * 把呼号与网格的对应关系添加到呼号--网格对应表
     *
     * @param callsign 呼号
     * @param grid     网格
     */
    public static void addCallsignAndGrid(String callsign, String grid) {
        if (callsign == null || grid == null) return;
        if (grid.length() >= 4) {
            callsignAndGrids.put(callsign, grid);
            mutableNewGrid.postValue(grid);
        }
    }

    /**
     * 呼号--网格对应表。以呼号查网格
     *
     * @param callsign 呼号
     * @return 是否有对应的网格
     */
    public static boolean getCallsignHasGrid(String callsign) {
        return callsignAndGrids.containsKey(callsign);
    }

    /**
     * 呼号--网格对应表。以呼号查网格，条件是呼号和网格都对应的上。
     *
     * @param callsign 呼号
     * @param grid     网格
     * @return 是否有对应的网格
     */
    public static boolean getCallsignHasGrid(String callsign, String grid) {
        if (!callsignAndGrids.containsKey(callsign)) return false;
        String s = callsignAndGrids.get(callsign);
        if (s == null) return false;
        return s.equals(grid);
    }

    public static String getGridByCallsign(String callsign, DatabaseOpr db) {
        String s = callsign.replace("<", "").replace(">", "");
        if (getCallsignHasGrid(s)) {
            return callsignAndGrids.get(s);
        } else {
            db.getCallsignQTH(callsign);
            return "";
        }
    }

    /**
     * 遍历呼号--网格对应表，生成HTML
     *
     * @return HTML
     */
    public static String getCallsignAndGridToHTML() {
        StringBuilder result = new StringBuilder();
        int order = 0;
        for (String key : callsignAndGrids.keySet()) {
            order++;
            HtmlContext.tableKeyRow(result, order % 2 != 0, key, callsignAndGrids.get(key));
        }
        return result.toString();
    }

    public static synchronized void deleteArrayListMore(ArrayList<Ft8Message> list) {
        if (list.size() > GeneralVariables.MESSAGE_COUNT) {
            while (list.size() > GeneralVariables.MESSAGE_COUNT) {
                list.remove(0);
            }
        }
    }

    /**
     * 判断是否为整数
     *
     * @param str 传入的字符串
     * @return 是整数返回true, 否则返回false
     */
    public static boolean isInteger(String str) {
        if (str != null && !"".equals(str.trim()))
            return str.matches("^[0-9]*$");
        else
            return false;
    }

    /**
     * 输出音频的数据类型，网络模式不可用
     */
    public enum AudioOutputBitMode {
        Float32,
        Int16
    }

    /**
     * 创建一个临时文件。
     *
     * @param context Context
     * @param prefix  前缀
     * @param suffix  扩展名
     * @return File结构的文件
     */
    public static File getTempFile(Context context, String prefix, String suffix) {
        File tempDir = context.getExternalCacheDir();
        if (tempDir == null) {
            Log.e(TAG, "创建临时文件出错！无法获取临时目录");
            return null;
        }

        try {
            return File.createTempFile(prefix, suffix, tempDir);
        } catch (IOException e) {
            Log.e(TAG, "创建临时文件出错！" + e.getMessage());
            return null;
        }
    }

    /**
     * 把文本数据写入到文件
     *
     * @param file File
     * @param data 文本数据
     */
    public static void writeToFile(File file, String data) {
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(file, true);
            fileOutputStream.write(data.getBytes());
            Log.e(TAG, "文件数据写入完成！");
        } catch (IOException e) {
            Log.e(TAG, String.format("写文件出错：%s", e.getMessage()));
        } finally {
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, String.format("关闭写文件出错：%s", e.getMessage()));
            }
        }
    }

    /**
     * 保存数据包缓存文件
     *
     * @param context 上下文
     * @param prefix  前缀
     * @param suffix  扩展名
     * @param data    数据
     * @return 文件对象
     */
    public static File writeToTempFile(Context context, String prefix, String suffix, String data) {
        File file = getTempFile(context, prefix, suffix);
        writeToFile(file, data);
        if (file != null) {
            file.deleteOnExit();
        }
        return file;
    }

    /**
     * 删除文件夹
     *
     * @param dir 文件夹
     * @return 是否成功删除
     */
    public static boolean deleteDir(File dir) {
        if (dir == null) return false;
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    public static void clearCache(Context context) {
        try {
            File dir = context.getExternalCacheDir();
            deleteDir(dir);
        } catch (Exception e) {
            // ignore
        }
    }
}
