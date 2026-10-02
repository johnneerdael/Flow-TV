package io.github.aedev.flow.ui.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.plugin.catalog.LibraryProvider
import io.github.aedev.flow.plugin.catalog.ProviderLibraryItem
import io.github.aedev.flow.plugin.catalog.trackDescriptor
import io.github.aedev.flow.ui.tv.screens.library.TvMergedPlaylistGrid
import io.github.aedev.flow.ui.tv.screens.library.mergedLikedQueue
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.flow.flowOf
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TvMergedPlaylistsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedIdsOpenTheirOriginAndLikedSongsAppearsOnce() {
        val entity = EntityRef(EntityKind.PLAYLIST, "same")
        val items =
            listOf("a" to "Provider A", "b" to "Provider B").map { (id, name) ->
                ProviderLibraryItem(LibraryProvider(id, name, 1, "account", id), MetadataItem("same", entity, "Shared playlist"))
            }
        val done = LoadState.NotLoading(endOfPaginationReached = true)
        val pages = flowOf(PagingData.from(items, sourceLoadStates = LoadStates(done, done, done)))
        val opened = mutableListOf<Pair<String, EntityRef>>()
        var likes = 0
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TvMergedPlaylistGrid(emptyList(), emptyList(), pages.collectAsLazyPagingItems(), { likes++ }, {}, {}, { provider, ref ->
                        opened += provider to ref
                    })
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Shared playlist").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithText("Liked songs").assertCountEquals(1)
        compose.onNodeWithText("Liked songs").assertIsFocused()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressDPadRight()
        compose.waitForIdle()
        device.pressDPadCenter()
        compose.waitForIdle()
        device.pressDPadRight()
        compose.waitForIdle()
        device.pressDPadCenter()
        compose.waitForIdle()
        assertEquals(listOf("a" to entity, "b" to entity), opened)
        compose.onNodeWithText("Liked songs").performClick()
        assertEquals(1, likes)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            UiDevice
                .getInstance(
                    instrumentation,
                ).takeScreenshot(File(instrumentation.targetContext.externalCacheDir, "merged-playlists.png")),
        )
    }

    @Test fun collidingLikedSongsKeepRawDescriptorsThroughPlayerUris() {
        val raw = EntityRef(EntityKind.TRACK, "shared-id")
        val tracks =
            listOf("a", "b").map { provider ->
                val descriptor = TrackDescriptor(raw, provider, ids = mapOf(provider to raw.providerId))
                ProviderLibraryItem(
                    LibraryProvider(provider, provider, 1, "account", provider),
                    MetadataItem(raw.providerId, raw, provider, track = descriptor),
                )
            }
        val queue = mergedLikedQueue(emptyList(), tracks)
        assertEquals(2, queue.map { it.videoId }.distinct().size)
        queue.forEach { track ->
            val uri = MusicVideoItems.uri(track, false)
            assertEquals(track.videoId, uri.authority)
            assertEquals(track.trackDescriptor(), MusicVideoItems.descriptor(uri))
            assertEquals(raw, MusicVideoItems.descriptor(uri).ref)
        }
    }
}
