package com.ikemenselect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF1A1A1A),
    secondary = Color(0xFF7FB7FF),
    tertiary = Color(0xFF9BE37A),
    background = Color(0xFF121212),
    surface = Color(0xFF1B1B1B),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
)

@Composable
fun IkemenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
