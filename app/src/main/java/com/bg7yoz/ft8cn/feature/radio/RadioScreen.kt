package com.bg7yoz.ft8cn.feature.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.core.radio.NativeHamlibRadioController
import com.bg7yoz.ft8cn.core.radio.RadioMode
import com.bg7yoz.ft8cn.core.radio.RadioModel
import com.bg7yoz.ft8cn.core.radio.RadioVfo
import com.bg7yoz.ft8cn.core.radio.SplitStrategy
import com.bg7yoz.ft8cn.core.radio.UsbCatEndpoint
import com.bg7yoz.ft8cn.core.radio.UsbCatEndpointScanner
import com.bg7yoz.ft8cn.data.settings.FeatureSettings
import com.bg7yoz.ft8cn.database.OperationBand
import com.bg7yoz.ft8cn.feature.shell.Ft8cnPageHeader
import com.bg7yoz.ft8cn.feature.shell.Ft8cnPanel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 按 WSJT-X 电台设置维度配置 Hamlib，不再暴露机型私有 CAT 实现。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RadioScreen(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val graph = remember(context) { FeatureAppGraph.from(context) }
    val settings by graph.settings.state.collectAsStateWithLifecycle(initialValue = FeatureSettings())
    val radioState by graph.radioController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val nativeAvailable = remember { NativeHamlibRadioController.isAvailable() }
    val nativeVersion = remember { NativeHamlibRadioController.version() }
    var models by remember { mutableStateOf<List<RadioModel>>(emptyList()) }
    var usbEndpoints by remember { mutableStateOf<List<UsbCatEndpoint>>(emptyList()) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var endpointMenuExpanded by remember { mutableStateOf(false) }
    var modelFilter by rememberSaveable { mutableStateOf("") }
    var backend by rememberSaveable { mutableStateOf(settings.radioBackend) }
    var selectedModelId by rememberSaveable { mutableStateOf(settings.hamlibModelId) }
    var selectedModelName by rememberSaveable { mutableStateOf(settings.hamlibModelName) }
    var endpoint by rememberSaveable { mutableStateOf(settings.hamlibEndpoint) }
    var host by rememberSaveable { mutableStateOf(settings.rigctldHost) }
    var port by rememberSaveable { mutableStateOf(settings.rigctldPort.toString()) }
    var pollInterval by rememberSaveable { mutableStateOf(settings.hamlibPollIntervalMs.toString()) }
    var baud by rememberSaveable { mutableStateOf(settings.hamlibBaud.toString()) }
    var dataBits by rememberSaveable { mutableStateOf(settings.hamlibDataBits.toString()) }
    var stopBits by rememberSaveable { mutableStateOf(settings.hamlibStopBits.toString()) }
    var handshake by rememberSaveable { mutableStateOf(settings.hamlibHandshake) }
    var forceDtr by rememberSaveable { mutableStateOf(settings.hamlibForceDtr) }
    var forceRts by rememberSaveable { mutableStateOf(settings.hamlibForceRts) }
    var pttMethod by rememberSaveable { mutableStateOf(settings.hamlibPttMethod) }
    var pttEndpoint by rememberSaveable { mutableStateOf(settings.hamlibPttEndpoint) }
    var audioSource by rememberSaveable { mutableStateOf(settings.hamlibAudioSource) }
    var dataMode by rememberSaveable { mutableStateOf(settings.hamlibDataMode) }
    var txDelay by rememberSaveable { mutableStateOf(settings.hamlibTxDelayMs.toString()) }
    var autoPowerOn by rememberSaveable { mutableStateOf(settings.hamlibAutoPowerOn) }
    var autoPowerOff by rememberSaveable { mutableStateOf(settings.hamlibAutoPowerOff) }
    var querySMeter by rememberSaveable { mutableStateOf(settings.hamlibQuerySMeter) }
    var rxFrequency by rememberSaveable { mutableStateOf("") }
    var txFrequency by rememberSaveable { mutableStateOf("") }
    var presetMode by rememberSaveable { mutableStateOf("FT8") }
    var status by remember { mutableStateOf("未连接") }

    LaunchedEffect(Unit) {
        models = graph.radioController.discoverModels()
        usbEndpoints = withContext(Dispatchers.IO) { UsbCatEndpointScanner.scan(context) }
        if (nativeAvailable && models.isEmpty()) status = "Hamlib 未返回电台型号"
    }
    LaunchedEffect(settings, radioState.connected) {
        if (!radioState.connected) {
            backend = settings.radioBackend
            selectedModelId = settings.hamlibModelId
            selectedModelName = settings.hamlibModelName
            endpoint = settings.hamlibEndpoint
            host = settings.rigctldHost
            port = settings.rigctldPort.toString()
            pollInterval = settings.hamlibPollIntervalMs.toString()
            baud = settings.hamlibBaud.toString()
            dataBits = settings.hamlibDataBits.toString()
            stopBits = settings.hamlibStopBits.toString()
            handshake = settings.hamlibHandshake
            forceDtr = settings.hamlibForceDtr
            forceRts = settings.hamlibForceRts
            pttMethod = settings.hamlibPttMethod
            pttEndpoint = settings.hamlibPttEndpoint
            audioSource = settings.hamlibAudioSource
            dataMode = settings.hamlibDataMode
            txDelay = settings.hamlibTxDelayMs.toString()
            autoPowerOn = settings.hamlibAutoPowerOn
            autoPowerOff = settings.hamlibAutoPowerOff
            querySMeter = settings.hamlibQuerySMeter
        }
    }
    LaunchedEffect(radioState.rxFrequencyHz, radioState.txFrequencyHz) {
        if (radioState.rxFrequencyHz > 0) rxFrequency = radioState.rxFrequencyHz.toString()
        if (radioState.txFrequencyHz > 0) txFrequency = radioState.txFrequencyHz.toString()
    }

    val visibleModels = models.asSequence()
        .filter { it.backend != "dummy/netrigctl" }
        .filter {
            modelFilter.isBlank() ||
                "${it.manufacturer} ${it.model}".contains(modelFilter.trim(), ignoreCase = true)
        }
        .sortedWith(compareBy<RadioModel>({ it.manufacturer.lowercase() }, { it.model.lowercase() }, { it.id }))
        .toList()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Ft8cnPageHeader(
                title = "电台",
                subtitle = "Rig · CAT · PTT · Split",
            )
        }
        item {
            RadioCard("工作频率") {
                Text("常用频率", style = MaterialTheme.typography.titleSmall)
                ChoiceRow(
                    value = presetMode,
                    options = listOf("FT8" to "FT8", "FT4" to "FT4", "Q65" to "Q65"),
                    enabled = !radioState.transmitting,
                    onSelected = { presetMode = it },
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        COMMON_WORKING_FREQUENCIES.filter { it.mode == presetMode },
                        key = { "${it.mode}-${it.frequencyHz}" },
                    ) { preset ->
                        OutlinedButton(
                            enabled = !radioState.transmitting,
                            onClick = {
                                rxFrequency = preset.frequencyHz.toString()
                                txFrequency = preset.frequencyHz.toString()
                            },
                        ) {
                            Text("${preset.band} ${preset.mhz}", maxLines = 1)
                        }
                    }
                }
                Text(
                    "Q65 频点按常见 EME/弱信号活动频率列出，使用前请核对本地区频率规划。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rxFrequency,
                        onValueChange = { rxFrequency = it.filter(Char::isDigit).take(12) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("RX Hz") },
                    )
                    OutlinedTextField(
                        value = txFrequency,
                        onValueChange = { txFrequency = it.filter(Char::isDigit).take(12) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("TX Hz") },
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = radioState.connected && !radioState.transmitting &&
                        (rxFrequency.toLongOrNull() ?: 0L) > 0L,
                    onClick = {
                        scope.launch {
                            val rx = rxFrequency.toLongOrNull() ?: 0L
                            val tx = txFrequency.toLongOrNull() ?: rx
                            status = graph.radioTransactionCoordinator.setIdleFrequency(rx, tx).fold(
                                onSuccess = {
                                    GeneralVariables.band = rx
                                    GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(rx)
                                    GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex)
                                    mainViewModel.databaseOpr.writeConfig("bandFreq", rx.toString(), null)
                                    mainViewModel.databaseOpr.getAllQSLCallsigns()
                                    "频率已应用并读回"
                                },
                                onFailure = { "频率设置失败：${it.message}" },
                            )
                        }
                    },
                ) { Text(if (radioState.connected) "应用到电台" else "连接电台后应用") }
            }
        }
        item {
            RadioCard("连接与电台型号") {
                ChoiceRow(
                    value = backend,
                    options = listOf("NATIVE" to "内置 Hamlib", "RIGCTLD" to "rigctld"),
                    enabled = !radioState.connected,
                    onSelected = { backend = it },
                )
                Text(
                    if (nativeAvailable) "内置 Hamlib $nativeVersion 可用" else "当前 ABI 未包含内置 Hamlib",
                    color = if (nativeAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (backend == "NATIVE") {
                    Text(
                        "已加载 ${models.size} 个型号 · 当前显示 ${visibleModels.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = modelFilter,
                        onValueChange = { modelFilter = it.take(40) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("搜索电台型号") },
                        enabled = !radioState.connected,
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { modelMenuExpanded = true },
                            enabled = !radioState.connected && visibleModels.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (selectedModelId > 0) "$selectedModelName (#$selectedModelId)"
                                else "选择电台型号",
                                maxLines = 1,
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false },
                        ) {
                            visibleModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text("${model.manufacturer} ${model.model} (#${model.id})") },
                                    onClick = {
                                        selectedModelId = model.id
                                        selectedModelName = "${model.manufacturer} ${model.model}".trim()
                                        modelMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { endpointMenuExpanded = true },
                                enabled = !radioState.connected && usbEndpoints.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    usbEndpoints.firstOrNull { it.token == endpoint }?.label
                                        ?: if (usbEndpoints.isEmpty()) "未发现 USB CAT" else "选择 USB CAT",
                                    maxLines = 1,
                                )
                            }
                            DropdownMenu(
                                expanded = endpointMenuExpanded,
                                onDismissRequest = { endpointMenuExpanded = false },
                            ) {
                                usbEndpoints.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(candidate.label) },
                                        onClick = {
                                            endpoint = candidate.token
                                            endpointMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            enabled = !radioState.connected,
                            onClick = {
                                scope.launch {
                                    usbEndpoints = withContext(Dispatchers.IO) { UsbCatEndpointScanner.scan(context) }
                                    status = if (usbEndpoints.isEmpty()) "未发现可用 USB 串口" else "已发现 ${usbEndpoints.size} 个 USB CAT 端点"
                                }
                            },
                        ) { Text("扫描") }
                    }
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.take(253) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("CAT 端点（USB 下拉、串口路径或 host:port）") },
                        enabled = !radioState.connected,
                    )
                    ChoiceRow(
                        value = pollInterval,
                        options = listOf("500" to "0.5s", "1000" to "1s", "2000" to "2s", "5000" to "5s"),
                        enabled = !radioState.connected,
                        onSelected = { pollInterval = it },
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.take(253) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("rigctld 主机") },
                            enabled = !radioState.connected,
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.weight(0.5f),
                            singleLine = true,
                            label = { Text("端口") },
                            enabled = !radioState.connected,
                        )
                    }
                }
            }
        }
        if (backend == "NATIVE") {
            item {
                RadioCard("CAT 串口参数") {
                    Text("波特率", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        value = baud,
                        options = listOf("1200", "2400", "4800", "9600", "19200", "38400", "57600", "115200")
                            .map { it to it },
                        enabled = !radioState.connected,
                        onSelected = { baud = it },
                    )
                    Text("数据位", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        value = dataBits,
                        options = listOf("0" to "默认", "7" to "7", "8" to "8"),
                        enabled = !radioState.connected,
                        onSelected = { dataBits = it },
                    )
                    Text("停止位", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        value = stopBits,
                        options = listOf("0" to "默认", "1" to "1", "2" to "2"),
                        enabled = !radioState.connected,
                        onSelected = { stopBits = it },
                    )
                    Text("握手", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        value = handshake,
                        options = listOf(
                            "DEFAULT" to "默认",
                            "NONE" to "None",
                            "XON_XOFF" to "XON/XOFF",
                            "HARDWARE" to "硬件",
                        ),
                        enabled = !radioState.connected,
                        onSelected = { handshake = it },
                    )
                    Text("强制控制线", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("DTR", style = MaterialTheme.typography.bodySmall)
                            ChoiceRow(
                                value = forceDtr,
                                options = controlLineOptions(),
                                enabled = !radioState.connected,
                                onSelected = { forceDtr = it },
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RTS", style = MaterialTheme.typography.bodySmall)
                            ChoiceRow(
                                value = forceRts,
                                options = controlLineOptions(),
                                enabled = !radioState.connected,
                                onSelected = { forceRts = it },
                            )
                        }
                    }
                }
            }
            item {
                RadioCard("PTT、音频与模式") {
                    Text("PTT 方法", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        value = pttMethod,
                        options = listOf("VOX" to "VOX", "CAT" to "CAT", "DTR" to "DTR", "RTS" to "RTS"),
                        enabled = !radioState.connected,
                        onSelected = { pttMethod = it },
                    )
                    if (pttMethod == "DTR" || pttMethod == "RTS") {
                        OutlinedTextField(
                            value = pttEndpoint,
                            onValueChange = { pttEndpoint = it.take(253) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("PTT 端点（留空则与 CAT 共用）") },
                            enabled = !radioState.connected,
                        )
                    }
                    Text("发射音频源", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        value = audioSource,
                        options = listOf("FRONT" to "前方/麦克风", "REAR_DATA" to "后方/数据口"),
                        enabled = !radioState.connected,
                        onSelected = { audioSource = it },
                    )
                    Text("数据模式", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        value = dataMode,
                        options = listOf("NONE" to "None", "USB" to "USB", "DATA_USB" to "数据/Pkt"),
                        enabled = !radioState.connected,
                        onSelected = { dataMode = it },
                    )
                    OutlinedTextField(
                        value = txDelay,
                        onValueChange = { txDelay = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("发射延迟（ms）") },
                        enabled = !radioState.connected,
                    )
                    SettingSwitch("打开时开启设备电源", autoPowerOn, !radioState.connected) { autoPowerOn = it }
                    SettingSwitch("关闭时关闭设备电源", autoPowerOff, !radioState.connected) { autoPowerOff = it }
                    SettingSwitch("轮询 S 表", querySMeter, !radioState.connected) { querySMeter = it }
                }
            }
        }
        item {
            RadioCard("连接测试") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !radioState.connected && (backend != "NATIVE" || nativeAvailable),
                        onClick = {
                            scope.launch {
                                status = saveAndConnect(
                                    graph = graph,
                                    backend = backend,
                                    selectedModelId = selectedModelId,
                                    selectedModelName = selectedModelName,
                                    endpoint = endpoint,
                                    host = host,
                                    port = port,
                                    pollInterval = pollInterval,
                                    baud = baud,
                                    dataBits = dataBits,
                                    stopBits = stopBits,
                                    handshake = handshake,
                                    forceDtr = forceDtr,
                                    forceRts = forceRts,
                                    pttMethod = pttMethod,
                                    pttEndpoint = pttEndpoint,
                                    audioSource = audioSource,
                                    dataMode = dataMode,
                                    txDelay = txDelay,
                                    autoPowerOn = autoPowerOn,
                                    autoPowerOff = autoPowerOff,
                                    querySMeter = querySMeter,
                                )
                            }
                        },
                    ) { Text("测试 CAT / 连接") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = radioState.connected,
                        onClick = {
                            scope.launch {
                                graph.radioController.disconnect()
                                status = "已安全断开"
                            }
                        },
                    ) { Text("断开") }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = radioState.connected && pttMethod != "VOX",
                    onClick = {
                        scope.launch {
                            val bridge = graph.radioTransmitBridge
                            if (!bridge.requestPtt(true)) {
                                status = "PTT 测试失败：电台事务未能启动"
                                return@launch
                            }
                            try {
                                kotlinx.coroutines.delay(300)
                                status = "PTT 测试完成"
                            } finally {
                                if (!bridge.abortTransmit("PTT 测试结束")) {
                                    status = "PTT 测试失败：无法确认 PTT 已关闭"
                                }
                            }
                        }
                    },
                ) { Text("测试 PTT（300 ms）") }
                Text(status, color = if (radioState.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            RadioCard("状态与异频") {
                RadioRow("连接", if (radioState.connected) radioState.model else "未连接")
                RadioRow("传输", radioState.transport.name)
                RadioRow("RX", radioState.rxFrequencyHz.toFrequencyText())
                RadioRow("TX", radioState.txFrequencyHz.toFrequencyText())
                RadioRow("模式", "${radioState.mode.hamlibName} / ${radioState.passbandHz} Hz")
                RadioRow("split", if (radioState.splitEnabled) "已启用" else "关闭")
                RadioRow("PTT", if (radioState.transmitting) "发射中" else "接收")
                if (querySMeter) {
                    RadioRow("S 表", radioState.strengthDbm?.let { "%.1f dB".format(it) } ?: "电台不支持")
                }
                if (radioState.capabilities.canSetVfo) {
                    Text("VFO", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        value = radioState.activeVfo.name,
                        options = radioState.capabilities.supportedVfos
                            .sortedBy { it.ordinal }
                            .map { it.name to it.displayName() },
                        enabled = radioState.connected && !radioState.transmitting,
                        onSelected = { selected ->
                            scope.launch {
                                val vfo = RadioVfo.valueOf(selected)
                                status = graph.radioController.setVfo(vfo).fold(
                                    onSuccess = { "VFO 已切换为 ${vfo.displayName()}" },
                                    onFailure = { "VFO 切换失败：${it.message}" },
                                )
                            }
                        },
                    )
                }
                if (radioState.capabilities.canSplit) {
                    SettingSwitch(
                        label = "Rig Split",
                        checked = radioState.splitEnabled,
                        enabled = radioState.connected && !radioState.transmitting,
                    ) { enabled ->
                        scope.launch {
                            status = graph.radioController.setSplit(enabled, RadioVfo.B).fold(
                                onSuccess = { if (enabled) "Rig Split 已启用，TX VFO B" else "Rig Split 已关闭" },
                                onFailure = { "Rig Split 失败：${it.message}" },
                            )
                        }
                    }
                }
                if (radioState.capabilities.canSetPower) {
                    Text("RF 功率", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf(5, 10, 25, 50, 100), key = { it }) { percent ->
                            val selected = radioState.powerFraction?.let {
                                kotlin.math.abs(it - percent / 100f) < 0.01f
                            } == true
                            ChoiceButton(selected, "$percent%", !radioState.transmitting) {
                                scope.launch {
                                    status = graph.radioController.setPower(percent / 100f).fold(
                                        onSuccess = { "RF 功率已设为 $percent%" },
                                        onFailure = { "功率设置失败：${it.message}" },
                                    )
                                }
                            }
                        }
                    }
                }
                Text("TX 频率方法", style = MaterialTheme.typography.titleSmall)
                ChoiceRow(
                    value = settings.splitStrategy,
                    options = SplitStrategy.values().map { it.name to it.displayName() },
                    enabled = !radioState.transmitting,
                    onSelected = { scope.launch { graph.settings.setSplitStrategy(it) } },
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = radioState.connected,
                    onClick = {
                        scope.launch {
                            status = graph.radioController.refreshState().fold(
                                onSuccess = { "读回成功" },
                                onFailure = { "读回失败：${it.message}" },
                            )
                        }
                    },
                ) { Text("刷新读回") }
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = radioState.connected,
                onClick = {
                    scope.launch {
                        status = graph.radioController.emergencyStop().fold(
                            onSuccess = { "PTT 已撤销" },
                            onFailure = { "紧急停止失败：${it.message}" },
                        )
                    }
                },
            ) { Text("紧急停止 PTT") }
        }
    }
}

