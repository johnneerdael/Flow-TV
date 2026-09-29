package io.github.aedev.flow.ui.tv.screens.playlist

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.catalog.TracksRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvPlaylistViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val remote = EntityRef(EntityKind.PLAYLIST, "PL1")
    private val header = EntityHeader(id = "header", style = HeaderStyle.COVER, entity = remote, title = "Sets", tracks = remote)
    private val library = mockk<PlaylistRepository>()
    private val video = mockk<PluginVideoProvider>()

    private fun viewModel(id: String) = TvPlaylistViewModel(SavedStateHandle(mapOf(TvPlaylistViewModel.PLAYLIST_ARG to id)), library, video)

    private fun track(id: String) = TrackDescriptor(ref = EntityRef(EntityKind.VIDEO, id), title = id, durationMs = 61_000)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a playlist of the app's library stays local and asks the plugin nothing`() =
        runTest(dispatcher) {
            coEvery { library.getPlaylistInfo("mine") } returns
                PlaylistInfo(
                    id = "mine",
                    name = "Mine",
                    description = "",
                    videoCount = 0,
                    thumbnailUrl = "",
                    isPrivate = true,
                    createdAt = 0,
                )
            val vm = viewModel("mine")

            vm.load()
            advanceUntilIdle()

            assertThat(vm.source.value).isEqualTo(TvPlaylistSource.LOCAL)
            coVerify(exactly = 0) { video.page(any(), any(), any()) }
        }

    @Test
    fun `watch later is local without a library lookup`() =
        runTest(dispatcher) {
            val vm = viewModel(PlaylistRepository.WATCH_LATER_ID)

            vm.load()
            advanceUntilIdle()

            assertThat(vm.source.value).isEqualTo(TvPlaylistSource.LOCAL)
        }

    @Test
    fun `any other playlist is the video plugin's, with its header`() =
        runTest(dispatcher) {
            coEvery { library.getPlaylistInfo("PL1") } returns null
            coEvery { video.page(remote, null, null) } returns Result.success(MetadataPage("playlist", listOf(header), nextCursor = "more"))
            val vm = viewModel("PL1")

            vm.load()
            advanceUntilIdle()

            assertThat(vm.source.value).isEqualTo(TvPlaylistSource.REMOTE)
            assertThat(vm.state.value.header).isEqualTo(header)
            assertThat(vm.state.value.isLoading).isFalse()
        }

    @Test
    fun `play all reads every page of tracks once, and keeps them`() =
        runTest(dispatcher) {
            coEvery { video.tracks(TracksRequest(remote, null)) } returns
                Result.success(TrackList(listOf(track("a"), track("b")), next = "p2"))
            coEvery { video.tracks(TracksRequest(remote, "p2")) } returns Result.success(TrackList(listOf(track("b"), track("c"))))
            val vm = viewModel("PL1")

            val first = vm.queue()
            val again = vm.queue()

            assertThat(first.map { it.id }).containsExactly("a", "b", "c").inOrder()
            assertThat(first.first().duration).isEqualTo(61)
            assertThat(again).isEqualTo(first)
            coVerify(exactly = 2) { video.tracks(any()) }
        }
}
