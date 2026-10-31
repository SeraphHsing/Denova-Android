package com.denova.novara.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import com.denova.novara.ui.theme.NovaTheme

/**
 * 应用主题入口：按 [darkTheme] 选择语义色板，把 [NovaColors] 映射成 Material3 `ColorScheme`，
 * 并用 [Surface] 提供跟随主题的页面背景。
 *
 * 不加这层 Surface 时，页面根布局透明、露出 window 默认白底，深色模式只在自带背景的组件（抽屉等）生效。
 * 页面取色优先用 [NovaTheme.colors]（基线 `--nova-*` 语义键），`MaterialTheme.colorScheme` 只作兼容层，
 * 避免历史页面在用 M3 推导色时与基线产生第二套色值。
 */
@Composable
fun NovaraTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) NovaColors.Dark else NovaColors.Light
    CompositionLocalProvider(LocalNovaColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(darkTheme),
            typography = novaTypography(),
            shapes = novaShapes(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.bg,
                content = content,
            )
        }
    }
}

/** token 取用入口：`NovaTheme.colors.surface`、`NovaTheme.typography.titleLarge`。 */
object NovaTheme {
    val colors: NovaColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNovaColors.current
}

private fun NovaColors.toColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = surface3,
        onPrimaryContainer = text,
        secondary = textMuted,
        onSecondary = bg,
        secondaryContainer = surface2,
        onSecondaryContainer = text,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = textMuted,
        surfaceContainerLowest = bgDeep,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface3,
        outline = textFaint,
        outlineVariant = borderSoft,
        error = danger,
        onError = bg,
        errorContainer = dangerBg,
        onErrorContainer = danger,
        scrim = scrim,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = surface3,
        onPrimaryContainer = text,
        secondary = textMuted,
        onSecondary = bg,
        secondaryContainer = surface2,
        onSecondaryContainer = text,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = textMuted,
        surfaceContainerLowest = bgDeep,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface3,
        outline = textFaint,
        outlineVariant = borderSoft,
        error = danger,
        onError = bg,
        errorContainer = dangerBg,
        onErrorContainer = danger,
        scrim = scrim,
    )
}
