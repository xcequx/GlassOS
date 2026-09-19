package com.uxspace.phone.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val GlassBg = Color(0xFF0B0E14)
val GlassSurface = Color(0xFF161B24)
val GlassSurfaceHi = Color(0xFF1E2533)
val GlassAccent = Color(0xFF5EE0F7)
val GlassAccentDim = Color(0xFF2A6B78)
val GlassText = Color(0xFFE8EEF6)
val GlassMuted = Color(0xFF93A0B4)
val GlassDanger = Color(0xFFFF8A80)
val GlassOk = Color(0xFF7CFFB2)

private val Scheme = darkColorScheme(
    primary = GlassAccent,
    onPrimary = Color(0xFF00333C),
    secondary = GlassAccentDim,
    background = GlassBg,
    surface = GlassSurface,
    onBackground = GlassText,
    onSurface = GlassText,
    onSurfaceVariant = GlassMuted,
    outline = Color(0xFF2C3545),
)

@Composable
fun GlassOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
