package com.bg7yoz.ft8cn.feature.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bg7yoz.ft8cn.FT8Common
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.core.time.DisciplinedClockRegistry
import com.bg7yoz.ft8cn.feature.shell.LegacyConsoleDestination
import com.bg7yoz.ft8cn.feature.shell.LegacyConsoleLauncher
import kotlin.math.roundToInt

/** Call 页面只管理 FT8/FT4 小型状态；PCM 与 native 工作区继续留在操作台。 */
@Composable
fun CallScreen() {
    val context = LocalContext.current
    var selectedMode by rememberSaveable { mutableStateOf(GeneralVariables.getSignalMode()) }
    ObserveSignalMode { selectedMode = it }
    val clock by DisciplinedClockRegistry.state().collectAsStateWithLifecycle()
    val automaticAllowed = DisciplinedClockRegistry.isAutomaticTransmitAllowed()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("FT8 / FT4 呼叫", style = MaterialTheme.typography.headlineMedium)
            Text(
                "官方 WSJT-X 3.0 RX · 可审计自动流程 · 串行结果归并",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            CallCard("模式") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeButton(
                        selected = selectedMode == FT8Common.FT8_MODE,
                        label = "FT8 · 15 秒",
                        onClick = { GeneralVariables.setSignalMode(FT8Common.FT8_MODE) },
                    )
                    ModeButton(
                        selected = selectedMode == FT8Common.FT4_MODE,
                        label = "FT4 · 7.5 秒",
                        onClick = { GeneralVariables.setSignalMode(FT8Common.FT4_MODE) },
                    )
                }
                Text(
                    "模式切换会原子刷新 slot、TX 时序和 decode request；Q65 仅在月面通信页提供。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            CallCard("时间与自动发射") {
                Text("来源 ${clock.source.name} · 偏差 ${clock.offsetMillis.roundToInt()} ms")
                Text("误差 ±${clock.uncertaintyMillis.roundToInt()} ms · 样本年龄 ${clock.sampleAgeMillis / 1_000} s")
                Text(
                    if (automaticAllowed) "自动 TX 时间门禁：允许"
                    else "自动 TX 时间门禁：已阻止",
                    color = if (automaticAllowed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    "自动回答和自动 CQ 只有显式 armed 后才运行；每个 slot 最多一次动作。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            CallCard("实时操作") {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { LegacyConsoleLauncher.open(context, LegacyConsoleDestination.CALL) },
                ) { Text("进入呼叫与发射操作台") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { LegacyConsoleLauncher.open(context, LegacyConsoleDestination.DECODE) },
                    ) { Text("解码列表") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { LegacyConsoleLauncher.open(context, LegacyConsoleDestination.SPECTRUM) },
                    ) { Text("频谱") }
                }
                Text(
                    "兼容操作台继续承载已验证的 AudioRecord、JNI、PTT 与自动程序；现代页不复制 PCM。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ObserveSignalMode(onChanged: (Int) -> Unit) {
    val owner: LifecycleOwner = LocalLifecycleOwner.current
    val observer = remember(onChanged) { Observer<Int> { value -> onChanged(value) } }
    DisposableEffect(owner, observer) {
        GeneralVariables.mutableSignalMode.observe(owner, observer)
        onDispose { GeneralVariables.mutableSignalMode.removeObserver(observer) }
    }
}

@Composable
private fun CallCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun ModeButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
