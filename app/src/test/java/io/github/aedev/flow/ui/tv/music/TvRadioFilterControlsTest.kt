package io.github.aedev.flow.ui.tv.music

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.plugin.playback.RadioTuningState
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.catalog.FilterOption
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvRadioFilterControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val manager = EnhancedMusicPlayerManager

    @After fun cleanup() {
        manager.queueState.value = emptyList()
        manager.automixState.value = emptyList()
        manager.currentQueueIndexState.value = 0
    }

    @Test
    fun `all four choices remain visible above a scrolled queue and pass opaque IDs unchanged`() {
        val choices = listOf("All", "Familiar", "Popular", "Discover").map { FilterOption("opaque-$it", it) }
        val queue = (0..49).map { MusicTrack("$it", "Song $it", "Artist", "", 120) }
        manager.queueState.value = queue
        manager.automixState.value = emptyList()
        manager.currentQueueIndexState.value = 48
        var selected: String? = null
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Box(Modifier.width(650.dp).height(600.dp)) {
                    TvMusicQueuePanel(true, manager, {}, {}, RadioTuningState(choices), { selected = it })
                }
            }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText("Song 48").assertIsFocused()
        choices.forEach {
            compose.onNodeWithText(it.label).assertIsDisplayed().performClick()
            assertThat(selected).isEqualTo(it.id)
        }
        assertThat(manager.queue.value).containsExactlyElementsIn(queue).inOrder()
        assertThat(manager.currentQueueIndex.value).isEqualTo(48)
    }
}
