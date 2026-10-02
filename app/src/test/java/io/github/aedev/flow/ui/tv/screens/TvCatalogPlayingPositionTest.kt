package io.github.aedev.flow.ui.tv.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvCatalogPlayingPositionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a playlist cover stays in place while scrolling to its final track`() {
        val cover = EntityHeader("cover", HeaderStyle.COVER, EntityRef(EntityKind.PLAYLIST, "playlist"), "Playlist")
        val items =
            (1..50).map { index ->
                val ref = EntityRef(EntityKind.TRACK, "track$index")
                MetadataItem("row$index", ref, "Song $index", track = TrackDescriptor(ref, "Song $index"))
            }
        val table = CollectionBlock("tracks", null, CollectionLayout.TRACK_TABLE, ItemView.TRACK_ROW, items)
        val actions = TvCatalogActions({ it.track?.toMusicTrack("spotify") }, {}, { _, _, _, _ -> }, {})
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Box(Modifier.fillMaxSize()) {
                    CoverPage(cover, listOf(cover, table), actions, Modifier.fillMaxSize(), playingTrackId = "track10")
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val before =
            compose
                .onNodeWithText("Playlist")
                .fetchSemanticsNode()
                .boundsInRoot.top
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Song 50"))
        compose.waitForIdle()
        val after =
            compose
                .onNodeWithText("Playlist")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertThat(after).isWithin(0.1f).of(before)
    }

    @Test
    fun `opening a playing playlist shows and focuses its current track`() {
        val cover = EntityHeader("cover", HeaderStyle.COVER, EntityRef(EntityKind.PLAYLIST, "playlist"), "Playlist")
        val items =
            (1..50).map { index ->
                val ref = EntityRef(EntityKind.TRACK, "track$index")
                MetadataItem("row$index", ref, "Song $index", track = TrackDescriptor(ref, "Song $index"))
            }
        val table = CollectionBlock("tracks", null, CollectionLayout.TRACK_TABLE, ItemView.TRACK_ROW, items)
        val actions = TvCatalogActions({ it.track?.toMusicTrack("spotify") }, {}, { _, _, _, _ -> }, {})
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Box(Modifier.fillMaxSize()) {
                    CoverPage(cover, listOf(cover, table), actions, Modifier.fillMaxSize(), playingTrackId = "track48")
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Song 48").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Song 48").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Play").assertIsFocused()
        compose.onNodeWithText("Play").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Shuffle").assertIsFocused()
    }
}
