package com.denova.novara.feature.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 右项目 / 控制台抽屉（01 §3）：从右侧滑出，内容按当前页面提供——
 * 写作页为项目目录，游戏页为游戏控制台。数据源待接（文件树 API / 游戏状态）。
 */
@Composable
fun RightDrawer(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
        )
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp),
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    content()
                }
            }
        }
    }
}

/** 项目目录占位（写作页）。完整文件树在 P3 写作链路接 `/book/tree`。 */
@Composable
fun ProjectDirectoryContent() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "项目目录（待接文件树 API）",
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 游戏控制台占位（游戏页）。会话 / 分支状态后续接 GameViewModel。 */
@Composable
fun GameConsoleContent() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "游戏控制台（待接会话 / 分支状态）",
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
