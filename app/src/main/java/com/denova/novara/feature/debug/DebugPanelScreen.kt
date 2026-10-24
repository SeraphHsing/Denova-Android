package com.denova.novara.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.denova.novara.core.image.imageLoaderFor
import com.denova.novara.core.logging.LogEntry
import com.denova.novara.core.logging.LogLevel
import com.denova.novara.core.network.DenovaClient
import java.util.Calendar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPanelScreen(
    viewModel: DebugPanelViewModel,
    client: DenovaClient?,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var assetProjectId by remember { mutableStateOf("") }
    var assetPath by remember { mutableStateOf("") }
    var assetUrl by remember { mutableStateOf<String?>(null) }
    val imageLoader = remember(client) { client?.let { imageLoaderFor(context, it) } }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug 日志") },
                actions = {
                    TextButton(onClick = { viewModel.clear() }) { Text("清空") }
                    TextButton(onClick = onClose) { Text("关闭") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            AssetProbe(
                client = client,
                assetProjectId = assetProjectId,
                onProjectIdChange = { assetProjectId = it },
                assetPath = assetPath,
                onPathChange = { assetPath = it },
                onLoad = {
                    assetUrl = if (client != null && assetProjectId.isNotBlank() && assetPath.isNotBlank()) {
                        client.projectAssetUrl(assetProjectId.trim(), assetPath.trim()).toString()
                    } else {
                        null
                    }
                },
                imageLoader = imageLoader,
                assetUrl = assetUrl,
            )
            OutlinedTextField(
                value = state.filter,
                onValueChange = viewModel::setFilter,
                label = { Text("过滤 tag / 内容") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = state.minLevel == level,
                        onClick = { viewModel.setMinLevel(level) },
                        label = { Text(level.name) },
                    )
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(state.entries, key = { e -> "${e.timestamp}-${e.tag}-${e.message}" }) { entry ->
                    LogEntryRow(entry)
                    HorizontalDivider(color = Color(0x14000000))
                }
            }
        }
    }
}

/**
 * 受保护图片探针（G1）：按项目 ID + 资源路径读取 `GET /files/asset`，
 * 与页面共用同一会话 Cookie，用于确认「需要认证的图片资源」可以被正常取回。
 */
@Composable
private fun AssetProbe(
    client: DenovaClient?,
    assetProjectId: String,
    onProjectIdChange: (String) -> Unit,
    assetPath: String,
    onPathChange: (String) -> Unit,
    onLoad: () -> Unit,
    imageLoader: coil3.ImageLoader?,
    assetUrl: String?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = assetProjectId,
                onValueChange = onProjectIdChange,
                label = { Text("资源-项目ID") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = assetPath,
                onValueChange = onPathChange,
                label = { Text("资源路径") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = onLoad, enabled = client != null) { Text("读取图片") }
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    client == null -> "未连接"
                    assetUrl == null -> "填写项目与路径"
                    else -> "已请求：${assetUrl.substringAfterLast("path=")}"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (assetUrl != null && imageLoader != null) {
            AsyncImage(
                model = assetUrl,
                contentDescription = "受保护图片预览",
                imageLoader = imageLoader,
                modifier = Modifier.height(160.dp).width(240.dp),
            )
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.VERBOSE -> Color(0xFF9E9E9E)
        LogLevel.DEBUG -> Color(0xFF2196F3)
        LogLevel.INFO -> Color(0xFF4CAF50)
        LogLevel.WARN -> Color(0xFFFF9800)
        LogLevel.ERROR -> Color(0xFFF44336)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                entry.level.name,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(entry.tag, color = color, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(
                formatTime(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
            )
        }
        Text(entry.message, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTime(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format("%1\$tH:%1\$tM:%1\$tS.%1\$tL", cal)
}
