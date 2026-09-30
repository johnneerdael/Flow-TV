package io.github.aedev.flow.ui.startup

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
}
