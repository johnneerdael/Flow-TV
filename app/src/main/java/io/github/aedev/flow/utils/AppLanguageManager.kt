package io.github.aedev.flow.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguageManager {
    const val SYSTEM_DEFAULT = "system"
    private const val PREFS_FILE = "flow_language_prefs"
    private const val PREFS_KEY = "app_language_tag"

    fun loadSelectedLanguageTag(context: Context): String =
        context
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(PREFS_KEY, SYSTEM_DEFAULT) ?: SYSTEM_DEFAULT

    /**
     * [base] with the chosen language applied, or [base] itself when following the system.
     *
     * The override carries the locale and nothing else. Copying the whole current configuration
     * froze every field at launch, the night-mode bit included, so the app never followed a system
     * switch between light and dark (#972).
     */
    fun wrapContext(
        base: Context,
        selectedTag: String,
    ): Context {
        val normalizedTag = normalizeLanguageTag(selectedTag)
        val locale = resolveLocale(base, normalizedTag)
        Locale.setDefault(locale)
        if (normalizedTag == SYSTEM_DEFAULT) return base

        val configuration = Configuration()
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    fun normalizeLanguageTag(rawTag: String?): String {
        val value = rawTag?.trim().orEmpty()
        if (value.isEmpty() || value.equals(SYSTEM_DEFAULT, ignoreCase = true)) {
            return SYSTEM_DEFAULT
        }

        return when (value.lowercase(Locale.ROOT)) {
            "in", "id-id" -> "id"
            "pt-rbr", "pt_br", "pt-br" -> "pt-BR"
            else -> localeFromTag(value).toLanguageTag().takeIf { it.isNotBlank() } ?: value
        }
    }

    private fun resolveLocale(
        context: Context,
        selectedTag: String,
    ): Locale {
        if (selectedTag == SYSTEM_DEFAULT) {
            val configuration = context.resources.configuration
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                configuration.locales[0] ?: Locale.getDefault()
            } else {
                @Suppress("DEPRECATION")
                configuration.locale ?: Locale.getDefault()
            }
        }

        return localeFromTag(selectedTag)
    }

    private fun localeFromTag(tag: String): Locale {
        val normalizedTag =
            when (tag) {
                "id" -> "id"
                else -> tag
            }
        return Locale.forLanguageTag(normalizedTag)
    }
}
