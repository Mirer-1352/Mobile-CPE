package dev.autohotspot

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF152E3A)
internal val Muted = Color(0xFF526674)
internal val Paper = Color(0xFFEDF3F5)
internal val Teal = Color(0xFF155E75)
internal val Orange = Color(0xFF945020)
internal val Palette = lightColorScheme(primary = Teal, background = Paper, surface = Color.White,
    onBackground = Ink, onSurface = Ink, onSurfaceVariant = Muted, secondary = Teal, error = Color(0xFFB3261E))
