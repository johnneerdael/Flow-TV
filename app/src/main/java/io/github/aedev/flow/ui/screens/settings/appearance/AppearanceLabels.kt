package io.github.aedev.flow.ui.screens.settings.appearance

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.theme.ThemeVariant

@StringRes
internal fun themeVariantLabel(variant: ThemeVariant): Int =
    when (variant) {
        ThemeVariant.LIGHT -> R.string.appearance_variant_light
        ThemeVariant.DARK -> R.string.appearance_variant_dark
        ThemeVariant.AMOLED -> R.string.appearance_variant_amoled
    }