@Suppress("LongParameterList")
private suspend fun saveAndConnect(
    graph: FeatureAppGraph,
    backend: String,
    selectedModelId: Long,
    selectedModelName: String,
    endpoint: String,
    host: String,
    port: String,
    pollInterval: String,
    baud: String,
    dataBits: String,
    stopBits: String,
    handshake: String,
    forceDtr: String,
    forceRts: String,
    pttMethod: String,
    pttEndpoint: String,
    audioSource: String,
    dataMode: String,
    txDelay: String,
    autoPowerOn: Boolean,
    autoPowerOff: Boolean,
    querySMeter: Boolean,
): String = runCatching {
    graph.settings.setRadioBackend(backend)
    val profileId = if (backend == "NATIVE") {
        graph.settings.setNativeHamlibProfile(
            modelId = selectedModelId,
            modelName = selectedModelName,
            endpoint = endpoint,
            pollIntervalMs = pollInterval.toIntOrNull() ?: 1_000,
            baud = baud.toIntOrNull() ?: 4_800,
            dataBits = dataBits.toIntOrNull() ?: 0,
            stopBits = stopBits.toIntOrNull() ?: 0,
            handshake = handshake,
            forceDtr = forceDtr,
            forceRts = forceRts,
            pttMethod = pttMethod,
            pttEndpoint = pttEndpoint,
            audioSource = audioSource,
            dataMode = dataMode,
            txDelayMs = txDelay.toIntOrNull() ?: 100,
            autoPowerOn = autoPowerOn,
            autoPowerOff = autoPowerOff,
            querySMeter = querySMeter,
        )
        selectedModelId
    } else {
        val parsedPort = port.toIntOrNull() ?: error("rigctld 端口无效")
        graph.settings.setRigctldProfile(host, parsedPort)
        1L
    }
    graph.radioController.connect(profileId).getOrThrow()
    if (backend == "NATIVE" && dataMode != "NONE") {
        val mode = if (dataMode == "USB") RadioMode.USB else RadioMode.DATA_USB
        graph.radioController.setMode(mode, 3_000).getOrThrow()
    }
    "Hamlib 已连接并完成读回"
}.fold(
    onSuccess = { it },
    onFailure = { "连接失败：${it.message}" },
)

