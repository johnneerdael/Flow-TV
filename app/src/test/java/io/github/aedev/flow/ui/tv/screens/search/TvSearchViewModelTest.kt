package io.github.aedev.flow.ui.tv.screens.search

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.github.aedev.flow.plugin.catalog.NoVideoPluginException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.SearchRequest
import nl.neerdael.milkbeat.catalog.Suggestions
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val history =
        mockk<SearchHistoryRepository>(relaxed = true) {
            every { getSearchHistoryFlow() } returns flowOf(emptyList())
            coEvery { isSearchHistoryEnabled() } returns true
        }
    private val stats = mockk<VideoStatsRecorder>(relaxed = true)

    private class FakeBackend(
        var pages: suspend (SearchRequest) -> Result<MetadataPage> = { Result.success(page(it.query)) },
        var typeahead: (String) -> Result<Suggestions> = { Result.success(Suggestions(emptyList())) },
    ) : TvSearchBackend {
        val searches = mutableListOf<SearchRequest>()
        val suggests = mutableListOf<String>()

        override suspend fun search(request: SearchRequest): Result<MetadataPage> {
            searches += request
            return pages(request)
        }

        override suspend fun suggest(query: String): Result<Suggestions> {
            suggests += query
            return typeahead(query)
        }
    }

    private val music = FakeBackend()
    private val videos = FakeBackend()

    private fun viewModel(query: String? = null) =
        TvSearchViewModel(
            SavedStateHandle(listOfNotNull(query?.let { "query" to it }).toMap()),
            music,
            videos,
            { null },
            history,
            stats,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `typing searches the half on screen once, after the pause, and asks both plugins for typeahead`() =
        runTest(dispatcher) {
            music.typeahead = { Result.success(Suggestions(listOf("$it live"))) }
            videos.typeahead = { Result.success(Suggestions(listOf("$it set"))) }
            val vm = viewModel()
            vm.showSource(TvSearchSource.MUSIC)

            vm.onQueryChange("ca")
            advanceTimeBy(100)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            assertThat(music.searches).containsExactly(SearchRequest("cafe"))
            assertThat(videos.searches).isEmpty()
            assertThat(music.suggests).containsExactly("cafe")
            assertThat(videos.suggests).containsExactly("cafe")
            val state = vm.state.value
            assertThat(state.music.blocks).isEqualTo(page("cafe").blocks)
            assertThat(state.music.filters.map { it.id }).containsExactly("songs", "albums").inOrder()
            assertThat(state.suggestions(TvSearchSource.VIDEOS)).containsExactly("cafe set", "cafe live").inOrder()
        }

    @Test
    fun `showing the other half searches it once and switching back fetches nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.showSource(TvSearchSource.MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.showSource(TvSearchSource.VIDEOS)
            advanceUntilIdle()
            vm.showSource(TvSearchSource.MUSIC)
            vm.showSource(TvSearchSource.VIDEOS)
            advanceUntilIdle()

            assertThat(music.searches).hasSize(1)
            assertThat(videos.searches).containsExactly(SearchRequest("cafe"))
            assertThat(vm.state.value.videos.loaded).isTrue()
        }

    @Test
    fun `a filter searches with its id, and picking it again goes back to the mixed results`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.showSource(TvSearchSource.MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.selectFilter("songs")
            assertThat(vm.state.value.music.filterId).isEqualTo("songs")
            advanceUntilIdle()
            vm.showAll("songs")
            advanceUntilIdle()
            vm.selectFilter("songs")
            advanceUntilIdle()

            assertThat(music.searches)
                .containsExactly(SearchRequest("cafe"), SearchRequest("cafe", "songs"), SearchRequest("cafe"))
                .inOrder()
            assertThat(vm.state.value.music.filterId).isNull()
        }

    @Test
    fun `the next page extends the results once, however often the end comes into view`() =
        runTest(dispatcher) {
            videos.pages = { request ->
                Result.success(
                    if (request.cursor == null) {
                        MetadataPage("p", listOf(results("a", "b")), nextCursor = "c1")
                    } else {
                        MetadataPage("p", listOf(results("b", "c")), nextCursor = null)
                    },
                )
            }
            val vm = viewModel()
            vm.showSource(TvSearchSource.VIDEOS)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.loadMore(TvSearchSource.VIDEOS)
            vm.loadMore(TvSearchSource.VIDEOS)
            advanceUntilIdle()
            vm.loadMore(TvSearchSource.VIDEOS)
            advanceUntilIdle()

            assertThat(videos.searches).containsExactly(SearchRequest("cafe"), SearchRequest("cafe", cursor = "c1")).inOrder()
            val block =
                vm.state.value.videos.blocks
                    .single() as CollectionBlock
            assertThat(block.items.map { it.id }).containsExactly("a", "b", "c").inOrder()
            assertThat(vm.state.value.videos.nextCursor).isNull()
        }

    @Test
    fun `a newer query cancels the older one, whose answer never shows`() =
        runTest(dispatcher) {
            music.pages = { request ->
                if (request.query == "old") delay(5_000)
                Result.success(page(request.query))
            }
            val vm = viewModel()
            vm.showSource(TvSearchSource.MUSIC)
            vm.onQueryChange("old")
            advanceTimeBy(1_000)
            vm.onQueryChange("new")
            advanceUntilIdle()

            assertThat(vm.state.value.music.query).isEqualTo("new")
            assertThat(vm.state.value.music.blocks).isEqualTo(page("new").blocks)
        }

    @Test
    fun `no chosen plugin says so, and a failing plugin gives its reason`() =
        runTest(dispatcher) {
            music.pages = { Result.failure(NoMetadataPluginException()) }
            videos.pages = { Result.failure(IllegalStateException("YouTube said no")) }
            val vm = viewModel()
            vm.showSource(TvSearchSource.MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()
            vm.showSource(TvSearchSource.VIDEOS)
            advanceUntilIdle()

            assertThat(vm.state.value.music.noPlugin).isTrue()
            assertThat(vm.state.value.videos.noPlugin).isFalse()
            assertThat(vm.state.value.videos.error).isEqualTo("YouTube said no")
        }

    @Test
    fun `a missing video plugin leaves the music typeahead`() =
        runTest(dispatcher) {
            music.typeahead = { Result.success(Suggestions(listOf("cafe del mar"))) }
            videos.typeahead = { Result.failure(NoVideoPluginException()) }
            val vm = viewModel()
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            assertThat(vm.state.value.suggestions(TvSearchSource.VIDEOS)).containsExactly("cafe del mar")
        }

    @Test
    fun `clearing the query drops results and typeahead but keeps the filters`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.showSource(TvSearchSource.MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()
            vm.onQueryChange("")
            advanceUntilIdle()

            val state = vm.state.value
            assertThat(state.music.blocks).isEmpty()
            assertThat(state.music.filters).isNotEmpty()
            assertThat(state.suggestions(TvSearchSource.MUSIC)).isEmpty()
        }

    @Test
    fun `a restored query searches when its half is shown, and a picked suggestion is saved`() =
        runTest(dispatcher) {
            val vm = viewModel(query = "cafe")
            vm.showSource(TvSearchSource.MUSIC)
            advanceUntilIdle()
            vm.submit("cafe del mar", SearchType.SUGGESTION)
            advanceUntilIdle()

            assertThat(music.searches).containsExactly(SearchRequest("cafe"), SearchRequest("cafe del mar")).inOrder()
            coVerify { history.saveSearchQuery("cafe del mar", SearchType.SUGGESTION) }
        }

    private companion object {
        fun item(id: String) = MetadataItem(id = id, entity = EntityRef(EntityKind.VIDEO, id), title = id)

        fun results(vararg ids: String) =
            CollectionBlock(
                id = "results",
                header = null,
                layout = CollectionLayout.HORIZONTAL_SHELF,
                defaultItemView = ItemView.LANDSCAPE_CARD,
                items = ids.map(::item),
            )

        fun page(query: String) =
            MetadataPage(
                id = "search/$query",
                blocks = listOf(results("$query-1", "$query-2")),
                filters = FilterControl(listOf(FilterOption("songs", "Songs"), FilterOption("albums", "Albums"))),
            )
    }
}
