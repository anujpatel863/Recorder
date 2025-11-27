package com.example.allrecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Using your retro_colors for both light and dark theme as they are distinct
private val DarkColorScheme = darkColorScheme(
    primary = RetroPrimary,
    onPrimary = RetroBackground,
    secondary = RetroAccent,
    onSecondary = RetroBackground,
    background = RetroBackground,
    onBackground = RetroTextPrimary,
    surface = RetroCardBackground,
    onSurface = RetroTextPrimary,
    surfaceVariant = RetroCardBackground,
    onSurfaceVariant = RetroTextSecondary,
    outline = RetroCardStroke
)

private val LightColorScheme = lightColorScheme(
    primary = RetroPrimary,
    onPrimary = RetroBackground,
    secondary = RetroAccent,
    onSecondary = RetroBackground,
    background = RetroBackground,
    onBackground = RetroTextPrimary,
    surface = RetroCardBackground,
    onSurface = RetroTextPrimary,
    surfaceVariant = RetroCardBackground,
    onSurfaceVariant = RetroTextSecondary,
    outline = RetroCardStroke
)

@Composable
fun AllRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Your app appears to use the same retro theme for both light and dark
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme



    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}