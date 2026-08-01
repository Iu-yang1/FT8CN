package com.bg7yoz.ft8cn.feature.settings

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.Ft8Message
import com.bg7yoz.ft8cn.FAQActivity
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.BuildConfig
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.core.time.ClockSnapshot
import com.bg7yoz.ft8cn.core.time.DisciplinedClockRegistry
import com.bg7yoz.ft8cn.cq.CqRankMethod
import com.bg7yoz.ft8cn.data.settings.FeatureSettings
import com.bg7yoz.ft8cn.ft8signal.FT8Package
import com.bg7yoz.ft8cn.feature.shell.Ft8cnPageHeader
import com.bg7yoz.ft8cn.feature.shell.Ft8cnPanel
import com.bg7yoz.ft8cn.log.ThirdPartyService
import com.bg7yoz.ft8cn.ui.ClearCacheDataDialog
import com.bg7yoz.ft8cn.ui.HelpDialog
import com.bg7yoz.ft8cn.timer.UtcTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 直接写入既有配置键，保证 Compose 控件与 decoder/audio/NTP 使用同一份生产配置。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val graph = remember(context) { FeatureAppGraph.from(context) }
    val featureSettings by graph.settings.state.collectAsStateWithLifecycle(initialValue = FeatureSettings())
    val clock by DisciplinedClockRegistry.state().collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var callsign by rememberSaveable { mutableStateOf(GeneralVariables.myCallsign) }
    var grid by rememberSaveable { mutableStateOf(GeneralVariables.getMyMaidenheadGrid()) }
    var baseFrequency by rememberSaveable { mutableStateOf(GeneralVariables.getBaseFrequency().roundToInt().toString()) }
    var customNtpServer by rememberSaveable { mutableStateOf(GeneralVariables.ntpCustomServer) }
    var ntpEnabled by remember { mutableStateOf(GeneralVariables.ntpEnable) }
    var ntpServerIndex by remember { mutableStateOf(GeneralVariables.ntpServerIndex) }
    var deepDecode by remember { mutableStateOf(GeneralVariables.deepDecodeMode) }
    var decodePasses by remember { mutableStateOf(GeneralVariables.wsjtxDecodePassCount.coerceIn(1, 3)) }
    var decodeRounds by remember { mutableStateOf(GeneralVariables.wsjtxMultiDecodeRoundCount.coerceIn(1, 3)) }
    var decodeSensitivity by remember { mutableStateOf(GeneralVariables.wsjtxDecodeSensitivity.coerceIn(0, 2)) }
    var qsoSensitivity by remember { mutableStateOf(GeneralVariables.wsjtxQsoFreqSensitivity.coerceIn(0, 2)) }
    var earlyDecode by remember { mutableStateOf(GeneralVariables.wsjtxEnableEarlyDecode) }
    var widebandSearch by remember { mutableStateOf(GeneralVariables.wsjtxWidebandDxSearch) }
    var audioRate by remember { mutableStateOf(GeneralVariables.audioSampleRate) }
    var audio32Bit by remember { mutableStateOf(GeneralVariables.audioOutput32Bit) }
    var experimentalMode by remember { mutableStateOf(GeneralVariables.experimentalCodecMode) }
    var autoFollowCq by remember { mutableStateOf(GeneralVariables.autoFollowCQ) }
    var autoCallFollow by remember { mutableStateOf(GeneralVariables.autoCallFollow) }
    var cqQueueEnabled by remember { mutableStateOf(GeneralVariables.cqQueueEnabled) }
    var cqRankMethod by remember { mutableStateOf(GeneralVariables.cqRankMethod) }
    var cqMaxQueueSize by remember { mutableStateOf(GeneralVariables.cqMaxQueueSize) }
    var directedCqPrefixes by rememberSaveable { mutableStateOf(GeneralVariables.cqDirectedCqPrefixes) }
    var cloudlogEnabled by remember { mutableStateOf(GeneralVariables.enableCloudlog) }
    var cloudlogAddress by rememberSaveable { mutableStateOf(GeneralVariables.cloudlogServerAddress) }
    var cloudlogApiKey by rememberSaveable { mutableStateOf(GeneralVariables.cloudlogApiKey) }
    var cloudlogStationId by rememberSaveable { mutableStateOf(GeneralVariables.cloudlogStationID) }
    var qrzEnabled by remember { mutableStateOf(GeneralVariables.enableQRZ) }
    var qrzApiKey by rememberSaveable { mutableStateOf(GeneralVariables.qrzApiKey) }
    var callModifier by rememberSaveable { mutableStateOf(GeneralVariables.toModifier) }
    var transmitDelay by rememberSaveable { mutableStateOf(GeneralVariables.transmitDelay.toString()) }
    var pttDelay by rememberSaveable { mutableStateOf(GeneralVariables.pttDelay.toString()) }
    var sameFrequency by remember { mutableStateOf(GeneralVariables.synFrequency) }
    var compactMessages by remember { mutableStateOf(GeneralVariables.simpleCallItemMode) }
    var launchSupervisionMinutes by remember {
        mutableStateOf((GeneralVariables.launchSupervision / 60_000).coerceAtLeast(0))
    }
    var noReplyLimit by remember { mutableStateOf(GeneralVariables.noReplyLimit.coerceIn(0, 30)) }
    var swrAlarm by remember { mutableStateOf(GeneralVariables.swr_switch_on) }
    var alcAlarm by remember { mutableStateOf(GeneralVariables.alc_switch_on) }
    var autoDxpeditionHound by remember { mutableStateOf(GeneralVariables.autoDxpeditionHound) }
    var saveSwlMessages by remember { mutableStateOf(GeneralVariables.saveSWLMessage) }
    var saveSwlQso by remember { mutableStateOf(GeneralVariables.saveSWL_QSO) }
    var pskReporterEnabled by remember { mutableStateOf(GeneralVariables.enablePskReporter) }
    var pskReporterHost by rememberSaveable { mutableStateOf(GeneralVariables.pskReporterHost) }
    var pskReporterPort by rememberSaveable { mutableStateOf(GeneralVariables.pskReporterPort.toString()) }
    var pskReporterAntenna by rememberSaveable { mutableStateOf(GeneralVariables.pskReporterAntennaInfo) }
    var pskReporterFlushSeconds by rememberSaveable {
        mutableStateOf((GeneralVariables.pskReporterFlushIntervalMs / 1_000).toString())
    }
    var manualClockOffset by rememberSaveable { mutableStateOf(UtcTimer.delay.toString()) }
    var manualHound by remember { mutableStateOf(GeneralVariables.manualDxpeditionHoundMode) }
    var manualFox by remember { mutableStateOf(GeneralVariables.manualDxpeditionFoxMode) }
    var foxHoldFrequency by remember { mutableStateOf(GeneralVariables.dxpeditionFoxHoldFrequency) }
    var foxTxSlots by remember { mutableStateOf(GeneralVariables.dxpeditionFoxTxSlots.coerceIn(1, 5)) }
    var foxManualFrequency by remember { mutableStateOf(GeneralVariables.dxpeditionFoxManualSlotFrequency) }
    var foxStartHz by rememberSaveable { mutableStateOf(GeneralVariables.dxpeditionFoxSlotStartHz.toString()) }
    var foxStepHz by rememberSaveable { mutableStateOf(GeneralVariables.dxpeditionFoxSlotStepHz.toString()) }
    var foxAutoSpecial by remember { mutableStateOf(GeneralVariables.dxpeditionFoxAutoSpecialMessage) }
    var foxCqFreeSlot by remember { mutableStateOf(GeneralVariables.dxpeditionFoxCqOnFreeSlot) }
    var excludedCallsigns by rememberSaveable { mutableStateOf(GeneralVariables.getExcludeCallsigns()) }
    var serviceTestStatus by remember { mutableStateOf("") }
    var cacheStatus by remember { mutableStateOf("") }

    fun persist(key: String, value: String) {
        mainViewModel.databaseOpr.writeConfig(key, value, null)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Ft8cnPageHeader(
                title = "设置",
                subtitle = "台站 · 解码 · 音频 · 时间",
            )
        }
        item {
            SettingsCard("时间同步") {
                ClockStatus(clock, DisciplinedClockRegistry.isAutomaticTransmitAllowed())
                SwitchRow(
                    title = "NTP 对时",
                    subtitle = "校准应用内UTC时间",
                    checked = ntpEnabled,
                ) { enabled ->
                    ntpEnabled = enabled
                    GeneralVariables.setNtpEnable(enabled)
                    persist("ntpEnable", if (enabled) "1" else "0")
                    if (enabled) mainViewModel.syncNtpTime()
                }
                Text("NTP 服务器", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(GeneralVariables.NTP_SERVER_ITEMS.toList()) { index, server ->
                        SelectionButton(ntpServerIndex == index, serverLabel(index, server)) {
                            ntpServerIndex = index
                            GeneralVariables.setNtpServerIndex(index)
                            persist("ntpServerIndex", index.toString())
                            if (GeneralVariables.ntpEnable && index != GeneralVariables.NTP_SERVER_INDEX_CUSTOM) {
                                mainViewModel.syncNtpTime()
                            }
                        }
                    }
                }
                if (ntpServerIndex == GeneralVariables.NTP_SERVER_INDEX_CUSTOM) {
                    OutlinedTextField(
                        value = customNtpServer,
                        onValueChange = { customNtpServer = it.trim().take(253) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("自定义 NTP 主机") },
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = customNtpServer.isNotBlank(),
                        onClick = {
                            GeneralVariables.setNtpCustomServer(customNtpServer)
                            persist("ntpCustomServer", customNtpServer)
                            if (GeneralVariables.ntpEnable) mainViewModel.syncNtpTime()
                        },
                    ) { Text("保存并同步") }
                }
                SwitchRow(
                    title = "GNSS 时间",
                    subtitle = "辅助NTP校准时间",
                    checked = featureSettings.gnssTimeEnabled,
                ) { scope.launch { graph.settings.setGnssTimeEnabled(it) } }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ntpEnabled,
                    onClick = { mainViewModel.syncNtpTime() },
                ) { Text("立即重新对时") }
                OutlinedTextField(
                    value = manualClockOffset,
                    onValueChange = { value ->
                        manualClockOffset = value.filterIndexed { index, character ->
                            character.isDigit() || (character == '-' && index == 0)
                        }.take(5)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("手动时钟微调（ms）") },
                    supportingText = { Text("范围:-7500…7500") },
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val offset = manualClockOffset.toIntOrNull()?.coerceIn(-7_500, 7_500) ?: 0
                        manualClockOffset = offset.toString()
                        UtcTimer.delay = offset
                    },
                ) { Text("应用微调") }
            }
        }
        item {
            SettingsCard("台站与音频频率") {
                OutlinedTextField(
                    value = callsign,
                    onValueChange = { callsign = it.uppercase().trim().take(16) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("我的呼号") },
                )
                OutlinedTextField(
                    value = grid,
                    onValueChange = { grid = it.uppercase().trim().take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("我的网格") },
                )
                OutlinedTextField(
                    value = baseFrequency,
                    onValueChange = { baseFrequency = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("音频基准频率（100–2900 Hz）") },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val frequency = baseFrequency.toFloatOrNull()?.coerceIn(100f, 2_900f) ?: 1_000f
                        GeneralVariables.myCallsign = callsign
                        if (callsign.isNotBlank()) {
                            Ft8Message.hashList.addHash(FT8Package.getHash22(callsign).toLong(), callsign)
                            Ft8Message.hashList.addHash(FT8Package.getHash12(callsign).toLong(), callsign)
                            Ft8Message.hashList.addHash(FT8Package.getHash10(callsign).toLong(), callsign)
                        }
                        GeneralVariables.setMyMaidenheadGrid(grid)
                        GeneralVariables.setBaseFrequency(frequency)
                        mainViewModel.ft8TransmitSignal.setBaseFrequency(frequency)
                        persist("callsign", callsign)
                        persist("grid", grid)
                        persist("freq", frequency.roundToInt().toString())
                    },
                ) { Text("保存台站设置") }
            }
        }
        item {
            SettingsCard("发射与显示") {
                OutlinedTextField(
                    value = callModifier,
                    onValueChange = { callModifier = it.uppercase().trim().take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = callModifier.isNotBlank() &&
                        !callModifier.matches(Regex("[0-9]{3}|[A-Z]{1,4}")),
                    label = { Text("呼叫修饰符") },
                    supportingText = { Text("留空，或 3 位数字 / 1–4 位字母") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = transmitDelay,
                        onValueChange = { transmitDelay = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("发射延迟 ms") },
                    )
                    OutlinedTextField(
                        value = pttDelay,
                        onValueChange = { pttDelay = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("PTT 延迟 ms") },
                    )
                }
                SwitchRow("同频发射", "TX 音频跟随当前 QSO 频率", sameFrequency) {
                    sameFrequency = it
                    GeneralVariables.synFrequency = it
                    persist("synFreq", if (it) "1" else "0")
                }
                SwitchRow("精简消息卡片", "保留相同消息字段，仅使用紧凑布局", compactMessages) {
                    compactMessages = it
                    GeneralVariables.simpleCallItemMode = it
                    persist("msgMode", if (it) "1" else "0")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = callModifier.isBlank() ||
                        callModifier.matches(Regex("[0-9]{3}|[A-Z]{1,4}")),
                    onClick = {
                        val txDelayMs = transmitDelay.toIntOrNull()?.coerceIn(0, 9_999) ?: 500
                        val pttDelayMs = pttDelay.toIntOrNull()?.coerceIn(0, 190) ?: 100
                        GeneralVariables.toModifier = callModifier
                        GeneralVariables.transmitDelay = txDelayMs
                        GeneralVariables.pttDelay = pttDelayMs
                        mainViewModel.ft8TransmitSignal.setTimer_sec(txDelayMs)
                        persist("toModifier", callModifier)
                        persist("transDelay", txDelayMs.toString())
                        persist("pttDelay", pttDelayMs.toString())
                    },
                ) { Text("保存发射设置") }
            }
        }
        item {
            SettingsCard("自动流程限制") {
                Text("发射监管", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(listOf(0, 5, 15, 25, 35, 45, 55, 65, 75, 85, 95)) { _, minutes ->
                        SelectionButton(
                            launchSupervisionMinutes == minutes,
                            if (minutes == 0) "关闭" else "${minutes}分",
                        ) {
                            launchSupervisionMinutes = minutes
                            GeneralVariables.launchSupervision = minutes * 60_000
                            GeneralVariables.launchSupervisionStart = com.bg7yoz.ft8cn.timer.UtcTimer.getSystemTime()
                            persist("launchSupervision", GeneralVariables.launchSupervision.toString())
                        }
                    }
                }
                Text("无回应后停止", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(listOf(0, 1, 2, 3, 5, 10, 15, 20, 30)) { _, count ->
                        SelectionButton(noReplyLimit == count, if (count == 0) "忽略" else "$count 次") {
                            noReplyLimit = count
                            GeneralVariables.noReplyLimit = count
                            GeneralVariables.noReplyCount = 0
                            persist("noReplyLimit", count.toString())
                        }
                    }
                }
                SwitchRow("DXpedition Hound 自动识别", "识别 Fox 消息后按 Hound 流程处理", autoDxpeditionHound) {
                    autoDxpeditionHound = it
                    GeneralVariables.autoDxpeditionHound = it
                    persist("autoDxpeditionHound", if (it) "1" else "0")
                }
            }
        }
        item {
            SettingsCard("DXpedition") {
                SwitchRow("Hound", "手动进入 Hound 流程", manualHound) { enabled ->
                    manualHound = enabled
                    if (enabled) {
                        manualFox = false
                        GeneralVariables.manualDxpeditionFoxMode = false
                        persist("manualDxpeditionFoxMode", "0")
                    }
                    GeneralVariables.manualDxpeditionHoundMode = enabled
                    persist("manualDxpeditionHoundMode", if (enabled) "1" else "0")
                    mainViewModel.ft8TransmitSignal.refreshSessionModeByCurrentTarget()
                }
                SwitchRow("Fox", "手动进入 Fox 多时隙流程", manualFox) { enabled ->
                    manualFox = enabled
                    if (enabled) {
                        manualHound = false
                        GeneralVariables.manualDxpeditionHoundMode = false
                        persist("manualDxpeditionHoundMode", "0")
                    }
                    GeneralVariables.manualDxpeditionFoxMode = enabled
                    persist("manualDxpeditionFoxMode", if (enabled) "1" else "0")
                    mainViewModel.ft8TransmitSignal.refreshSessionModeByCurrentTarget()
                }
                SwitchRow("保持 Fox 频率", "完成一次交换后不自动换频", foxHoldFrequency) {
                    foxHoldFrequency = it
                    GeneralVariables.dxpeditionFoxHoldFrequency = it
                    persist("dxpeditionFoxHoldFrequency", if (it) "1" else "0")
                }
                NumericChoice("Fox TX 时隙", foxTxSlots, 1..5) {
                    foxTxSlots = it
                    mainViewModel.ft8TransmitSignal.setDxpeditionFoxTxSlots(it)
                    persist("dxpeditionFoxTxSlots", it.toString())
                }
                SwitchRow("手动时隙频率", "关闭时使用标准 Fox 频率计划", foxManualFrequency) {
                    foxManualFrequency = it
                    val start = foxStartHz.toIntOrNull() ?: 1_300
                    val step = foxStepHz.toIntOrNull() ?: 60
                    mainViewModel.ft8TransmitSignal.setDxpeditionFoxSlotFrequencyConfig(it, start, step)
                    persist("dxpeditionFoxManualSlotFrequency", if (it) "1" else "0")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = foxStartHz,
                        onValueChange = { foxStartHz = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(1f),
                        enabled = foxManualFrequency,
                        singleLine = true,
                        label = { Text("起点 Hz") },
                    )
                    OutlinedTextField(
                        value = foxStepHz,
                        onValueChange = { foxStepHz = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f),
                        enabled = foxManualFrequency,
                        singleLine = true,
                        label = { Text("间隔 Hz") },
                    )
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = foxManualFrequency,
                    onClick = {
                        val start = foxStartHz.toIntOrNull() ?: 1_300
                        val step = foxStepHz.toIntOrNull() ?: 60
                        mainViewModel.ft8TransmitSignal.setDxpeditionFoxSlotFrequencyConfig(true, start, step)
                        foxStartHz = GeneralVariables.dxpeditionFoxSlotStartHz.toString()
                        foxStepHz = GeneralVariables.dxpeditionFoxSlotStepHz.toString()
                        persist("dxpeditionFoxSlotStartHz", foxStartHz)
                        persist("dxpeditionFoxSlotStepHz", foxStepHz)
                    },
                ) { Text("应用 Fox 频率计划") }
                SwitchRow("自动特殊消息", "Fox 自动编排 RR73 等消息", foxAutoSpecial) {
                    foxAutoSpecial = it
                    GeneralVariables.dxpeditionFoxAutoSpecialMessage = it
                    persist("dxpeditionFoxAutoSpecialMessage", if (it) "1" else "0")
                }
                SwitchRow("空闲时隙 CQ", "无候选时允许 Fox 发送 CQ", foxCqFreeSlot) {
                    foxCqFreeSlot = it
                    GeneralVariables.dxpeditionFoxCqOnFreeSlot = it
                    persist("dxpeditionFoxCqOnFreeSlot", if (it) "1" else "0")
                }
            }
        }
        item {
            SettingsCard("增强解码") {
                SwitchRow("深度解码", "允许 full/deep 与后续轮次", deepDecode) {
                    deepDecode = it
                    GeneralVariables.deepDecodeMode = it
                    persist("deepMode", if (it) "1" else "0")
                }
                NumericChoice("解码 Pass", decodePasses, 1..3) {
                    decodePasses = it
                    GeneralVariables.wsjtxDecodePassCount = it
                    persist("wsjtxDecodePassCount", it.toString())
                }
                NumericChoice("多轮解码", decodeRounds, 1..3) {
                    decodeRounds = it
                    GeneralVariables.wsjtxMultiDecodeRoundCount = it
                    persist("wsjtxMultiDecodeRoundCount", it.toString())
                }
                SensitivityChoice("解码灵敏度", decodeSensitivity) {
                    decodeSensitivity = it
                    GeneralVariables.wsjtxDecodeSensitivity = it
                    persist("wsjtxDecodeSensitivity", it.toString())
                }
                SensitivityChoice("QSO 频率窗口", qsoSensitivity) {
                    qsoSensitivity = it
                    GeneralVariables.wsjtxQsoFreqSensitivity = it
                    persist("wsjtxQsoFreqSensitivity", it.toString())
                }
                SwitchRow("提前解码", "只跑 fast pass，让自动流程更早拿到强信号", earlyDecode) {
                    earlyDecode = it
                    GeneralVariables.wsjtxEnableEarlyDecode = it
                    persist("wsjtxEnableEarlyDecode", if (it) "1" else "0")
                }
                SwitchRow("宽带 DX 搜索", "允许 0–3000 Hz 范围内的辅助搜索", widebandSearch) {
                    widebandSearch = it
                    GeneralVariables.wsjtxWidebandDxSearch = it
                    persist("wsjtxWidebandDxSearch", if (it) "1" else "0")
                }
            }
        }
        item {
            SettingsCard("自动呼叫与定向 CQ") {
                SwitchRow("自动关注 CQ", "将收到的 CQ 纳入自动流程", autoFollowCq) {
                    autoFollowCq = it
                    GeneralVariables.autoFollowCQ = it
                    persist("autoFollowCQ", if (it) "1" else "0")
                }
                SwitchRow("自动跟随呼叫", "自动流程跟随当前目标呼号", autoCallFollow) {
                    autoCallFollow = it
                    GeneralVariables.autoCallFollow = it
                    persist("autoCallFollow", if (it) "1" else "0")
                }
                SwitchRow("多 CQ 排队", "按下方规则管理同一时隙收到的呼叫", cqQueueEnabled) {
                    cqQueueEnabled = it
                    GeneralVariables.cqQueueEnabled = it
                    persist("cqQueueEnabled", if (it) "1" else "0")
                    mainViewModel.ft8TransmitSignal.updateCqQueueSettings()
                }
                Text("队列排序", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(CqRankMethod.values().toList()) { _, method ->
                        SelectionButton(cqRankMethod == method.value, method.toString()) {
                            cqRankMethod = method.value
                            GeneralVariables.cqRankMethod = method.value
                            persist("cqRankMethod", method.value.toString())
                            mainViewModel.ft8TransmitSignal.updateCqQueueSettings()
                        }
                    }
                }
                Text("队列上限", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(10, 20, 50, 100).forEach { size ->
                        SelectionButton(cqMaxQueueSize == size, size.toString(), Modifier.weight(1f)) {
                            cqMaxQueueSize = size
                            GeneralVariables.cqMaxQueueSize = size
                            persist("cqMaxQueueSize", size.toString())
                            mainViewModel.ft8TransmitSignal.updateCqQueueSettings()
                        }
                    }
                }
                OutlinedTextField(
                    value = directedCqPrefixes,
                    onValueChange = {
                        directedCqPrefixes = it.uppercase()
                            .filter { character -> character.isLetterOrDigit() || character in " ,/;" }
                            .take(128)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("定向 CQ 前缀") },
                    supportingText = { Text("例如 DX, AS, EU；留空表示不限制") },
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val normalized = directedCqPrefixes.trim()
                        directedCqPrefixes = normalized
                        GeneralVariables.cqDirectedCqPrefixes = normalized
                        persist("cqDirectedCqPrefixes", normalized)
                        mainViewModel.ft8TransmitSignal.updateCqQueueSettings()
                    },
                ) { Text("保存定向 CQ") }
            }
        }
        item {
            SettingsCard("音频输入 / 输出") {
                Text("采样率", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(12_000, 24_000, 48_000).forEach { rate ->
                        SelectionButton(audioRate == rate, "${rate / 1_000} kHz", Modifier.weight(1f)) {
                            audioRate = rate
                            GeneralVariables.audioSampleRate = rate
                            persist("audioRate", rate.toString())
                            mainViewModel.refreshRecorderSampleRate()
                        }
                    }
                }
                Text("输出位深", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SelectionButton(!audio32Bit, "16-bit", Modifier.weight(1f)) {
                        audio32Bit = false
                        GeneralVariables.audioOutput32Bit = false
                        persist("audioBits", "0")
                    }
                    SelectionButton(audio32Bit, "32-bit", Modifier.weight(1f)) {
                        audio32Bit = true
                        GeneralVariables.audioOutput32Bit = true
                        persist("audioBits", "1")
                    }
                }
                Text("24/48kHz输入后重采样到12KHz", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SettingsCard("接收、上报与保护") {
                SwitchRow("SWR 告警", "电台支持读回时监测驻波异常", swrAlarm) {
                    swrAlarm = it
                    GeneralVariables.swr_switch_on = it
                    persist("swrSwitch", if (it) "1" else "0")
                }
                SwitchRow("ALC 告警", "电台支持读回时监测 ALC 异常", alcAlarm) {
                    alcAlarm = it
                    GeneralVariables.alc_switch_on = it
                    persist("alcSwitch", if (it) "1" else "0")
                }
                SwitchRow("保存 SWL 消息", "保存未参与通联的接收消息", saveSwlMessages) {
                    saveSwlMessages = it
                    GeneralVariables.saveSWLMessage = it
                    persist("saveSWL", if (it) "1" else "0")
                }
                SwitchRow("保存 SWL QSO", "保存观察到的第三方完整通联", saveSwlQso) {
                    saveSwlQso = it
                    GeneralVariables.saveSWL_QSO = it
                    persist("saveSWLQSO", if (it) "1" else "0")
                }
                SwitchRow("PSK Reporter", "后台批量上报接收报告", pskReporterEnabled) {
                    pskReporterEnabled = it
                    GeneralVariables.enablePskReporter = it
                    persist("enablePskReporter", if (it) "1" else "0")
                }
                OutlinedTextField(
                    value = pskReporterHost,
                    onValueChange = { pskReporterHost = it.trim().take(253) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = pskReporterEnabled,
                    label = { Text("PSK Reporter 主机") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pskReporterPort,
                        onValueChange = { pskReporterPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = pskReporterEnabled,
                        label = { Text("端口") },
                    )
                    OutlinedTextField(
                        value = pskReporterFlushSeconds,
                        onValueChange = { pskReporterFlushSeconds = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = pskReporterEnabled,
                        label = { Text("批次秒数") },
                    )
                }
                OutlinedTextField(
                    value = pskReporterAntenna,
                    onValueChange = { pskReporterAntenna = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = pskReporterEnabled,
                    label = { Text("天线说明") },
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pskReporterEnabled && pskReporterHost.isNotBlank(),
                    onClick = {
                        val port = pskReporterPort.toIntOrNull()?.coerceIn(1, 65_535) ?: 4_739
                        val interval = pskReporterFlushSeconds.toIntOrNull()?.coerceIn(5, 300) ?: 15
                        pskReporterPort = port.toString()
                        pskReporterFlushSeconds = interval.toString()
                        GeneralVariables.pskReporterHost = pskReporterHost
                        GeneralVariables.pskReporterPort = port
                        GeneralVariables.pskReporterAntennaInfo = pskReporterAntenna
                        GeneralVariables.pskReporterFlushIntervalMs = interval * 1_000
                        persist("pskReporterHost", pskReporterHost)
                        persist("pskReporterPort", port.toString())
                        persist("pskReporterAntennaInfo", pskReporterAntenna)
                        persist("pskReporterFlushIntervalMs", (interval * 1_000).toString())
                    },
                ) { Text("保存上报参数") }
                OutlinedTextField(
                    value = excludedCallsigns,
                    onValueChange = { excludedCallsigns = it.uppercase().take(512) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("排除呼号前缀") },
                    supportingText = { Text("逗号或空格分隔，例如 R0, TEST") },
                )
                OutlinedButton(
                    onClick = {
                        GeneralVariables.addExcludedCallsigns(excludedCallsigns)
                        excludedCallsigns = GeneralVariables.getExcludeCallsigns()
                        persist("excludedCallsigns", excludedCallsigns)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存过滤规则") }
            }
        }
        item {
            SettingsCard("日志上传（Cloudlog / QRZ）") {
                SwitchRow("Cloudlog 自动上传", "完成 QSO 后上传到已配置的 Cloudlog 实例", cloudlogEnabled) {
                    cloudlogEnabled = it
                    GeneralVariables.enableCloudlog = it
                    persist("enableCloudlog", if (it) "1" else "0")
                }
                OutlinedTextField(
                    value = cloudlogAddress,
                    onValueChange = { cloudlogAddress = it.trim().take(512) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Cloudlog 服务器地址") },
                )
                OutlinedTextField(
                    value = cloudlogApiKey,
                    onValueChange = { cloudlogApiKey = it.trim().take(256) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Cloudlog API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = cloudlogStationId,
                    onValueChange = { cloudlogStationId = it.trim().take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Cloudlog Station Profile ID") },
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        GeneralVariables.cloudlogServerAddress = cloudlogAddress
                        GeneralVariables.cloudlogApiKey = cloudlogApiKey
                        GeneralVariables.cloudlogStationID = cloudlogStationId
                        persist("cloudlogServerAddress", cloudlogAddress)
                        persist("cloudlogApiKey", cloudlogApiKey)
                        persist("cloudlogStationID", cloudlogStationId)
                    },
                ) { Text("保存 Cloudlog") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = cloudlogAddress.isNotBlank() && cloudlogApiKey.isNotBlank(),
                    onClick = {
                        GeneralVariables.cloudlogServerAddress = cloudlogAddress
                        GeneralVariables.cloudlogApiKey = cloudlogApiKey
                        GeneralVariables.cloudlogStationID = cloudlogStationId
                        scope.launch {
                            serviceTestStatus = "正在测试 Cloudlog…"
                            serviceTestStatus = if (withContext(Dispatchers.IO) {
                                    ThirdPartyService.CheckCloudlogConnection()
                                }
                            ) "Cloudlog 连接正常" else "Cloudlog 连接失败"
                        }
                    },
                ) { Text("测试 Cloudlog") }

                SwitchRow("QRZ Logbook 自动上传", "完成 QSO 后上传到 QRZ Logbook", qrzEnabled) {
                    qrzEnabled = it
                    GeneralVariables.enableQRZ = it
                    persist("enableQRZ", if (it) "1" else "0")
                }
                OutlinedTextField(
                    value = qrzApiKey,
                    onValueChange = { qrzApiKey = it.trim().take(256) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("QRZ API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        GeneralVariables.qrzApiKey = qrzApiKey
                        persist("qrzApiKey", qrzApiKey)
                    },
                ) { Text("保存 QRZ") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = qrzApiKey.isNotBlank(),
                    onClick = {
                        GeneralVariables.qrzApiKey = qrzApiKey
                        scope.launch {
                            serviceTestStatus = "正在测试 QRZ…"
                            serviceTestStatus = if (withContext(Dispatchers.IO) {
                                    ThirdPartyService.CheckQRZConnection()
                                }
                            ) "QRZ 连接正常" else "QRZ 连接失败"
                        }
                    },
                ) { Text("测试 QRZ") }
                if (serviceTestStatus.isNotBlank()) {
                    Text(serviceTestStatus, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "密钥仅写入现有本地配置，不在界面或诊断日志中明文显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("实验编解码器") {
                Text("与 FT8/FT4/Q65 core 保持独立；关闭时不进入 experimental 解码路径。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        GeneralVariables.EXP_CODEC_MODE_OFF to "关闭",
                        GeneralVariables.EXP_CODEC_MODE_4FSK to "4FSK",
                        GeneralVariables.EXP_CODEC_MODE_CPFSK to "CPFSK",
                    ).forEach { (mode, label) ->
                        SelectionButton(experimentalMode == mode, label, Modifier.weight(1f)) {
                            experimentalMode = mode
                            GeneralVariables.experimentalCodecMode = mode
                            GeneralVariables.experimentalCodecDebugMode = mode != GeneralVariables.EXP_CODEC_MODE_OFF
                            persist("expCodecMode", mode.toString())
                            persist("expCodecDebug", if (mode == GeneralVariables.EXP_CODEC_MODE_OFF) "0" else "1")
                        }
                    }
                }
            }
        }
        item {
            SettingsCard("数据维护") {
                Text(
                    "删除操作沿用原有确认对话框；不会删除 QSO 正式日志或用户配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            ClearCacheDataDialog(
                                context,
                                context as Activity,
                                mainViewModel.databaseOpr,
                                ClearCacheDataDialog.CACHE_MODE.FOLLOW_DATA,
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("关注列表", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            ClearCacheDataDialog(
                                context,
                                context as Activity,
                                mainViewModel.databaseOpr,
                                ClearCacheDataDialog.CACHE_MODE.SWL_MSG,
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("SWL 消息", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            ClearCacheDataDialog(
                                context,
                                context as Activity,
                                mainViewModel.databaseOpr,
                                ClearCacheDataDialog.CACHE_MODE.SWL_QSO,
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("SWL QSO", maxLines = 1) }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { GeneralVariables.clearCache(context) }
                            cacheStatus = "共享临时文件已清理"
                        }
                    },
                ) { Text("清理共享临时文件") }
                if (cacheStatus.isNotBlank()) {
                    Text(cacheStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SettingsCard("帮助") {
                Text("FT8CN ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, FAQActivity::class.java)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("常见问题") }
                    OutlinedButton(
                        onClick = { HelpDialog(context, context as Activity, "readme.txt", true).show() },
                        modifier = Modifier.weight(1f),
                    ) { Text("关于") }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Ft8cnPanel(title = title, content = content)
}

@Composable
private fun ClockStatus(clock: ClockSnapshot, automaticAllowed: Boolean) {
    ClockRow("来源", clock.source.name)
    ClockRow("偏差", "${clock.offsetMillis.roundToInt()} ms")
    ClockRow("不确定度", "±${clock.uncertaintyMillis.roundToInt()} ms")
    ClockRow("样本年龄", "${clock.sampleAgeMillis / 1_000} s")
    ClockRow("自动 TX", if (automaticAllowed) "允许" else "已阻止")
}

@Composable
private fun ClockRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun NumericChoice(title: String, selected: Int, values: IntRange, onSelected: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        values.forEach { value ->
            SelectionButton(selected == value, value.toString(), Modifier.weight(1f)) { onSelected(value) }
        }
    }
}

@Composable
private fun SensitivityChoice(title: String, selected: Int, onSelected: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf("低", "标准", "高").forEachIndexed { index, label ->
            SelectionButton(selected == index, label, Modifier.weight(1f)) { onSelected(index) }
        }
    }
}

@Composable
private fun SelectionButton(
    selected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1) }
}

private fun serverLabel(index: Int, value: String): String = when (index) {
    GeneralVariables.NTP_SERVER_INDEX_AUTO -> "自动"
    GeneralVariables.NTP_SERVER_INDEX_CUSTOM -> "自定义"
    else -> value.substringBefore('.')
}
