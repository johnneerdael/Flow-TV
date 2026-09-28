package io.github.aedev.flow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

private val MilkbeatSeed =
    PaletteSeed(
        primary = Color(0xFF8355FB),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF00E3FD),
        background = Color(0xFF000000),
        surface = Color(0xFF080808),
        onSurface = Color(0xFFF4F4F4),
        onSurfaceVariant = Color(0xFFB8B8B8),
        outline = Color(0xFF2A2A2A),
        error = Color(0xFFFF5370),
    )

/**
 * Milkbeat's single look: the logo's violet (the centre of its cyan-to-magenta sweep) as the
 * accent and its cyan as the second accent, on the AMOLED black ladder.
 */
val MilkbeatColorScheme: ColorScheme =
    MilkbeatSeed.expand(ThemeVariant.AMOLED).toColorScheme(ThemeVariant.AMOLED)
