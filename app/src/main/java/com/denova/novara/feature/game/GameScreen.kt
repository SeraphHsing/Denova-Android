package com.denova.novara.feature.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GamePhase

/**
 * 游戏舞台（G4）：输入 story/branch → 挂接活动流 → 展示投影阶段与累积叙事。
 * 叙事按 chunk 累积；断线由 [GameViewModel] 内部恢复协调器自动重挂。
 */
@Composable
fun GameScreen(viewModel: GameViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var storyId by remember { mutableStateOf("") }
    var branchId by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Novara 游戏", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            TextButton(onClick = onBack) { Text("返回") }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = storyId,
                onValueChange = { storyId = it },
                label = { Text("story ID") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedTextField(
                value = branchId,
                onValueChange = { branchId = it },
                label = { Text("branch（可空）") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(onClick = { viewModel.start(storyId.trim(), branchId.trim()) }) { Text("挂接") }
        }

        // 投影状态
        val phaseText = when (uiState.stage.phase) {
            GamePhase.IDLE -> "空闲"
            GamePhase.RUNNING -> "运行中"
            GamePhase.SUSPENDED -> "已暂停"
            GamePhase.DONE -> "完成"
            GamePhase.ABORTED -> "已中止"
            GamePhase.ERROR -> "错误"
        }
        val activityText = when (uiState.stage.activity) {
            GameActivity.IDLE -> ""
            GameActivity.THINKING -> "思考中…"
            GameActivity.TOOL -> "调用工具…"
            GameActivity.REPAIRING -> "修复中…"
            GameActivity.RECOVERING -> "恢复中…"
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(
                "状态：$phaseText${if (activityText.isNotEmpty()) " · $activityText" else ""}${if (uiState.connected) " · 已连接" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        uiState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        }

        // 叙事单独收集 blocks 流：高频更新只重组本面板
        NarrativePane(viewModel = viewModel, modifier = Modifier.weight(1f))

        // 发送玩家输入（POST 实时流；先落本地命令事务再发送）
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("玩家输入") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    viewModel.send(storyId.trim(), branchId.trim(), input)
                    input = ""
                },
                enabled = !uiState.sending && storyId.isNotBlank() && input.isNotBlank(),
            ) { Text(if (uiState.sending) "发送中…" else "发送") }
        }
    }
}

/**
 * 叙事面板：只有它收集高频 [GameViewModel.blocks] 流，
 * chunk 更新不会连带重组状态行与输入框（含 IME 布局，代价高）。
 */
@Composable
private fun NarrativePane(viewModel: GameViewModel, modifier: Modifier = Modifier) {
    val blocks by viewModel.blocks.collectAsState()
    if (blocks.isEmpty()) {
        Text(
            text = "输入 story ID 后挂接游戏流",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(12.dp),
        )
        return
    }
    // reverseLayout：新块追加后视图天然锚定底部，无需每次 flush 调 scrollToItem。
    LazyColumn(
        reverseLayout = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        itemsIndexed(blocks.asReversed()) { _, block ->
            Text(
                text = block,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
