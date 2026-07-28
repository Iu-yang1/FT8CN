package com.bg7yoz.ft8cn.feature.logbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.data.logbook.AdifCodec
import com.bg7yoz.ft8cn.data.logbook.LotwStatus
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogbookScreen() {
    val context = LocalContext.current
    val graph = remember(context) { FeatureAppGraph.from(context) }
    val workflow = graph.lotwWorkflow
    val records by graph.qsoLogRepository.observeRecent(250).collectAsState(initial = emptyList())
    val jobs by workflow.observeJobs(50).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("本地日志就绪") }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    val createAdif = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-adif"),
    ) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri != null && content != null) {
            scope.launch {
                statusText = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.US_ASCII)?.use {
                            it.write(content)
                        } ?: error("无法打开导出文件")
                    }
                    "已导出 ${records.size} 条 ADIF 记录；请使用 TQSL 签名为 .tq8"
                }.getOrElse { "导出失败：${it.message}" }
            }
        }
    }
    val importAdif = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            statusText = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(::readBoundedAdif)
                        ?: error("无法读取 ADIF 文件")
                }
                val summary = workflow.importAdif(text)
                "ADIF 导入 ${summary.imported} 条，拒绝 ${summary.rejected} 条" +
                    if (summary.warnings.isEmpty()) "" else "；${summary.warnings.first()}"
            }.getOrElse { "导入失败：${it.message}" }
        }
    }
    val importTq8 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            statusText = runCatching {
                val queued = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { workflow.importSignedTq8AndSchedule(it) }
                        ?: error("无法读取 .tq8 文件")
                }
                "已校验并排队 ${queued.qsoCount} 条签名 QSO" +
                    if (queued.reusedExistingJob) "（幂等复用原任务）" else ""
            }.getOrElse { "签名文件拒绝：${it.message}" }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("日志与 LoTW", style = MaterialTheme.typography.headlineMedium)
            Text(
                "ADIF 3.1.5 · 外部 TQSL 数字签名 · 官方 HTTPS 上传",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("安全工作流", style = MaterialTheme.typography.titleMedium)
                    Text("FT8CN 不保存呼号证书私钥或密码，也不会把未签名 ADIF 直接上传到 LoTW。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { importAdif.launch(arrayOf("application/x-adif", "text/plain", "*/*")) }) {
                            Text("导入 ADIF")
                        }
                        Button(
                            enabled = records.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    statusText = runCatching {
                                        val candidates = records.filter {
                                            it.lotwStatus == LotwStatus.LOCAL ||
                                                it.lotwStatus == LotwStatus.REJECTED ||
                                                it.lotwStatus == LotwStatus.PENDING_SIGN
                                        }
                                        pendingExport = workflow.exportForExternalSigning(candidates.map { it.stableId })
                                        createAdif.launch("FT8CN-${System.currentTimeMillis()}.adi")
                                        "等待选择导出位置"
                                    }.getOrElse { "导出准备失败：${it.message}" }
                                }
                            },
                        ) { Text("导出并签名") }
                    }
                    Button(onClick = { importTq8.launch(arrayOf("application/octet-stream", "*/*")) }) {
                        Text("选择并上传已签名 .tq8")
                    }
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (jobs.isNotEmpty()) {
            item { Text("LoTW 队列", style = MaterialTheme.typography.titleLarge) }
            items(jobs, key = { it.id }) { job ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("任务 #${job.id} · ${job.state}", style = MaterialTheme.typography.titleSmall)
                        Text("QSO ${job.qsoStableIds.lineSequence().count { it.isNotBlank() }} · 尝试 ${job.attemptCount}")
                        if (!job.responseMessage.isNullOrBlank()) Text(job.responseMessage.take(300))
                        if (!job.lastError.isNullOrBlank()) {
                            Text(job.lastError.take(300), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item { Text("最近 QSO（${records.size}）", style = MaterialTheme.typography.titleLarge) }
        items(records, key = { it.stableId }) { record ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(record.dxCall, style = MaterialTheme.typography.titleMedium)
                        Text(record.mode.name)
                    }
                    Text(
                        "${formatUtc(record.startedUtcMillis)} · ${formatFrequency(record.frequencyHz)} · " +
                            "${record.reportSent}/${record.reportReceived}",
                    )
                    Text(
                        "LoTW ${record.lotwStatus}" +
                            record.satelliteName?.let { " · SAT $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun readBoundedAdif(input: java.io.InputStream): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= AdifCodec.MAX_INPUT_CHARACTERS) { "ADIF 文件超过 32 MiB 上限" }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun formatUtc(utcMillis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    .withZone(ZoneOffset.UTC)
    .format(Instant.ofEpochMilli(utcMillis))

private fun formatFrequency(hz: Long): String = String.format(java.util.Locale.US, "%.6f MHz", hz / 1_000_000.0)
