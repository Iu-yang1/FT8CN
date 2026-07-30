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

enum class FeatureDestination(
    val route: String,
    val label: String,
    val shortLabel: String,
    val navigationLabel: String,
    val primary: Boolean,
) {
    DECODE("decode", "解码", "解", "解码", true),
    CALL("call", "FT8 / FT4 呼叫", "呼", "呼叫", true),
    SPECTRUM("spectrum", "频谱", "谱", "频谱", true),
    EME("eme", "Q65 / EME", "月", "EME", true),
    SATELLITE("satellite", "卫星追踪", "星", "卫星", true),
    LOGBOOK("logbook", "通联记录与 LoTW", "志", "记录", false),
    RADIO("radio", "Hamlib 电台", "台", "电台", false),
    SETTINGS("settings", "设置与时间", "设", "设置", false),
}
