package com.bg7yoz.ft8cn.core.model

enum class FtxMode {
    FT8,
    FT4,
    Q65,
}

enum class DecodeStage {
    EARLY,
    LIVE_FULL,
    DISK,
    FOLLOW_UP,
}

data class DecodeResultSummary(
    val utcMillis: Long,
    val mode: FtxMode,
    val text: String,
    val snrDb: Int,
    val frequencyHz: Float,
    val timeOffsetSeconds: Float,
)

data class FeatureState(
    val destination: FeatureDestination = FeatureDestination.CALL,
    val clockHealthy: Boolean = false,
    val radioConnected: Boolean = false,
    val transmitting: Boolean = false,
    val decodeResults: List<DecodeResultSummary> = emptyList(),
)

enum class FeatureDestination(val route: String, val label: String, val shortLabel: String) {
    CALL("call", "FT8 / FT4 呼叫", "呼"),
    EME("eme", "Q65 月面通信", "月"),
    SATELLITE("satellite", "卫星工作台", "星"),
    LOGBOOK("logbook", "日志与 LoTW", "志"),
    RADIO("radio", "电台控制", "台"),
    SETTINGS("settings", "设置与时间", "设"),
}
