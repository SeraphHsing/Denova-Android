package com.denova.novara.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// DesignTokens（P2）：语义色板，深浅两套。页面只引用 ColorScheme 的语义键，不直接写色值。

private val LightPrimary = Color(0xFF5B5BD6)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFE2E0FF)
private val LightOnPrimaryContainer = Color(0xFF16125C)
private val LightSecondary = Color(0xFF5D5C72)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightBackground = Color(0xFFFBFAFF)
private val LightSurface = Color(0xFFFBFAFF)
private val LightOnBackground = Color(0xFF1B1B21)
private val LightOnSurface = Color(0xFF1B1B21)
private val LightOutline = Color(0xFF767680)
private val LightError = Color(0xFFBA1A1A)

private val DarkPrimary = Color(0xFFC3C1FF)
private val DarkOnPrimary = Color(0xFF252276)
private val DarkPrimaryContainer = Color(0xFF3C3A8F)
private val DarkOnPrimaryContainer = Color(0xFFE2E0FF)
private val DarkSecondary = Color(0xFFC6C5DC)
private val DarkOnSecondary = Color(0xFF2E2D42)
private val DarkBackground = Color(0xFF131318)
private val DarkSurface = Color(0xFF131318)
private val DarkOnBackground = Color(0xFFE5E1E9)
private val DarkOnSurface = Color(0xFFE5E1E9)
private val DarkOutline = Color(0xFF90909C)
private val DarkError = Color(0xFFFFB4AB)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
    outline = LightOutline,
    error = LightError,
)

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    outline = DarkOutline,
    error = DarkError,
)
