package com.bg7yoz.ft8cn.feature.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bg7yoz.ft8cn.feature.shell.LegacyConsoleDestination
import com.bg7yoz.ft8cn.feature.shell.LegacyConsoleLauncher

@Composable
fun RadioScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("电台控制", style = MaterialTheme.typography.headlineMedium)
        Text(
            "统一频率计划 · split / Fake It · PTT watchdog · 失败回滚",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("安全状态", style = MaterialTheme.typography.titleLarge)
                Text("启动时不会自动 PTT；CAT 跟踪和自动发射均需单独 armed。")
                Text("split 与 Fake It 采用读旧值、应用、读回验证、失败恢复事务。")
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { LegacyConsoleLauncher.open(context, LegacyConsoleDestination.CALL) },
                ) { Text("打开实时电台操作") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { LegacyConsoleLauncher.open(context, LegacyConsoleDestination.SETTINGS) },
                ) { Text("配置连接与 split") }
            }
        }
    }
}
