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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.bg7yoz.ft8cn.FT8Common
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.core.time.DisciplinedClockRegistry
import com.bg7yoz.ft8cn.data.settings.FeatureSettings
import com.bg7yoz.ft8cn.eme.ObserverLocation
import com.bg7yoz.ft8cn.feature.shell.Ft8cnPageHeader
import com.bg7yoz.ft8cn.feature.shell.Ft8cnPanel
import com.bg7yoz.ft8cn.satellite.ObserverPosition
import com.bg7yoz.ft8cn.satellite.SatelliteCatalogRepository
import com.bg7yoz.ft8cn.satellite.SatelliteDopplerPlanner
import com.bg7yoz.ft8cn.satellite.SatelliteFrequencyTarget
import com.bg7yoz.ft8cn.satellite.SatelliteObservation
import com.bg7yoz.ft8cn.satellite.SatellitePass
import com.bg7yoz.ft8cn.satellite.SatellitePassPlanCache
import com.bg7yoz.ft8cn.satellite.SatelliteRefreshResult
import com.bg7yoz.ft8cn.satellite.SatelliteRadioTracker
import com.bg7yoz.ft8cn.satellite.SatelliteTransponder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class SatelliteDetailState(
    val name: String,
    val catalogNumber: Int,
    val observation: SatelliteObservation,
    val passes: List<SatellitePass>,
    val polarTrack: List<SatelliteObservation>,
    val transponders: List<SatelliteTransponder>,
    val selectedTransponderKey: String?,
    val frequencyTarget: SatelliteFrequencyTarget?,
    val tleEpochUtcMillis: Long,
)

