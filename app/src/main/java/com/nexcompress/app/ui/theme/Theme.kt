package com.nexcompress.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = NexIndigo,
    onPrimary = Color.White,
    primaryContainer = NexIndigoDark,
    onPrimaryContainer = Color.White,
    secondary = NexViolet,
    onSecondary = Color.White,
    tertiary = NexCyan,
    onTertiary = Color.Black,
    background = NexBackgroundDark,
    onBackground = NexOnDark,
    surface = NexSurfaceDark,
    onSurface = NexOnDark,
    surfaceVariant = NexSurfaceVariantDark,
    onSurfaceVariant = NexOnDarkMuted,
    error = NexRed,
    onError = Color.White,
    outline = Color(0xFF333A47),
    outlineVariant = Color(0xFF2A303B)
)

private val LightColors = lightColorScheme(
    primary = NexIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FB),
    onPrimaryContainer = NexIndigoDark,
    secondary = NexViolet,
    onSecondary = Color.White,
    tertiary = Color(0xFF0E7490),
    onTertiary = Color.White,
    background = NexBackgroundLight,
    onBackground = NexOnLight,
    surface = NexSurfaceLight,
    onSurface = NexOnLight,
    surfaceVariant = NexSurfaceVariantLight,
    onSurfaceVariant = NexOnLightMuted,
    error = NexRed,
    onError = Color.White,
    outline = Color(0xFFCBD2E0),
    outlineVariant = Color(0xFFDDE2EC)
)

@Composable
fun NexCompressTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand colors by default so the app matches the design across devices.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NexTypography,
        content = content
    )
}
