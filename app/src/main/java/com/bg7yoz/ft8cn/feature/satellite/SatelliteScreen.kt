package com.bg7yoz.ft8cn.feature.satellite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.eme.ObserverLocation
import com.bg7yoz.ft8cn.satellite.ObserverPosition
import com.bg7yoz.ft8cn.satellite.SatelliteCatalogRepository
import com.bg7yoz.ft8cn.satellite.SatelliteDopplerPlanner
import com.bg7yoz.ft8cn.satellite.SatelliteFrequencyTarget
import com.bg7yoz.ft8cn.satellite.SatelliteObservation
import com.bg7yoz.ft8cn.satellite.SatellitePass
import com.bg7yoz.ft8cn.satellite.SatellitePassPredictor
import com.bg7yoz.ft8cn.satellite.SatelliteRefreshResult
import com.bg7yoz.ft8cn.satellite.SatelliteTransponder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class SatelliteDetailState(
    val observation: SatelliteObservation,
    val passes: List<SatellitePass>,
    val polarTrack: List<SatelliteObservation>,
    val groundTrack: List<SatelliteObservation>,
    val transponders: List<SatelliteTransponder>,
    val frequencyTarget: SatelliteFrequencyTarget?,
    val tleEpochUtcMillis: Long,
)

/** 卫星页只保存有界轨迹摘要；SGP4 和 Room 工作均在后台调度器执行。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SatelliteScreen() {
    val context = LocalContext.current
    val repository = remember(context) { FeatureAppGraph.from(context).satelliteCatalogRepository }
    val satellites by repository.observeSatellites().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var selectedCatalogNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("离线目录已就绪") }
    var manualTle by rememberSaveable { mutableStateOf("") }
    val gridObserver = remember { ObserverLocation.fromGrid(GeneralVariables.getMyMaidenheadGrid()) }
    var latitudeText by rememberSaveable {
        mutableStateOf(String.format(Locale.US, "%.4f", gridObserver?.latitudeDeg ?: 0.0))
    }
    var longitudeText by rememberSaveable {
        mutableStateOf(String.format(Locale.US, "%.4f", gridObserver?.longitudeDeg ?: 0.0))
    }
    var detail by remember { mutableStateOf<SatelliteDetailState?>(null) }
    val observer = remember(latitudeText, longitudeText) {
        runCatching {
            ObserverPosition(latitudeText.toDouble(), longitudeText.toDouble())
        }.getOrNull()
    }
    val filtered = remember(satellites, search) {
        val needle = search.trim()
        satellites.filter {
            needle.isBlank() || it.name.contains(needle, ignoreCase = true) ||
                it.catalogNumber.toString().contains(needle)
        }.take(MAXIMUM_VISIBLE_SATELLITES)
    }

    LaunchedEffect(selectedCatalogNumber, observer) {
        val catalogNumber = selectedCatalogNumber ?: return@LaunchedEffect
        val station = observer ?: return@LaunchedEffect
        while (true) {
            detail = loadDetail(repository, catalogNumber, station, System.currentTimeMillis())
            delay(5_000L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("卫星工作台", style = MaterialTheme.typography.headlineMedium)
            Text(
                "离线优先 SGP4 · 过境预测 · 上下行 Doppler · CAT 默认不 armed",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SatelliteCard("目录与观察站") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latitudeText,
                        onValueChange = { latitudeText = it.take(12) },
                        modifier = Modifier.weight(1f),
                        label = { Text("纬度") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = longitudeText,
                        onValueChange = { longitudeText = it.take(13) },
                        modifier = Modifier.weight(1f),
                        label = { Text("经度") },
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            status = runCatching {
                                repository.refreshCelesTrakGroup("amateur", System.currentTimeMillis())
                                    .displayText()
                            }.getOrElse { "刷新失败：${it.message}" }
                        }
                    }) { Text("刷新业余卫星") }
                    OutlinedButton(
                        enabled = selectedCatalogNumber != null,
                        onClick = {
                            val selected = selectedCatalogNumber ?: return@OutlinedButton
                            scope.launch {
                                status = runCatching {
                                    repository.refreshSatNogsTransmitters(selected, System.currentTimeMillis())
                                        .displayText("转发器")
                                }.getOrElse { "SatNOGS 失败：${it.message}" }
                            }
                        },
                    ) { Text("刷新转发器") }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text(
                    "CelesTrak TLE；SatNOGS 转发器数据 CC BY-SA 4.0。网络只在点击刷新时使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SatelliteCard("手动导入 TLE") {
                OutlinedTextField(
                    value = manualTle,
                    onValueChange = { manualTle = it.take(MAXIMUM_MANUAL_TLE_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("粘贴 2LE/3LE") },
                    maxLines = 6,
                )
                OutlinedButton(
                    enabled = manualTle.isNotBlank(),
                    onClick = {
                        scope.launch {
                            status = runCatching {
                                val count = repository.importTle(manualTle, "manual-ui", System.currentTimeMillis())
                                manualTle = ""
                                "已导入 $count 颗卫星"
                            }.getOrElse { "导入失败：${it.message}" }
                        }
                    },
                ) { Text("验证并导入") }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(40) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索名称或 NORAD 编号") },
            )
        }
        items(filtered, key = { it.catalogNumber }) { satellite ->
            Card(onClick = { selectedCatalogNumber = satellite.catalogNumber }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(satellite.name.ifBlank { "NORAD ${satellite.catalogNumber}" }, fontWeight = FontWeight.SemiBold)
                        Text("NORAD ${satellite.catalogNumber}", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = {
                        scope.launch { repository.setFavorite(satellite.catalogNumber, !satellite.favorite) }
                    }) { Text(if (satellite.favorite) "已收藏" else "收藏") }
                }
            }
        }
        if (selectedCatalogNumber != null) {
            item {
                SatelliteDetail(detail)
            }
        }
    }
}

@Composable
private fun SatelliteDetail(detail: SatelliteDetailState?) {
    SatelliteCard("实时轨道与频率计划") {
        if (detail == null) {
            Text("正在读取离线 TLE，或当前位置无效。")
            return@SatelliteCard
        }
        val observation = detail.observation
        Text(String.format(
            Locale.US,
            "方位 %.1f° · 仰角 %.1f° · 距离 %.0f km · 径向速度 %+.0f m/s",
            observation.azimuthDegrees,
            observation.elevationDegrees,
            observation.rangeKilometers,
            observation.rangeRateMetersPerSecond,
        ))
        Text(String.format(
            Locale.US,
            "星下点 %.2f°, %.2f° · 高度 %.0f km",
            observation.subpointLatitudeDegrees,
            observation.subpointLongitudeDegrees,
            observation.altitudeKilometers,
        ))
        val ageDays = (System.currentTimeMillis() - detail.tleEpochUtcMillis) / 86_400_000.0
        Text(
            String.format(Locale.US, "TLE age %.1f 天%s", ageDays, if (ageDays > 14.0) "（过期警告）" else ""),
            color = if (ageDays > 14.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PolarTrack(detail.polarTrack, Modifier.weight(1f).height(180.dp))
            GroundTrack(detail.groundTrack, Modifier.weight(1f).height(180.dp))
        }
        detail.passes.take(3).forEach { pass ->
            Text(
                "${formatUtc(pass.aosUtcMillis)} → ${formatUtc(pass.losUtcMillis)} · " +
                    "最高 ${pass.maximumElevationDegrees.roundToInt()}°",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (detail.transponders.isEmpty()) {
            Text("无离线转发器记录；可点击“刷新转发器”。")
        } else {
            detail.transponders.take(4).forEach {
                Text("${it.name} · ${it.mode}${if (it.inverted) " · 反向" else ""}")
            }
        }
        detail.frequencyTarget?.let { target ->
            Text("RX ${target.rxFrequencyHz} Hz · TX ${target.txFrequencyHz} Hz")
            Text(
                String.format(
                    Locale.US,
                    "Doppler RX %+.0f Hz / TX %+.0f Hz",
                    target.receiveDopplerHz,
                    target.transmitDopplerHz,
                ),
            )
        }
        Text(
            "CAT 跟踪必须从 Radio 页显式启动；过期目标、读回失败或 LOS 会停止并恢复原频率，永不自动 PTT。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PolarTrack(track: List<SatelliteObservation>, modifier: Modifier) {
    Canvas(modifier) {
        val radius = minOf(size.width, size.height) * 0.42f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color.Gray, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawCircle(Color.Gray, radius / 2f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        track.zipWithNext().forEach { (first, second) ->
            drawLine(Color(0xFF00A896), polarPoint(first, center, radius), polarPoint(second, center, radius), 3f)
        }
        track.lastOrNull()?.let { drawCircle(Color(0xFFFFB000), 6f, polarPoint(it, center, radius)) }
    }
}

@Composable
private fun GroundTrack(track: List<SatelliteObservation>, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color.Gray, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawLine(Color.DarkGray, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
        track.zipWithNext().forEach { (first, second) ->
            val a = Offset(
                ((first.subpointLongitudeDegrees + 180.0) / 360.0 * size.width).toFloat(),
                ((90.0 - first.subpointLatitudeDegrees) / 180.0 * size.height).toFloat(),
            )
            val b = Offset(
                ((second.subpointLongitudeDegrees + 180.0) / 360.0 * size.width).toFloat(),
                ((90.0 - second.subpointLatitudeDegrees) / 180.0 * size.height).toFloat(),
            )
            if (kotlin.math.abs(a.x - b.x) < size.width / 2f) drawLine(Color(0xFF3A86FF), a, b, 3f)
        }
    }
}

private fun polarPoint(observation: SatelliteObservation, center: Offset, radius: Float): Offset {
    val radial = ((90.0 - observation.elevationDegrees.coerceIn(0.0, 90.0)) / 90.0 * radius).toFloat()
    val angle = Math.toRadians(observation.azimuthDegrees - 90.0)
    return Offset(center.x + cos(angle).toFloat() * radial, center.y + sin(angle).toFloat() * radial)
}

@Composable
private fun SatelliteCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private suspend fun loadDetail(
    repository: SatelliteCatalogRepository,
    catalogNumber: Int,
    observer: ObserverPosition,
    nowUtcMillis: Long,
): SatelliteDetailState? = withContext(Dispatchers.Default) {
    val propagator = repository.latestPropagator(catalogNumber) ?: return@withContext null
    val observation = propagator.observe(observer, nowUtcMillis)
    val passes = SatellitePassPredictor(propagator, observer).predict(
        nowUtcMillis,
        nowUtcMillis + 24L * 60L * 60L * 1_000L,
        maximumPasses = 16,
    )
    val firstPass = passes.firstOrNull()
    val polarTrack = if (firstPass == null) emptyList() else sampleObservations(
        propagator,
        observer,
        firstPass.aosUtcMillis,
        firstPass.losUtcMillis,
        MAXIMUM_POLAR_POINTS,
    )
    val groundTrack = sampleObservations(
        propagator,
        observer,
        nowUtcMillis,
        nowUtcMillis + 90L * 60L * 1_000L,
        MAXIMUM_GROUND_POINTS,
    )
    val transponders = repository.transponders(catalogNumber).take(MAXIMUM_TRANSPONDERS)
    val usable = transponders.firstOrNull {
        it.downlinkLowHz != null && it.downlinkHighHz != null && it.uplinkLowHz != null && it.uplinkHighHz != null
    }
    val frequencyTarget = usable?.let {
        val downlink = (requireNotNull(it.downlinkLowHz) + requireNotNull(it.downlinkHighHz)) / 2L
        val uplink = SatelliteDopplerPlanner().mapDownlinkToUplink(downlink, it)
        SatelliteDopplerPlanner().plan(nowUtcMillis, observation.rangeRateMetersPerSecond, downlink, uplink)
    }
    SatelliteDetailState(
        observation,
        passes,
        polarTrack,
        groundTrack,
        transponders,
        frequencyTarget,
        propagator.record.epochUtcMillis,
    )
}

private fun sampleObservations(
    propagator: com.bg7yoz.ft8cn.satellite.Sgp4OrbitPropagator,
    observer: ObserverPosition,
    start: Long,
    end: Long,
    maximumPoints: Int,
): List<SatelliteObservation> {
    if (end <= start || maximumPoints < 2) return emptyList()
    val step = maxOf(1L, (end - start) / (maximumPoints - 1))
    return buildList(maximumPoints) {
        var time = start
        while (time < end && size < maximumPoints - 1) {
            add(propagator.observe(observer, time))
            time += step
        }
        add(propagator.observe(observer, end))
    }
}

private fun SatelliteRefreshResult.displayText(prefix: String = "目录"): String = when (this) {
    is SatelliteRefreshResult.Updated -> "$prefix 已更新：$recordCount 条，SHA256 ${payloadSha256.take(12)}…"
    SatelliteRefreshResult.NotModified -> "$prefix 未变化"
    is SatelliteRefreshResult.Throttled -> "$prefix 刷新过于频繁，请在 ${formatUtc(retryAfterUtcMillis)} 后重试"
}

private fun formatUtc(utcMillis: Long): String = SimpleDateFormat("MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(utcMillis))

private const val MAXIMUM_VISIBLE_SATELLITES = 250
private const val MAXIMUM_MANUAL_TLE_CHARS = 64 * 1024
private const val MAXIMUM_POLAR_POINTS = 64
private const val MAXIMUM_GROUND_POINTS = 96
private const val MAXIMUM_TRANSPONDERS = 32
