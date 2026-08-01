package com.bg7yoz.ft8cn.data.logbook

import android.content.Context
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.core.model.FtxMode
import com.bg7yoz.ft8cn.log.QSLRecord
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** 将仍在使用的旧自动通联记录可靠地写入现代 Room 日志。 */
object LegacyQsoPersistence {
    private val utcFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)

    @JvmStatic
    fun toRecord(
        legacy: QSLRecord,
        operatingProfile: Int = GeneralVariables.OPERATING_PROFILE_NORMAL,
        satelliteName: String? = null,
        satelliteMode: String? = null,
    ): QsoRecord {
        val mode = when (legacy.mode.trim().uppercase(Locale.US)) {
            "FT8" -> FtxMode.FT8
            "FT4" -> FtxMode.FT4
            "Q65", "MFSK/Q65" -> FtxMode.Q65
            else -> error("不支持的通联模式：${legacy.mode}")
        }
        val started = parseUtc(legacy.qso_date, legacy.time_on)
        val ended = runCatching { parseUtc(legacy.qso_date_off, legacy.time_off) }
            .getOrDefault(started)
            .coerceAtLeast(started)
        val stationCall = legacy.myCallsign.orEmpty().trim().uppercase(Locale.US)
        val dxCall = legacy.toCallsign.orEmpty().trim().uppercase(Locale.US)
        require(stationCall.isNotEmpty()) { "本台呼号为空" }
        require(dxCall.isNotEmpty()) { "对方呼号为空" }
        require(legacy.bandFreq > 0) { "通联频率无效" }

        val normalizedSatellite = satelliteName?.trim()?.uppercase(Locale.US)?.ifEmpty { null }
        val propagation = when (operatingProfile) {
            GeneralVariables.OPERATING_PROFILE_Q65_EME -> "EME"
            GeneralVariables.OPERATING_PROFILE_SATELLITE_FT4 -> if (normalizedSatellite != null) "SAT" else null
            else -> null
        }
        val stableId = QsoStableId.create(
            started,
            mode,
            stationCall,
            dxCall,
            legacy.bandFreq,
            propagation,
            normalizedSatellite,
        )
        return QsoRecord(
            stableId = stableId,
            startedUtcMillis = started,
            endedUtcMillis = ended,
            mode = mode,
            submode = when (mode) {
                FtxMode.FT8 -> null
                FtxMode.FT4 -> "FT4"
                FtxMode.Q65 -> "Q65"
            },
            stationCall = stationCall,
            stationGrid = legacy.myMaidenGrid.orEmpty().trim().uppercase(Locale.US),
            dxCall = dxCall,
            dxGrid = legacy.toMaidenGrid.orEmpty().trim().uppercase(Locale.US),
            frequencyHz = legacy.bandFreq,
            reportSent = legacy.sendReport.takeUnless { it <= -100 }?.toString().orEmpty(),
            reportReceived = legacy.receivedReport.takeUnless { it <= -100 }?.toString().orEmpty(),
            propagationMode = propagation,
            satelliteName = normalizedSatellite,
            satelliteMode = satelliteMode?.trim()?.uppercase(Locale.US)?.ifEmpty { null },
            lotwStatus = if (legacy.isLotW_QSL) LotwStatus.CONFIRMED else LotwStatus.LOCAL,
            updatedUtcMillis = ended,
        )
    }

    /** 仅供已有 Java 控制链在专用 IO executor 中调用。 */
    @JvmStatic
    fun persistBlocking(
        context: Context,
        legacy: QSLRecord,
        operatingProfile: Int,
        satelliteName: String? = null,
        satelliteMode: String? = null,
    ): Long = runBlocking(Dispatchers.IO) {
        FeatureAppGraph.from(context).qsoLogRepository.upsert(
            toRecord(legacy, operatingProfile, satelliteName, satelliteMode),
        )
    }

    /** 外部 ADIF 保留卫星、EME 与 LoTW 字段，不经信息较少的旧记录模型转换。 */
    @JvmStatic
    fun importFieldsBlocking(context: Context, fields: Map<String, String>): Long = runBlocking(Dispatchers.IO) {
        FeatureAppGraph.from(context).qsoLogRepository.upsert(AdifCodec.fieldsToQso(fields))
    }

    private fun parseUtc(date: String?, time: String?): Long {
        val normalizedDate = date.orEmpty().trim()
        var normalizedTime = time.orEmpty().trim()
        require(normalizedDate.length == 8) { "QSO 日期无效" }
        require(normalizedTime.length == 4 || normalizedTime.length == 6) { "QSO 时间无效" }
        if (normalizedTime.length == 4) normalizedTime += "00"
        return LocalDateTime.parse(normalizedDate + normalizedTime, utcFormatter)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }
}
