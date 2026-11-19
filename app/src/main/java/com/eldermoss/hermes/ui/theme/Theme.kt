package com.eldermoss.hermes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.eldermoss.hermes.core.store.ThemeMode

// Erich-approved identity: charcoal surfaces, amber accent.
private val Amber = Color(0xFFF5A623)
private val AmberDeep = Color(0xFFB87A10)
private val Charcoal = Color(0xFF1C1C1E)
private val CharcoalDeep = Color(0xFF121214)
private val CharcoalRaise = Color(0xFF2A2A2D)
private val Ink = Color(0xFFECEAE4)
private val InkDim = Color(0xFF9C9A94)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Charcoal,
    primaryContainer = Color(0xFF43351A),
    onPrimaryContainer = Color(0xFFFFC96B),
    secondary = Color(0xFFA8A6A0),
    onSecondary = Charcoal,
    secondaryContainer = CharcoalRaise,
    onSecondaryContainer = Ink,
    background = CharcoalDeep,
    onBackground = Ink,
    surface = Charcoal,
    onSurface = Ink,
    surfaceVariant = CharcoalRaise,
    onSurfaceVariant = InkDim,
    outline = Color(0xFF4A484D),
    error = Color(0xFFF28B82),
    onError = Color(0xFF3B0A05),
    errorContainer = Color(0xFF5C1A12),
    onErrorContainer = Color(0xFFF6B3AC),
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9C4),
    onPrimaryContainer = Color(0xFF4A3208),
    secondary = Color(0xFF5C5B56),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1B1A17),
    surface = Color(0xFFFDFCFA),
    onSurface = Color(0xFF1B1A17),
    surfaceVariant = Color(0xFFEBE8E0),
    onSurfaceVariant = Color(0xFF5C5B56),
    outline = Color(0xFFB9B6AD),
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
