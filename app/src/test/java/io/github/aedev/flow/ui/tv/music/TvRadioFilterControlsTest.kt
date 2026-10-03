package io.github.aedev.flow.ui.tv.music

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.RadioTuningState
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.catalog.FilterOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TvRadioFilterControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `all four server choices are reachable and pass opaque IDs unchanged`() {
        val choices = listOf("All", "Familiar", "Popular", "Discover").map { FilterOption("opaque-$it", it) }
        var selected: String? = null
        compose.setContent { TvTheme { TvRadioFilterControls(RadioTuningState(choices), { selected = it }) } }
        choices.forEach {
            compose.onNodeWithText(it.label).performClick()
            assertThat(selected).isEqualTo(it.id)
        }
    }
}
