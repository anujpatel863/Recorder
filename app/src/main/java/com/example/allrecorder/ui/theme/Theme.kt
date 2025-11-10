package com.example.allrecorder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}