@Composable
private fun RadioCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Ft8cnPanel(title = title, content = content)
}

@Composable
private fun ChoiceRow(
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options, key = { it.first }) { option ->
            ChoiceButton(value == option.first, option.second, enabled) { onSelected(option.first) }
        }
    }
}

@Composable
private fun ChoiceButton(selected: Boolean, label: String, enabled: Boolean, onClick: () -> Unit) {
    if (selected) Button(enabled = enabled, onClick = onClick) { Text(label) }
    else OutlinedButton(enabled = enabled, onClick = onClick) { Text(label) }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChanged)
    }
}

@Composable
private fun RadioRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun controlLineOptions(): List<Pair<String, String>> =
    listOf("DEFAULT" to "默认", "HIGH" to "高", "LOW" to "低")

private fun Long.toFrequencyText(): String =
    if (this <= 0L) "--" else String.format(Locale.US, "%.6f MHz", this / 1_000_000.0)

private fun SplitStrategy.displayName(): String = when (this) {
    SplitStrategy.NONE -> "同频"
    SplitStrategy.RIG_SPLIT -> "Split"
    SplitStrategy.FAKE_IT -> "Fake It"
}

private fun RadioVfo.displayName(): String = when (this) {
    RadioVfo.CURRENT -> "Current"
    RadioVfo.A -> "VFO A"
    RadioVfo.B -> "VFO B"
}

