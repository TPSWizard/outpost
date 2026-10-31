package com.conza.outpost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Field-kit palette: forest green, sand, signal orange. Readable in sunlight and at night.
private val Dark = darkColorScheme(
    primary = Color(0xFF8FC79A),
    onPrimary = Color(0xFF0E2416),
    primaryContainer = Color(0xFF244533),
    onPrimaryContainer = Color(0xFFCDEBD2),
    secondary = Color(0xFFE0C38C),
    secondaryContainer = Color(0xFF2F4A3A),
    onSecondaryContainer = Color(0xFFDCEFDF),
    surfaceContainer = Color(0xFF18231D),
    surfaceContainerHigh = Color(0xFF1E2A24),
    surfaceContainerLow = Color(0xFF131C17),
    tertiary = Color(0xFFFFB37A),
    background = Color(0xFF101814),
    surface = Color(0xFF151F1A),
    surfaceVariant = Color(0xFF223029),
    onSurfaceVariant = Color(0xFFB9C7BE),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2E6A42),
    primaryContainer = Color(0xFFCDEBD2),
    onPrimaryContainer = Color(0xFF0E2416),
    secondary = Color(0xFF7A5C22),
    secondaryContainer = Color(0xFFD3E8D6),
    onSecondaryContainer = Color(0xFF0E2416),
    surfaceContainer = Color(0xFFEFEADC),
    surfaceContainerHigh = Color(0xFFE9E4D5),
    surfaceContainerLow = Color(0xFFF3F0E6),
    tertiary = Color(0xFFB0541A),
    background = Color(0xFFF6F3EA),
    surface = Color(0xFFFBF9F3),
    surfaceVariant = Color(0xFFE6E2D6),
)

@Composable
fun OutpostTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
