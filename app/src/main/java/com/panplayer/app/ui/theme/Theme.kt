package com.panplayer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E5FA),
    onPrimaryContainer = Color(0xFF173A8F),
    secondary = BrandBright,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEBFD),
    onSecondaryContainer = Color(0xFF0F4C8F),
    tertiary = Accent,
    onTertiary = Color.White,
    background = BgTop,
    onBackground = OnSurface,
    surface = SurfaceLight,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariant,
    outline = OutlineSoft,
    error = ErrorRed
)

@Composable
fun PanPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content
    )
}
