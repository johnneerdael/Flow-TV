package io.github.aedev.flow.ui.startup

import androidx.annotation.StyleRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.aedev.flow.R

private const val DARK_LUMINANCE = 0.5f

/** The background family of the app theme, which the next launch's splash should open on. */
enum class SplashTone { LIGHT, DARK, BLACK }

fun splashTone(background: Color): SplashTone =
    when {
        background == Color.Black -> SplashTone.BLACK
        background.luminance() < DARK_LUMINANCE -> SplashTone.DARK
        else -> SplashTone.LIGHT
    }

/** The starting style for a tone: the splash opens on the app theme's background family. */
@StyleRes
fun splashThemeFor(tone: SplashTone): Int =
    when (tone) {
        SplashTone.LIGHT -> R.style.Theme_Flow_Starting_Light
        SplashTone.DARK -> R.style.Theme_Flow_Starting_Dark
        SplashTone.BLACK -> R.style.Theme_Flow_Starting_Black
    }
