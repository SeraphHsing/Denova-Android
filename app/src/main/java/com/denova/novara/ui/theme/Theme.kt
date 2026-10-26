package com.denova.novara.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 应用主题入口：按 [darkTheme] 选择深浅配色，并用 [Surface] 提供跟随主题的页面背景。
 * 不加这层 Surface 时，页面根布局透明、露出 window 默认白底，深色模式只在自带背景的组件（抽屉等）生效。
 */
@Composable
fun NovaraTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
