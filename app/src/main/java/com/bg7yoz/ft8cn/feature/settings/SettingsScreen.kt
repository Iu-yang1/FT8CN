package com.bg7yoz.ft8cn.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bg7yoz.ft8cn.core.time.ClockSnapshot
import com.bg7yoz.ft8cn.core.time.DisciplinedClockRegistry
import com.bg7yoz.ft8cn.feature.shell.LegacyConsoleDestination
import com.bg7yoz.ft8cn.feature.shell.LegacyConsoleLauncher
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val clock by DisciplinedClockRegistry.state().collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "设置与时间", style = MaterialTheme.typography.headlineMedium)
        ClockStatusCard(clock, DisciplinedClockRegistry.isAutomaticTransmitAllowed())
        Text(
            text = "完整设置仍由兼容操作台承载；自动发射只在 NTP/GNSS 时间健康时允许。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { LegacyConsoleLauncher.open(context, LegacyConsoleDestination.SETTINGS) },
        ) { Text("打开完整设置") }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { LegacyConsoleLauncher.open(context, LegacyConsoleDestination.HISTORY) },
        ) { Text("打开兼容通联记录") }
    }
}

@Composable
private fun ClockStatusCard(clock: ClockSnapshot, automaticTransmitAllowed: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "应用 UTC 时间", style = MaterialTheme.typography.titleLarge)
            ClockRow("来源", clock.source.name)
            ClockRow("偏差", "${clock.offsetMillis.roundToInt()} ms")
            ClockRow("误差范围", "±${clock.uncertaintyMillis.roundToInt()} ms")
            ClockRow("样本年龄", "${clock.sampleAgeMillis / 1_000} s")
            ClockRow("自动发射", if (automaticTransmitAllowed) "允许" else "已阻止")
            if (clock.detail.isNotBlank()) {
                Text(text = clock.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ClockRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