/** 卫星页只保存有界轨迹摘要；SGP4 和 Room 工作均在后台调度器执行。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SatelliteScreen(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val graph = remember(context) { FeatureAppGraph.from(context) }
    val repository = graph.satelliteCatalogRepository
    val settings by graph.settings.state.collectAsStateWithLifecycle(initialValue = FeatureSettings())
    val radioState by graph.radioController.state.collectAsStateWithLifecycle()
    val radioTracker = remember(graph.radioTransactionCoordinator) {
        SatelliteRadioTracker(graph.radioTransactionCoordinator)
    }
    val passPlanCache = remember { SatellitePassPlanCache() }
    val cleanupScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val satellites by repository.observeSatellites().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var selectedCatalogNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedTransponderKey by rememberSaveable { mutableStateOf<String?>(null) }
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
    var automaticTracking by rememberSaveable { mutableStateOf(false) }
    var trackerStarted by remember { mutableStateOf(false) }
    var trackingStatus by remember { mutableStateOf("自动调频未启用") }
    var satelliteModeEnabled by rememberSaveable {
        mutableStateOf(GeneralVariables.isSatelliteOperatingProfile())
    }
    var previousFtxMode by rememberSaveable {
        mutableStateOf(settings.previousFtxMode.coerceIn(FT8Common.FT8_MODE, FT8Common.FT4_MODE))
    }
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

    LaunchedEffect(filtered, selectedCatalogNumber) {
        if (selectedCatalogNumber == null) {
            selectedCatalogNumber = filtered.firstOrNull()?.catalogNumber
        }
    }

    LaunchedEffect(selectedCatalogNumber) {
        selectedTransponderKey = null
    }

    fun restartOperatingRuntime(mode: Int, profile: Int) {
        mainViewModel.ft8TransmitSignal?.apply {
            setActivated(false)
            stopQ65Sequence("运行模式已切换")
            setTransmitting(false)
        }
        GeneralVariables.setOperatingProfile(profile)
        GeneralVariables.setSignalMode(mode)
        mainViewModel.ft8SignalListener?.restartByCurrentMode()
        mainViewModel.ft8TransmitSignal?.apply {
            restartByCurrentMode()
            setActivated(false)
            setTransmitting(false)
            resetToCQ()
        }
        mainViewModel.clearTransmittingMessage()
    }

    fun setSatelliteModeEnabled(enabled: Boolean) {
        if (enabled) {
            val currentMode = GeneralVariables.getSignalMode()
            if (currentMode == FT8Common.FT8_MODE || currentMode == FT8Common.FT4_MODE) {
                previousFtxMode = currentMode
            }
            satelliteModeEnabled = true
            restartOperatingRuntime(
                FT8Common.FT4_MODE,
                GeneralVariables.OPERATING_PROFILE_SATELLITE_FT4,
            )
            scope.launch { graph.settings.setSatelliteMode(true, previousFtxMode) }
            return
        }

        automaticTracking = false
        satelliteModeEnabled = false
        val restoreMode = previousFtxMode.coerceIn(FT8Common.FT8_MODE, FT8Common.FT4_MODE)
        restartOperatingRuntime(restoreMode, GeneralVariables.OPERATING_PROFILE_NORMAL)
        scope.launch { graph.settings.setSatelliteMode(false, restoreMode) }
    }

    DisposableEffect(radioTracker) {
        onDispose {
            if (GeneralVariables.isSatelliteOperatingProfile()) {
                GeneralVariables.setOperatingTrackingStatus("自动调频未启用")
            }
            cleanupScope.launch {
                withTimeoutOrNull(3_000L) { radioTracker.stop("离开卫星页面") }
                cleanupScope.cancel()
            }
        }
    }

    LaunchedEffect(settings.satelliteModeEnabled, settings.previousFtxMode) {
        previousFtxMode = settings.previousFtxMode.coerceIn(FT8Common.FT8_MODE, FT8Common.FT4_MODE)
        satelliteModeEnabled = settings.satelliteModeEnabled && !settings.emeModeEnabled
    }

    LaunchedEffect(selectedCatalogNumber, observer, selectedTransponderKey) {
        val catalogNumber = selectedCatalogNumber ?: return@LaunchedEffect
        val station = observer ?: return@LaunchedEffect
        while (true) {
            val loaded = loadDetail(
                repository,
                passPlanCache,
                catalogNumber,
                station,
                DisciplinedClockRegistry.nowMillis(),
                selectedTransponderKey,
            )
            detail = loaded
            if (loaded?.selectedTransponderKey != selectedTransponderKey) {
                selectedTransponderKey = loaded?.selectedTransponderKey
            }
            delay(1_000L)
        }
    }

    LaunchedEffect(satelliteModeEnabled, automaticTracking, detail?.frequencyTarget?.generatedUtcMillis) {
        if (!satelliteModeEnabled) {
            automaticTracking = false
        }
        if (!automaticTracking) {
            if (trackerStarted) {
                radioTracker.stop("用户停止卫星跟踪")
                trackerStarted = false
            }
            trackingStatus = "自动调频未启用"
            return@LaunchedEffect
        }
        val target = detail?.frequencyTarget
        if (target == null) {
            if (trackerStarted) {
                radioTracker.stop("卫星频率计划不可用")
                trackerStarted = false
            }
            automaticTracking = false
            trackingStatus = "当前转发器没有可用的上下行频率"
            return@LaunchedEffect
        }
        if (!trackerStarted) {
            val start = radioTracker.start()
            if (start.isFailure) {
                automaticTracking = false
                trackingStatus = "无法启动：${start.exceptionOrNull()?.message}"
                return@LaunchedEffect
            }
            trackerStarted = true
        }
        trackingStatus = radioTracker.apply(target, DisciplinedClockRegistry.nowMillis()).fold(
            onSuccess = { applied -> if (applied) "Hamlib 已按 Doppler 更新频率" else "频率变化未达到更新步长" },
            onFailure = {
                trackerStarted = false
                automaticTracking = false
                "自动调频停止：${it.message}"
            },
        )
    }

    LaunchedEffect(satelliteModeEnabled, trackingStatus) {
        if (satelliteModeEnabled) GeneralVariables.setOperatingTrackingStatus(trackingStatus)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Ft8cnPageHeader(
                title = "卫星追踪",
                subtitle = "过境 · 转发器 · 多普勒修正",
            )
        }
        item {
            SatelliteCard("卫星模式") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (satelliteModeEnabled) "卫星 · FT4 已启用" else "普通 FT8/FT4 运行",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (satelliteModeEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (satelliteModeEnabled) {
                                "固定使用 FT4 并复用 FT4 自动流程；定向 CQ 暂停，配置不会被清除。"
                            } else {
                                "启用后安全停止当前自动发射，关闭时恢复先前的 FT8/FT4 模式。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = satelliteModeEnabled,
                        onCheckedChange = ::setSatelliteModeEnabled,
                    )
                }
            }
        }
        if (selectedCatalogNumber != null) {
            item {
                SatelliteDetail(
                    detail = detail,
                    radioConnected = radioState.connected,
                    satelliteModeEnabled = satelliteModeEnabled,
                    automaticTracking = automaticTracking,
                    trackingStatus = trackingStatus,
                    onAutomaticTrackingChanged = { automaticTracking = it },
                    onTransponderSelected = { selectedTransponderKey = it },
                )
            }
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
                                repository.refreshCelesTrakGroup("amateur", DisciplinedClockRegistry.nowMillis())
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
                                    repository.refreshSatNogsTransmitters(selected, DisciplinedClockRegistry.nowMillis())
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
                                val count = repository.importTle(manualTle, "manual-ui", DisciplinedClockRegistry.nowMillis())
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
        item {
            Text("选择卫星", style = MaterialTheme.typography.titleLarge)
        }
        items(filtered, key = { it.catalogNumber }) { satellite ->
            Card(onClick = {
                selectedCatalogNumber = satellite.catalogNumber
                selectedTransponderKey = null
            }) {
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
    }
}

@Composable
private fun SatelliteDetail(
    detail: SatelliteDetailState?,
    radioConnected: Boolean,
    satelliteModeEnabled: Boolean,
    automaticTracking: Boolean,
    trackingStatus: String,
    onAutomaticTrackingChanged: (Boolean) -> Unit,
    onTransponderSelected: (String) -> Unit,
) {
    val deviceOrientation by rememberDeviceOrientation()
    SatelliteCard("当前过境") {
        if (detail == null) {
            Text("正在读取离线 TLE，或当前位置无效。")
            return@SatelliteCard
        }
        val observation = detail.observation
        Text(
            "${detail.catalogNumber} · ${detail.name}",
            style = MaterialTheme.typography.titleLarge,
        )
        val pass = detail.passes.firstOrNull()
        val countdown = pass?.let {
            val nowUtcMillis = DisciplinedClockRegistry.nowMillis()
            val target = if (nowUtcMillis < it.aosUtcMillis) it.aosUtcMillis else it.losUtcMillis
            formatCountdown(target - nowUtcMillis)
        } ?: "--:--:--"
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (pass != null && DisciplinedClockRegistry.nowMillis() >= pass.aosUtcMillis) "LOS" else "AOS")
                Text(countdown, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Column {
                Text(String.format(Locale.US, "方位 %.1f°", observation.azimuthDegrees))
                Text(String.format(Locale.US, "仰角 %.1f°", observation.elevationDegrees))
            }
        }
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
        val ageDays = kotlin.math.abs(DisciplinedClockRegistry.nowMillis() - detail.tleEpochUtcMillis) / 86_400_000.0
        Text(
            String.format(Locale.US, "TLE age %.1f 天%s", ageDays, if (ageDays > 14.0) "（过期警告）" else ""),
            color = if (ageDays > 14.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(deviceOrientation.summary(), style = MaterialTheme.typography.bodySmall)
        if (deviceOrientation.available) {
            val azimuthError = signedAngleDifference(
                observation.azimuthDegrees,
                deviceOrientation.azimuthDegrees.toDouble(),
            )
            val elevationError = observation.elevationDegrees - deviceOrientation.elevationDegrees
            Text(
                String.format(
                    Locale.US,
                    "指向提示：水平 %+.0f° · 垂直 %+.0f°",
                    azimuthError,
                    elevationError,
                ),
                color = if (kotlin.math.abs(azimuthError) < 5.0 && kotlin.math.abs(elevationError) < 5.0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        PolarTrack(
            track = detail.polarTrack,
            current = observation,
            orientation = deviceOrientation,
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
        Text(
            "黄色点为卫星目标，红色准星随手机方位与仰角移动；两者重合即完成指向。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        detail.passes.take(3).forEach { predictedPass ->
            Text(
                "${formatUtc(predictedPass.aosUtcMillis)} → ${formatUtc(predictedPass.losUtcMillis)} · " +
                    "最高 ${predictedPass.maximumElevationDegrees.roundToInt()}°",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (detail.transponders.isEmpty()) {
            Text("无离线转发器记录；可点击“刷新转发器”。")
        } else {
            Text("转发器", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(detail.transponders, key = { it.stableKey() }) { transponder ->
                    val selected = detail.selectedTransponderKey == transponder.stableKey()
                    val label = transponder.name.ifBlank { transponder.mode.ifBlank { "Unknown" } }
                    if (selected) {
                        Button(
                            enabled = transponder.hasCompleteFrequencyPlan(),
                            onClick = { onTransponderSelected(transponder.stableKey()) },
                        ) {
                            Text(label, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            enabled = transponder.hasCompleteFrequencyPlan(),
                            onClick = { onTransponderSelected(transponder.stableKey()) },
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }
            detail.transponders.firstOrNull { it.stableKey() == detail.selectedTransponderKey }?.let {
                Text("${it.mode.ifBlank { "Mode --" }}${if (it.inverted) " · Inverting" else ""}")
                Text(
                    "D ${formatFrequencyRange(it.downlinkLowHz, it.downlinkHighHz)} · " +
                        "U ${formatFrequencyRange(it.uplinkLowHz, it.uplinkHighHz)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        detail.frequencyTarget?.let { target ->
            Text("频率计划", style = MaterialTheme.typography.titleLarge)
            Text("下行 ${target.rxFrequencyHz.toMhzText()} MHz")
            Text("上行 ${target.txFrequencyHz.toMhzText()} MHz")
            Text(
                String.format(
                    Locale.US,
                    "Doppler RX %+.0f Hz / TX %+.0f Hz",
                    target.receiveDopplerHz,
                    target.transmitDopplerHz,
                ),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hamlib 自动调频", style = MaterialTheme.typography.titleMedium)
                Text(trackingStatus, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = automaticTracking,
                enabled = satelliteModeEnabled && radioConnected && detail.frequencyTarget != null,
                onCheckedChange = onAutomaticTrackingChanged,
            )
        }
        Text(
            when {
                !satelliteModeEnabled -> "请先启用卫星模式；自动跟踪不会单独改变 FT8/FT4 运行状态。"
                radioConnected -> "频率更新具有步长、时效和读回保护；LOS 或失败时恢复原频率，永不自动 PTT。"
                else -> "请先从底部电台图标连接 Hamlib。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (satelliteModeEnabled && radioConnected) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PolarTrack(
    track: List<SatelliteObservation>,
    current: SatelliteObservation,
    orientation: DeviceOrientation,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val radius = minOf(size.width, size.height) * 0.44f
        val center = Offset(size.width / 2f, size.height / 2f)
        val gridColor = Color(0xFF8CA9A3)
        drawCircle(gridColor, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawCircle(gridColor, radius * 2f / 3f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawCircle(gridColor, radius / 3f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawLine(gridColor, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 2f)
        drawLine(gridColor, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 2f)
        track.zipWithNext().forEach { (first, second) ->
            drawLine(
                Color(0xFF00A896),
                polarPoint(first, center, radius),
                polarPoint(second, center, radius),
                5f,
            )
        }
        track.firstOrNull()?.let {
            drawCircle(Color(0xFFFFB000), 7f, polarPoint(it, center, radius))
        }
        track.lastOrNull()?.let {
            drawCircle(Color(0xFF6C7A89), 9f, polarPoint(it, center, radius))
        }
        drawCircle(Color(0xFFFFD166), 12f, polarPoint(current, center, radius))
        if (orientation.available) {
            val marker = polarPoint(
                orientation.azimuthDegrees.toDouble(),
                orientation.elevationDegrees.toDouble(),
                center,
                radius,
            )
            val red = Color(0xFFE32020)
            drawLine(red.copy(alpha = 0.45f), center, marker, 2f)
            drawCircle(red, 18f, marker, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            drawLine(red, Offset(marker.x - 26f, marker.y), Offset(marker.x + 26f, marker.y), 4f)
            drawLine(red, Offset(marker.x, marker.y - 26f), Offset(marker.x, marker.y + 26f), 4f)
            drawCircle(red, 4f, marker)
        }
    }
}

private fun polarPoint(
    observation: SatelliteObservation,
    center: Offset,
    radius: Float,
): Offset = polarPoint(observation.azimuthDegrees, observation.elevationDegrees, center, radius)

private fun polarPoint(
    azimuthDegrees: Double,
    elevationDegrees: Double,
    center: Offset,
    radius: Float,
): Offset {
    val radial = ((90.0 - elevationDegrees.coerceIn(0.0, 90.0)) / 90.0 * radius).toFloat()
    val angle = Math.toRadians(azimuthDegrees - 90.0)
    return Offset(center.x + cos(angle).toFloat() * radial, center.y + sin(angle).toFloat() * radial)
}

private fun signedAngleDifference(targetDegrees: Double, currentDegrees: Double): Double =
    (targetDegrees - currentDegrees + 540.0) % 360.0 - 180.0

@Composable
private fun SatelliteCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Ft8cnPanel(title = title, content = content)
}

private suspend fun loadDetail(
    repository: SatelliteCatalogRepository,
    passPlanCache: SatellitePassPlanCache,
    catalogNumber: Int,
    observer: ObserverPosition,
    nowUtcMillis: Long,
    selectedTransponderKey: String?,
): SatelliteDetailState? = withContext(Dispatchers.Default) {
    val propagator = repository.latestPropagator(catalogNumber) ?: return@withContext null
    val observation = propagator.observe(observer, nowUtcMillis)
    val passPlan = passPlanCache.load(propagator, observer, nowUtcMillis)
    val passes = passPlan.passes
    val polarTrack = passPlan.polarTrack
    val transponders = repository.transponders(catalogNumber).take(MAXIMUM_TRANSPONDERS)
    val usableTransponders = transponders.filter(SatelliteTransponder::hasCompleteFrequencyPlan)
    val usable = usableTransponders.firstOrNull { it.stableKey() == selectedTransponderKey }
        ?: usableTransponders.firstOrNull()
    val frequencyTarget = usable?.let {
        val downlink = (requireNotNull(it.downlinkLowHz) + requireNotNull(it.downlinkHighHz)) / 2L
        val uplink = SatelliteDopplerPlanner().mapDownlinkToUplink(downlink, it)
        SatelliteDopplerPlanner().plan(nowUtcMillis, observation.rangeRateMetersPerSecond, downlink, uplink)
    }
    SatelliteDetailState(
        propagator.record.name.ifBlank { "NORAD $catalogNumber" },
        catalogNumber,
        observation,
        passes,
        polarTrack,
        transponders,
        usable?.stableKey(),
        frequencyTarget,
        propagator.record.epochUtcMillis,
    )
}

private fun SatelliteRefreshResult.displayText(prefix: String = "目录"): String = when (this) {
    is SatelliteRefreshResult.Updated -> "$prefix 已更新：$recordCount 条，SHA256 ${payloadSha256.take(12)}…"
    SatelliteRefreshResult.NotModified -> "$prefix 未变化"
    is SatelliteRefreshResult.Throttled -> "$prefix 刷新过于频繁，请在 ${formatUtc(retryAfterUtcMillis)} 后重试"
}

private fun formatUtc(utcMillis: Long): String = SimpleDateFormat("MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(utcMillis))

private fun formatCountdown(durationMillis: Long): String {
    val seconds = (durationMillis.coerceAtLeast(0L) / 1_000L)
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3_600, (seconds / 60) % 60, seconds % 60)
}

private fun Long.toMhzText(): String = String.format(Locale.US, "%.6f", this / 1_000_000.0)

private fun SatelliteTransponder.stableKey(): String = listOf(
    name,
    mode,
    downlinkLowHz,
    downlinkHighHz,
    uplinkLowHz,
    uplinkHighHz,
    inverted,
).joinToString("|")

private fun SatelliteTransponder.hasCompleteFrequencyPlan(): Boolean =
    downlinkLowHz != null && downlinkHighHz != null && uplinkLowHz != null && uplinkHighHz != null

private fun formatFrequencyRange(lowHz: Long?, highHz: Long?): String {
    if (lowHz == null || highHz == null) return "--"
    return if (lowHz == highHz) {
        "${lowHz.toMhzText()} MHz"
    } else {
        "${lowHz.toMhzText()}–${highHz.toMhzText()} MHz"
    }
}

private const val MAXIMUM_VISIBLE_SATELLITES = 250
private const val MAXIMUM_MANUAL_TLE_CHARS = 64 * 1024
private const val MAXIMUM_TRANSPONDERS = 32
