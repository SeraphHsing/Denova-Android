package com.denova.novara.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** 应用主题入口：按 [darkTheme] 选择深浅配色。 */
@Composable
fun NovaraTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
