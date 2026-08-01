package com.bg7yoz.ft8cn.feature.eme

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bg7yoz.ft8cn.FT8Common
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.data.settings.FeatureSettings
import com.bg7yoz.ft8cn.eme.EmeDopplerCalculator
import com.bg7yoz.ft8cn.eme.EmeRadioTracker
import com.bg7yoz.ft8cn.eme.EmeRadioTrackingPolicy
import com.bg7yoz.ft8cn.eme.MoonEphemeris
import com.bg7yoz.ft8cn.eme.ObserverLocation
import com.bg7yoz.ft8cn.feature.shell.Ft8cnPageHeader
import com.bg7yoz.ft8cn.feature.shell.Ft8cnPanel
import com.bg7yoz.ft8cn.feature.satellite.DeviceOrientation
import com.bg7yoz.ft8cn.feature.satellite.rememberDeviceOrientation
import com.bg7yoz.ft8cn.feature.satellite.summary
import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private data class SubmodeOption(val value: Int, val label: String)

/** Q65/EME 独立页面；仅持有有界天文轨迹和配置，不持有 PCM 或 native 句柄。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EmeScreen(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val graph = remember(context) { FeatureAppGraph.from(context) }
    val radioState by graph.radioController.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle(initialValue = FeatureSettings())
    val radioTracker = remember(graph.radioTransactionCoordinator) {
        EmeRadioTracker(graph.radioTransactionCoordinator)
    }
    val cleanupScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val deviceOrientation by rememberDeviceOrientation()
    val submodes = remember {
        (FT8Common.Q65_SUBMODE_A..FT8Common.Q65_SUBMODE_E).map {
            SubmodeOption(it, "Q65${FT8Common.getQ65SubmodeLabel(it)}")
        }
    }
    val periods = remember { FT8Common.Q65_SUPPORTED_TR_PERIODS.toList() }
    var selectedSubmode by rememberSaveable { mutableStateOf(settings.q65Submode) }
    var selectedPeriod by rememberSaveable { mutableStateOf(settings.q65TrPeriodSeconds) }
    var emeModeEnabled by rememberSaveable { mutableStateOf(GeneralVariables.isQ65Mode()) }
    var previousFtxMode by rememberSaveable {
        mutableStateOf(settings.previousFtxMode.coerceIn(FT8Common.FT8_MODE, FT8Common.FT4_MODE))
    }
    var localGrid by rememberSaveable { mutableStateOf(GeneralVariables.getMyMaidenheadGrid()) }
    var dxGrid by rememberSaveable { mutableStateOf("") }
    var baseFrequencyText by rememberSaveable {
        mutableStateOf(settings.emeBaseFrequencyHz.toString())
    }
    var pathMode by rememberSaveable { mutableStateOf(EmeDopplerCalculator.PathMode.CONSTANT_FREQUENCY_ON_MOON) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var automaticTracking by rememberSaveable { mutableStateOf(false) }
    var trackerStarted by remember { mutableStateOf(false) }
    var activeTrackingPolicy by remember { mutableStateOf<EmeRadioTrackingPolicy?>(null) }
    var trackingStatus by remember { mutableStateOf("自动调频未启用") }
    var averagingState by remember { mutableStateOf<FT8SignalListener.Q65AveragingState?>(null) }
    var averagingStatus by remember { mutableStateOf("等待首个 Q65 slot") }
    var useCurrentRigFrequency by rememberSaveable { mutableStateOf(GeneralVariables.emeUseCurrentRigFrequency) }
    var maximumCorrectionText by rememberSaveable { mutableStateOf(GeneralVariables.emeMaxCorrectionHz.toString()) }
    var updateIntervalText by rememberSaveable { mutableStateOf(GeneralVariables.emeUpdateIntervalSeconds.toString()) }
    var minimumElevationText by rememberSaveable { mutableStateOf(GeneralVariables.emeMinElevationDeg.toString()) }
    var allowWhileTransmitting by rememberSaveable {
        mutableStateOf(GeneralVariables.emeAllowCorrectionWhileTransmitting)
    }
    var restoreFrequencyOnStop by rememberSaveable {
        mutableStateOf(GeneralVariables.emeRestoreFrequencyOnDisable)
    }

    fun persistConfig(key: String, value: String) {
        mainViewModel.databaseOpr.writeConfig(key, value, null)
    }

    DisposableEffect(radioTracker) {
        onDispose {
            if (GeneralVariables.isEmeOperatingProfile()) {
                GeneralVariables.setOperatingTrackingStatus("自动调频未启用")
            }
            cleanupScope.launch {
                withTimeoutOrNull(3_000L) { radioTracker.stop() }
                cleanupScope.cancel()
            }
        }
    }

    fun applyQ65Configuration(submode: Int, period: Int) {
        val changed = GeneralVariables.setQ65Configuration(submode, period)
        scope.launch { graph.settings.setQ65Configuration(submode, period) }
        if (changed && GeneralVariables.isQ65Mode()) {
            mainViewModel.ft8SignalListener?.restartByCurrentMode()
            mainViewModel.ft8TransmitSignal?.apply {
                restartByCurrentMode()
                setActivated(false)
                setTransmitting(false)
                resetToCQ()
            }
            mainViewModel.clearTransmittingMessage()
        }
    }

    fun activateQ65Runtime() {
        applyQ65Configuration(selectedSubmode, selectedPeriod)
        mainViewModel.ft8TransmitSignal?.setActivated(false)
        GeneralVariables.setOperatingProfile(GeneralVariables.OPERATING_PROFILE_Q65_EME)
        GeneralVariables.setSignalMode(FT8Common.Q65_MODE)
        mainViewModel.ft8SignalListener?.restartByCurrentMode()
        mainViewModel.ft8TransmitSignal?.apply {
            restartByCurrentMode()
            prepareQ65ReceiveOnly()
        }
        mainViewModel.clearTransmittingMessage()
    }

    fun setEmeModeEnabled(enabled: Boolean) {
        if (enabled) {
            val currentMode = GeneralVariables.getSignalMode()
            if (currentMode == FT8Common.FT8_MODE || currentMode == FT8Common.FT4_MODE) {
                previousFtxMode = currentMode
            }
            emeModeEnabled = true
            scope.launch { graph.settings.setEmeMode(true, previousFtxMode) }
            activateQ65Runtime()
            return
        }

        automaticTracking = false
        mainViewModel.ft8TransmitSignal?.apply {
            stopQ65Sequence("EME 模式已关闭")
            setTransmitting(false)
        }
        val restoreMode = previousFtxMode.coerceIn(FT8Common.FT8_MODE, FT8Common.FT4_MODE)
        GeneralVariables.setOperatingProfile(GeneralVariables.OPERATING_PROFILE_NORMAL)
        GeneralVariables.setSignalMode(restoreMode)
        mainViewModel.ft8SignalListener?.restartByCurrentMode()
        mainViewModel.ft8TransmitSignal?.restartByCurrentMode()
        mainViewModel.clearTransmittingMessage()
        emeModeEnabled = false
        scope.launch { graph.settings.setEmeMode(false, restoreMode) }
    }

    LaunchedEffect(settings.q65Submode, settings.q65TrPeriodSeconds) {
        selectedSubmode = settings.q65Submode
        selectedPeriod = settings.q65TrPeriodSeconds
    }

    LaunchedEffect(settings.emeModeEnabled, settings.previousFtxMode) {
        previousFtxMode = settings.previousFtxMode.coerceIn(FT8Common.FT8_MODE, FT8Common.FT4_MODE)
        emeModeEnabled = settings.emeModeEnabled && !settings.satelliteModeEnabled
    }

    LaunchedEffect(emeModeEnabled) {
        while (emeModeEnabled) {
            averagingState = withContext(Dispatchers.IO) {
                mainViewModel.ft8SignalListener?.q65AveragingState
            }
            delay(2_000L)
        }
        averagingState = null
    }

    val maximumCorrectionHz = maximumCorrectionText.toDoubleOrNull()?.coerceIn(0.0, 1_000_000.0) ?: 5_000.0
    val updateIntervalSeconds = updateIntervalText.toIntOrNull()?.coerceIn(1, 60) ?: 10
    val minimumElevationDegrees = minimumElevationText.toDoubleOrNull()?.coerceIn(-10.0, 90.0) ?: 0.0
    val trackingPolicy = remember(
        maximumCorrectionHz,
        updateIntervalSeconds,
        minimumElevationDegrees,
        allowWhileTransmitting,
        restoreFrequencyOnStop,
    ) {
        EmeRadioTrackingPolicy(
            maximumCorrectionHz = maximumCorrectionHz,
            minimumElevationDegrees = minimumElevationDegrees,
            updateIntervalMillis = updateIntervalSeconds * 1_000L,
            allowWhileTransmitting = allowWhileTransmitting,
            restoreFrequencyOnStop = restoreFrequencyOnStop,
        )
    }

    LaunchedEffect(updateIntervalSeconds) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(updateIntervalSeconds * 1_000L)
        }
    }

    val localObserver = remember(localGrid) { ObserverLocation.fromGrid(localGrid) }
    val dxObserver = remember(dxGrid) { ObserverLocation.fromGrid(dxGrid) }
    val localMoon = remember(localObserver, nowMillis) { localObserver?.let { MoonEphemeris.calculate(it, nowMillis) } }
    val dxMoon = remember(dxObserver, nowMillis) { dxObserver?.let { MoonEphemeris.calculate(it, nowMillis) } }
    val baseFrequencyHz = baseFrequencyText.toLongOrNull()?.takeIf { it in 100_000L..100_000_000_000L }
    val correction = remember(pathMode, baseFrequencyHz, localMoon, dxMoon) {
        if (baseFrequencyHz == null || localMoon == null) null else runCatching {
            EmeDopplerCalculator.calculatePlan(
                baseFrequencyHz.toDouble(),
                localMoon.rangeRateMps,
                dxMoon?.rangeRateMps ?: Double.NaN,
                pathMode,
            )
        }.getOrNull()
    }
    val target = remember(nowMillis, baseFrequencyHz, correction) {
        if (baseFrequencyHz == null || correction == null) null
        else EmeRadioTracker.target(nowMillis, baseFrequencyHz, correction)
    }
    val moonTrack = remember(localObserver, nowMillis / 300_000L) {
        localObserver?.let { observer ->
            List(49) { index -> MoonEphemeris.calculate(observer, nowMillis + index * 15L * 60L * 1_000L) }
        }.orEmpty()
    }

    val correctionWithinLimit = correction != null &&
        kotlin.math.abs(correction.receiveCorrectionHz) <= maximumCorrectionHz &&
        kotlin.math.abs(correction.transmitCorrectionHz) <= maximumCorrectionHz

    LaunchedEffect(automaticTracking, target?.generatedUtcMillis, trackingPolicy) {
        if (!automaticTracking) {
            if (trackerStarted) {
                radioTracker.stop()
                trackerStarted = false
                activeTrackingPolicy = null
            }
            trackingStatus = "自动调频未启用"
            return@LaunchedEffect
        }
        val currentTarget = target
        val currentCorrection = correction
        if (currentTarget == null || currentCorrection == null || localMoon == null ||
            localMoon.elevationDeg < minimumElevationDegrees
        ) {
            if (trackerStarted) {
                radioTracker.stop()
                trackerStarted = false
                activeTrackingPolicy = null
            }
            automaticTracking = false
            trackingStatus = "月面低于最低仰角或频率计划不可用"
            return@LaunchedEffect
        }
        if (!correctionWithinLimit) {
            if (trackerStarted) {
                radioTracker.stop()
                trackerStarted = false
                activeTrackingPolicy = null
            }
            automaticTracking = false
            trackingStatus = "Doppler 修正超过安全上限"
            return@LaunchedEffect
        }
        if (trackerStarted && activeTrackingPolicy != trackingPolicy) {
            radioTracker.stop()
            trackerStarted = false
            activeTrackingPolicy = null
        }
        if (!trackerStarted) {
            val start = radioTracker.start(trackingPolicy)
            if (start.isFailure) {
                automaticTracking = false
                trackingStatus = "无法启动：${start.exceptionOrNull()?.message}"
                return@LaunchedEffect
            }
            trackerStarted = true
            activeTrackingPolicy = trackingPolicy
        }
        trackingStatus = radioTracker.apply(
            target = currentTarget,
            nowUtcMillis = nowMillis,
            receiveCorrectionHz = currentCorrection.receiveCorrectionHz,
            transmitCorrectionHz = currentCorrection.transmitCorrectionHz,
            elevationDegrees = localMoon.elevationDeg,
        ).fold(
            onSuccess = { if (it) "Hamlib 已更新月面 Doppler" else "频率变化未达到更新步长" },
            onFailure = {
                automaticTracking = false
                trackerStarted = false
                "自动调频停止：${it.message}"
            },
        )
    }

    LaunchedEffect(emeModeEnabled, trackingStatus) {
        if (emeModeEnabled) GeneralVariables.setOperatingTrackingStatus(trackingStatus)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Ft8cnPageHeader(
                title = "Q65 / EME",
                subtitle = "Q65 · 月面跟踪 · Doppler",
                modifier = Modifier.testTag("eme-screen-title"),
            )
        }
        item {
            EmeCard("EME 模式") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (emeModeEnabled) "Q65 EME 已启用" else "FT8/FT4 正常运行",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (emeModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (emeModeEnabled) {
                                "FT8/FT4 解码和自动通联已暂停，全部收发按 Q65 周期执行。"
                            } else {
                                "启用后会安全停止当前自动发射，并记住返回时使用的 FT8/FT4 模式。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = emeModeEnabled,
                        onCheckedChange = ::setEmeModeEnabled,
                        modifier = Modifier.testTag("eme-mode-switch"),
                    )
                }
            }
        }
        item {
            EmeCard("月面追踪") {
                if (localMoon == null) {
                    Text("请设置有效的本台网格。", color = MaterialTheme.colorScheme.error)
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(Locale.US, "方位 %.1f°", localMoon.azimuthDeg))
                        Text(String.format(Locale.US, "仰角 %.1f°", localMoon.elevationDeg))
                    }
                    Text(deviceOrientation.summary(), style = MaterialTheme.typography.bodySmall)
                    if (deviceOrientation.available) {
                        val azimuthError = signedAngleDifference(
                            localMoon.azimuthDeg,
                            deviceOrientation.azimuthDegrees.toDouble(),
                        )
                        val elevationError = localMoon.elevationDeg - deviceOrientation.elevationDegrees
                        Text(
                            String.format(
                                Locale.US,
                                "指向提示：水平 %+.0f° · 垂直 %+.0f°",
                                azimuthError,
                                elevationError,
                            ),
                            color = if (kotlin.math.abs(azimuthError) < 5.0 &&
                                kotlin.math.abs(elevationError) < 5.0
                            ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MoonPolarPlot(
                        moonTrack,
                        localMoon,
                        deviceOrientation,
                        Modifier.fillMaxWidth().height(300.dp),
                    )
                    Text(
                        "黄色点为月面目标，红色准星随手机方位与仰角移动；两者重合即完成指向。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(Locale.US, "距离 %.0f km", localMoon.distanceKm))
                        Text(String.format(Locale.US, "径向速度 %+.2f m/s", localMoon.rangeRateMps))
                    }
                }
            }
        }
        item {
            EmeCard("Q65 模式") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(submodes, key = { it.value }) { option ->
                        SelectionButton(selectedSubmode == option.value, option.label) {
                            selectedSubmode = option.value
                            applyQ65Configuration(option.value, selectedPeriod)
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(periods, key = { it }) { period ->
                        SelectionButton(selectedPeriod == period, "${period}s") {
                            selectedPeriod = period
                            applyQ65Configuration(selectedSubmode, period)
                        }
                    }
                }
                Text(
                    "收发报文、TX 序列和自动流程位于底部“呼叫”页；解码结果位于“解码”页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        val state = averagingState
                        Text("Averaging", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                state == null || !state.available -> averagingStatus
                                state.clearPending -> "Clear pending · ${state.averagedFrameCount} frames"
                                else -> "${state.averagedFrameCount} frames"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        enabled = averagingState?.available == true,
                        onClick = {
                            scope.launch {
                                val cleared = withContext(Dispatchers.IO) {
                                    mainViewModel.ft8SignalListener?.resetQ65Averaging() == true
                                }
                                averagingStatus = if (cleared) "Clear pending" else "Clear failed"
                                averagingState = withContext(Dispatchers.IO) {
                                    mainViewModel.ft8SignalListener?.q65AveragingState
                                }
                            }
                        },
                    ) { Text("Clear Avg") }
                }
                Text("包含Q65A-E/15s-120s模式", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            EmeCard("多普勒控制") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = localGrid,
                        onValueChange = { localGrid = it.trim().uppercase(Locale.US).take(6) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("本台网格") },
                    )
                    OutlinedTextField(
                        value = dxGrid,
                        onValueChange = { dxGrid = it.trim().uppercase(Locale.US).take(6) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("DX 网格") },
                    )
                }
                OutlinedTextField(
                    value = baseFrequencyText,
                    onValueChange = {
                        baseFrequencyText = it.filter(Char::isDigit).take(12)
                        it.toLongOrNull()?.takeIf { value -> value in 100_000L..100_000_000_000L }?.let { value ->
                            GeneralVariables.emeBaseFrequencyHz = value
                            scope.launch { graph.settings.setEmeBaseFrequency(value) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("基准频率（Hz）") },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("使用电台当前频率", style = MaterialTheme.typography.titleSmall)
                        Text("启动跟踪时读入 RX dial", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = useCurrentRigFrequency,
                        onCheckedChange = { enabled ->
                            useCurrentRigFrequency = enabled
                            GeneralVariables.emeUseCurrentRigFrequency = enabled
                            persistConfig("emeUseCurrentRigFrequency", if (enabled) "1" else "0")
                            if (enabled && radioState.rxFrequencyHz > 0L) {
                                baseFrequencyText = radioState.rxFrequencyHz.toString()
                                GeneralVariables.emeBaseFrequencyHz = radioState.rxFrequencyHz
                                scope.launch { graph.settings.setEmeBaseFrequency(radioState.rxFrequencyHz) }
                            }
                        },
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(EmeDopplerCalculator.PathMode.values()) { mode ->
                        SelectionButton(pathMode == mode, mode.displayLabel()) { pathMode = mode }
                    }
                }
                if (correction != null && target != null) {
                    Text(String.format(Locale.US, "RX %+.1f Hz → %.6f MHz", correction.receiveCorrectionHz, target.rxFrequencyHz / 1_000_000.0))
                    Text(String.format(Locale.US, "TX %+.1f Hz → %.6f MHz", correction.transmitCorrectionHz, target.txFrequencyHz / 1_000_000.0))
                    if (!correctionWithinLimit) {
                        Text("修正超过 ${maximumCorrectionHz.toInt()} Hz 上限", color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maximumCorrectionText,
                        onValueChange = { value ->
                            maximumCorrectionText = value.filter { it.isDigit() || it == '.' }.take(9)
                            maximumCorrectionText.toDoubleOrNull()?.takeIf { it in 0.0..1_000_000.0 }?.let {
                                GeneralVariables.emeMaxCorrectionHz = it
                                persistConfig("emeMaxCorrectionHz", it.toString())
                            }
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Max Doppler Hz") },
                    )
                    OutlinedTextField(
                        value = updateIntervalText,
                        onValueChange = { value ->
                            updateIntervalText = value.filter(Char::isDigit).take(2)
                            updateIntervalText.toIntOrNull()?.takeIf { it in 1..60 }?.let {
                                GeneralVariables.emeUpdateIntervalSeconds = it
                                persistConfig("emeUpdateIntervalSeconds", it.toString())
                            }
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Update s") },
                    )
                }
                OutlinedTextField(
                    value = minimumElevationText,
                    onValueChange = { value ->
                        minimumElevationText = value.filter { it.isDigit() || it == '.' || it == '-' }.take(6)
                        minimumElevationText.toDoubleOrNull()?.takeIf { it in -10.0..90.0 }?.let {
                            GeneralVariables.emeMinElevationDeg = it
                            persistConfig("emeMinElevationDeg", it.toString())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Min elevation °") },
                )
                EmeSwitchRow("PTT 时继续调频", allowWhileTransmitting) {
                    allowWhileTransmitting = it
                    GeneralVariables.emeAllowCorrectionWhileTransmitting = it
                    persistConfig("emeAllowCorrectionWhileTransmitting", if (it) "1" else "0")
                }
                EmeSwitchRow("停止时恢复 dial", restoreFrequencyOnStop) {
                    restoreFrequencyOnStop = it
                    GeneralVariables.emeRestoreFrequencyOnDisable = it
                    persistConfig("emeRestoreFrequencyOnDisable", if (it) "1" else "0")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hamlib 自动调频", style = MaterialTheme.typography.titleMedium)
                        Text(trackingStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = automaticTracking,
                        enabled = emeModeEnabled && radioState.connected && target != null &&
                            correctionWithinLimit &&
                            (localMoon?.elevationDeg ?: -90.0) >= minimumElevationDegrees,
                        onCheckedChange = { enabled ->
                            if (enabled && useCurrentRigFrequency && radioState.rxFrequencyHz > 0L) {
                                baseFrequencyText = radioState.rxFrequencyHz.toString()
                                GeneralVariables.emeBaseFrequencyHz = radioState.rxFrequencyHz
                                scope.launch { graph.settings.setEmeBaseFrequency(radioState.rxFrequencyHz) }
                            }
                            automaticTracking = enabled
                        },
                    )
                }
                Text(
                    if (radioState.connected) "显式开启后才调整 RX/TX；读回失败会回滚，停止恢复由上方选项控制，永不自动 PTT。"
                    else "请先从底部“电台”图标连接 Hamlib。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (radioState.connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MoonPolarPlot(
    track: List<MoonEphemeris>,
    current: MoonEphemeris,
    orientation: DeviceOrientation,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val radius = minOf(size.width, size.height) * 0.44f
        val center = Offset(size.width / 2f, size.height / 2f)
        val grid = Color(0xFF8CA9A3)
        drawCircle(grid, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawCircle(grid, radius * 2f / 3f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawCircle(grid, radius / 3f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 2f)
        drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 2f)
        track.filter { it.elevationDeg >= 0.0 }.zipWithNext().forEach { (a, b) ->
            drawLine(Color(0xFF00A896), moonPoint(a, center, radius), moonPoint(b, center, radius), 5f)
        }
        if (current.elevationDeg >= 0.0) {
            val point = moonPoint(current, center, radius)
            drawCircle(Color(0xFFFFD166), 14f, point)
            drawCircle(Color(0xFFFFF3C4), 7f, point)
        }
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

private fun moonPoint(moon: MoonEphemeris, center: Offset, radius: Float): Offset =
    polarPoint(moon.azimuthDeg, moon.elevationDeg, center, radius)

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
private fun EmeCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Ft8cnPanel(title = title, content = content)
}

@Composable
private fun SelectionButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun EmeSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun EmeDopplerCalculator.PathMode.displayLabel(): String = when (this) {
    EmeDopplerCalculator.PathMode.FULL_DOPPLER_TO_DX -> "Full DX"
    EmeDopplerCalculator.PathMode.OWN_ECHO -> "Own Echo"
    EmeDopplerCalculator.PathMode.CONSTANT_FREQUENCY_ON_MOON -> "CFOM"
}
