package io.github.aedev.flow.ui.tv.music

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicPlayerState
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.tv.theme.TvTheme
import io.mockk.mockk
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvMusicCompletionTest {
    @get:Rule val compose = createComposeRule()
    private val manager = EnhancedMusicPlayerManager

    @After fun cleanup() {
        manager.playbackState.value = MusicPlayerState()
        manager.currentTrackState.value = null
        manager.radioLoadingState.value = false
    }

    @Test
    fun `pause buffering and a pending mix keep the player open but a finished queue closes it`() {
        var collapsed = 0
        manager.currentTrackState.value = MusicTrack("song", "Song", "Artist", "", 120)
        manager.playbackState.value = MusicPlayerState()
        manager.radioLoadingState.value = false
        val viewModel = mockk<MusicPlayerViewModel>(relaxed = true)
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme { TvMusicNowPlayingScreen(viewModel, { collapsed++ }) }
        }
        compose.waitForIdle()
        assertThat(collapsed).isEqualTo(0)
        compose.runOnIdle { manager.playbackState.value = MusicPlayerState(isBuffering = true) }
        compose.waitForIdle()
        assertThat(collapsed).isEqualTo(0)
        compose.runOnIdle {
            manager.radioLoadingState.value = true
            manager.playbackState.value = MusicPlayerState(isEnded = true)
        }
        compose.waitForIdle()
        assertThat(collapsed).isEqualTo(0)
        compose.runOnIdle { manager.radioLoadingState.value = false }
        compose.waitForIdle()
        assertThat(collapsed).isEqualTo(1)
    }
}
