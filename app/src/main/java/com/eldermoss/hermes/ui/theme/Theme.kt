package com.eldermoss.hermes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.eldermoss.hermes.core.store.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C5F2D),
    onPrimary = Color.White,
    secondary = Color(0xFF4F7942),
    surface = Color(0xFFFAF9F4),
    background = Color(0xFFFAF9F4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FBC8F),
    onPrimary = Color(0xFF10240F),
    secondary = Color(0xFF6B8F5E),
    surface = Color(0xFF151815),
    background = Color(0xFF101310),
)

@Composable
fun HermesTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
