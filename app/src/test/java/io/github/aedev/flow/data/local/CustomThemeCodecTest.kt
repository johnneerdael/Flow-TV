package io.github.aedev.flow.data.local

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.theme.CustomTheme
import io.github.aedev.flow.ui.theme.FlowPalettes
import io.github.aedev.flow.ui.theme.ThemeMode
import org.junit.Test

class CustomThemeCodecTest {
    private val theme = CustomTheme.from("custom-test", "Ocean", FlowPalettes.forMode(ThemeMode.TIDE))

    /** A theme as Flow Desktop's `createCustomTheme` stores it: Flow Default cloned, derived steps as `color-mix`. */
    private val desktopTheme =
        """
        {
          "id": "custom-1719000000000",
          "name": "From desktop",
          "custom": true,
          "variants": {
            "light": ${desktopVariant("#ffffff", "#f3f3f3", "#111111", "58", "7", "11")},
            "dark": ${desktopVariant("#0f0f0f", "#1d1d1d", "#f4f4f4", "72", "9", "14")},
            "amoled": {"primary":"#ff0000","onPrimary":"#ffffff","secondary":"#aaaaaa","background":"#000000","surface":"#080808",
              "surfaceContainerLow":"#050505","surfaceContainer":"#0c0c0c","surfaceContainerHigh":"#141414","surfaceContainerHighest":"#1c1c1c",
              "outline":"#343434","onSurface":"#f4f4f4","onSurfaceVariant":"#b8b8b8","error":"#ef5350"}
          }
        }
        """.trimIndent()

    private fun desktopVariant(
        background: String,
        surface: String,
        text: String,
        low: String,
        high: String,
        highest: String,
    ) = """{"primary":"#ff0000","onPrimary":"#ffffff","secondary":"#606060","background":"$background","surface":"$surface",
        "surfaceContainerLow":"color-mix(in srgb, $surface $low%, $background)","surfaceContainer":"$surface",
        "surfaceContainerHigh":"color-mix(in srgb, $text $high%, $surface)","surfaceContainerHighest":"color-mix(in srgb, $text $highest%, $surface)",
        "outline":"#d7d7d7","onSurface":"$text","onSurfaceVariant":"#5f5f5f","error":"#d32f2f"}"""

    @Test
    fun `a desktop theme with color-mix steps imports with the mixes computed`() {
        val imported = CustomThemeCodec.decode(desktopTheme).single()
        assertThat(imported.name).isEqualTo("From desktop")
        // color-mix(in srgb, #111111 7%, #f3f3f3) = 0x11 * 0.07 + 0xf3 * 0.93 = 227 = 0xe3
        assertThat(imported.light.surfaceContainerHigh.toHex()).isEqualTo("#e3e3e3")
        assertThat(imported.amoled.background).isEqualTo(Color.Black)
    }

    @Test
    fun `at most the desktop limit is kept`() {
        val many = (1..CustomTheme.MAX_COUNT + 5).map { theme.copy(id = "custom-$it") }
        assertThat(CustomThemeCodec.decode(CustomThemeCodec.encodeList(many))).hasSize(CustomTheme.MAX_COUNT)
    }

    @Test
    fun `garbage imports nothing`() {
        assertThat(CustomThemeCodec.decode("{ not json")).isEmpty()
        assertThat(CustomThemeCodec.decode("42")).isEmpty()
        assertThat(CustomThemeCodec.decode(null)).isEmpty()
    }

    @Test
    fun `colour parsing accepts desktop's forms and nothing else`() {
        assertThat(parseThemeColor("#FF0000")).isEqualTo(Color.Red)
        assertThat(parseThemeColor("color-mix(in srgb, #000000, #ffffff 25%)")?.toHex()).isEqualTo("#404040")
        assertThat(parseThemeColor("red")).isNull()
        assertThat(parseThemeColor("#fff")).isNull()
        assertThat(parseThemeColor("#ff000080")).isNull()
    }

    @Test
    fun `the first comma separated custom palette still migrates onto the dark style`() {
        val values = (1..16).map { 0xFF000000 + it }
        val stored = decodeLegacyCustomPalettes(raw = null, legacyCsv = values.joinToString(","))!!
        val migrated = legacyCustomTheme(stored, "custom-android-legacy", "My theme")
        assertThat(migrated.dark.primary).isEqualTo(Color(0xFF000001))
        assertThat(migrated.dark.surface).isEqualTo(Color(0xFF000009))
    }

    @Test
    fun `nothing stored means no migrated theme`() {
        assertThat(decodeLegacyCustomPalettes(raw = null, legacyCsv = null)).isNull()
        assertThat(decodeLegacyCustomPalettes(raw = "{ not json", legacyCsv = null)).isNull()
    }
}