private data class WorkingFrequencyPreset(
    val mode: String,
    val band: String,
    val frequencyHz: Long,
) {
    val mhz: String get() = String.format(Locale.US, "%.4f", frequencyHz / 1_000_000.0)
}

/** 预设只负责快速填入，电台仍以用户确认后的 RX/TX 输入为准。 */
private val COMMON_WORKING_FREQUENCIES = listOf(
    WorkingFrequencyPreset("FT8", "160m", 1_840_000L),
    WorkingFrequencyPreset("FT8", "80m", 3_573_000L),
    WorkingFrequencyPreset("FT8", "60m", 5_357_000L),
    WorkingFrequencyPreset("FT8", "40m", 7_074_000L),
    WorkingFrequencyPreset("FT8", "30m", 10_136_000L),
    WorkingFrequencyPreset("FT8", "20m", 14_074_000L),
    WorkingFrequencyPreset("FT8", "17m", 18_100_000L),
    WorkingFrequencyPreset("FT8", "15m", 21_074_000L),
    WorkingFrequencyPreset("FT8", "12m", 24_915_000L),
    WorkingFrequencyPreset("FT8", "10m", 28_074_000L),
    WorkingFrequencyPreset("FT8", "6m", 50_313_000L),
    WorkingFrequencyPreset("FT8", "2m", 144_174_000L),
    WorkingFrequencyPreset("FT8", "70cm", 432_174_000L),
    WorkingFrequencyPreset("FT4", "80m", 3_575_000L),
    WorkingFrequencyPreset("FT4", "60m", 5_357_000L),
    WorkingFrequencyPreset("FT4", "40m", 7_047_500L),
    WorkingFrequencyPreset("FT4", "30m", 10_104_000L),
    WorkingFrequencyPreset("FT4", "20m", 14_080_000L),
    WorkingFrequencyPreset("FT4", "17m", 18_104_000L),
    WorkingFrequencyPreset("FT4", "15m", 21_140_000L),
    WorkingFrequencyPreset("FT4", "12m", 24_919_000L),
    WorkingFrequencyPreset("FT4", "10m", 28_180_000L),
    WorkingFrequencyPreset("FT4", "6m", 50_318_000L),
    WorkingFrequencyPreset("FT4", "2m", 144_170_000L),
    WorkingFrequencyPreset("Q65", "6m", 50_275_000L),
    WorkingFrequencyPreset("Q65", "2m", 144_120_000L),
    WorkingFrequencyPreset("Q65", "70cm", 432_065_000L),
    WorkingFrequencyPreset("Q65", "23cm", 1_296_065_000L),
    WorkingFrequencyPreset("Q65", "13cm", 2_304_065_000L),
    WorkingFrequencyPreset("Q65", "9cm", 3_400_065_000L),
    WorkingFrequencyPreset("Q65", "6cm", 5_760_100_000L),
    WorkingFrequencyPreset("Q65", "3cm", 10_368_100_000L),
)
