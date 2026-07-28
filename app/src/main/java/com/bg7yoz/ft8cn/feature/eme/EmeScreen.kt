package com.bg7yoz.ft8cn.feature.eme

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bg7yoz.ft8cn.FT8Common
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.eme.EmeDopplerCalculator
import com.bg7yoz.ft8cn.eme.MoonEphemeris
import com.bg7yoz.ft8cn.eme.ObserverLocation
import kotlinx.coroutines.delay
import java.util.Locale

private data class SubmodeOption(val value: Int, val label: String)

/**
 * 独立的 Q65/EME 工作台。页面状态只保存配置与小型天文摘要，不持有 PCM 或 native 句柄。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EmeScreen() {
    val submodes = remember {
        (FT8Common.Q65_SUBMODE_A..FT8Common.Q65_SUBMODE_E).map {
            SubmodeOption(it, "Q65${FT8Common.getQ65SubmodeLabel(it)}")
        }
    }
    val periods = remember { FT8Common.Q65_SUPPORTED_TR_PERIODS.toList() }
    var selectedSubmode by rememberSaveable { mutableStateOf(GeneralVariables.getQ65Submode()) }
    var selectedPeriod by rememberSaveable { mutableStateOf(GeneralVariables.getQ65TrPeriodSeconds()) }
    var localGrid by rememberSaveable { mutableStateOf(GeneralVariables.getMyMaidenheadGrid()) }
    var dxGrid by rememberSaveable { mutableStateOf("") }
    var pathMode by rememberSaveable {
        mutableStateOf(EmeDopplerCalculator.PathMode.CONSTANT_FREQUENCY_ON_MOON)
    }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(10_000L)
        }
    }

    val localMoon = remember(localGrid, nowMillis) {
        ObserverLocation.fromGrid(localGrid)?.let { MoonEphemeris.calculate(it, nowMillis) }
    }
    val dxMoon = remember(dxGrid, nowMillis) {
        ObserverLocation.fromGrid(dxGrid)?.let { MoonEphemeris.calculate(it, nowMillis) }
    }
    val frequencyHz = remember { GeneralVariables.emeBaseFrequencyHz.toDouble() }
    val correction = remember(pathMode, frequencyHz, localMoon, dxMoon) {
        if (frequencyHz <= 0.0 || localMoon == null) {
            null
        } else {
            runCatching {
                EmeDopplerCalculator.calculatePlan(
                    frequencyHz,
                    localMoon.rangeRateMps,
                    dxMoon?.rangeRateMps ?: Double.NaN,
                    pathMode,
                )
            }.getOrNull()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Q65 月面通信", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Q65 A–E · 串行官方核心 · 长周期流式音频",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            EmeCard("模式与周期") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(submodes, key = { it.value }) { option ->
                        SelectionButton(
                            selected = selectedSubmode == option.value,
                            label = option.label,
                            onClick = {
                                selectedSubmode = option.value
                                GeneralVariables.setQ65Configuration(option.value, selectedPeriod)
                            },
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(periods, key = { it }) { period ->
                        SelectionButton(
                            selected = selectedPeriod == period,
                            label = "${period}s",
                            onClick = {
                                selectedPeriod = period
                                GeneralVariables.setQ65Configuration(selectedSubmode, period)
                            },
                        )
                    }
                }
                Text(
                    "Q65F 仅保留诊断入口，不开放正式发射。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            EmeCard("月面与 Doppler") {
                OutlinedTextField(
                    value = localGrid,
                    onValueChange = { localGrid = it.trim().uppercase(Locale.US).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("本台网格") },
                )
                OutlinedTextField(
                    value = dxGrid,
                    onValueChange = { dxGrid = it.trim().uppercase(Locale.US).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("DX 网格（Full Doppler 必填）") },
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(EmeDopplerCalculator.PathMode.values().toList(), key = { it.name }) { mode ->
                        SelectionButton(
                            selected = pathMode == mode,
                            label = mode.displayLabel(),
                            onClick = { pathMode = mode },
                        )
                    }
                }
                if (localMoon == null) {
                    Text("请输入有效本台网格以计算月面位置。")
                } else {
                    Text(String.format(
                        Locale.US,
                        "月面 方位 %.1f° · 仰角 %.1f° · 距离 %.0f km",
                        localMoon.azimuthDeg,
                        localMoon.elevationDeg,
                        localMoon.distanceKm,
                    ))
                    Text(String.format(Locale.US, "本台 range rate %.2f m/s", localMoon.rangeRateMps))
                }
                if (correction == null) {
                    Text("设置 EME 基准频率；Full DX 还需要有效 DX 网格。")
                } else {
                    Text(String.format(
                        Locale.US,
                        "RX 校正 %+.1f Hz · TX 校正 %+.1f Hz",
                        correction.receiveCorrectionHz,
                        correction.transmitCorrectionHz,
                    ))
                }
                Text(
                    "自动 CAT 默认关闭；只有 armed、时钟健康且读回验证成功时才允许跟踪。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            EmeCard("接收与发射状态") {
                Text("RX：24/48 kHz 分块抽取到预分配 12 kHz 时隙缓冲区")
                Text("TX：AudioTrack MODE_STREAM，4096 样本有界队列与背压")
                Text("Averaging：按 Q65 session 持久化；切换周期、submode 或目标时清除")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("发射状态：未 armed")
                    Text("电台：等待连接")
                }
            }
        }
    }
}

@Composable
private fun EmeCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SelectionButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

private fun EmeDopplerCalculator.PathMode.displayLabel(): String = when (this) {
    EmeDopplerCalculator.PathMode.FULL_DOPPLER_TO_DX -> "Full DX"
    EmeDopplerCalculator.PathMode.OWN_ECHO -> "Own Echo"
    EmeDopplerCalculator.PathMode.CONSTANT_FREQUENCY_ON_MOON -> "CFOM"
}
