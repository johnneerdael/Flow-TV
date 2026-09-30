package io.github.aedev.flow.ui.startup

import androidx.annotation.StyleRes
import io.github.aedev.flow.R

/** The background family of the app theme, which the next launch's splash should open on. */
enum class SplashTone { LIGHT, DARK, BLACK }

/** The starting style for a tone: the splash opens on the app theme's background family. */
@StyleRes
fun splashThemeFor(tone: SplashTone): Int =
    when (tone) {
        SplashTone.LIGHT -> R.style.Theme_Flow_Starting_Light
        SplashTone.DARK -> R.style.Theme_Flow_Starting_Dark
        SplashTone.BLACK -> R.style.Theme_Flow_Starting_Black
    }
