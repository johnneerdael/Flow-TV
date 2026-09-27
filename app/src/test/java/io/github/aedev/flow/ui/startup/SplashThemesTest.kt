package io.github.aedev.flow.ui.startup

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import org.junit.Test

class SplashThemesTest {
    @Test
    fun `each tone opens on its own splash`() {
        assertThat(SplashTone.entries.map(::splashThemeFor))
            .containsExactly(
                R.style.Theme_Flow_Starting_Light,
                R.style.Theme_Flow_Starting_Dark,
                R.style.Theme_Flow_Starting_Black,
            ).inOrder()
    }

    @Test
    fun `the tone follows the theme background`() {
        assertThat(splashTone(Color.Black)).isEqualTo(SplashTone.BLACK)
        assertThat(splashTone(Color(0xFF0F0F0F))).isEqualTo(SplashTone.DARK)
        assertThat(splashTone(Color.White)).isEqualTo(SplashTone.LIGHT)
    }
}
