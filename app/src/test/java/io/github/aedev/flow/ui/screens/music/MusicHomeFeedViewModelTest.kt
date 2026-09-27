package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.account.AccountFeedClient
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.data.recommendation.MusicRecommendationAlgorithm
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.BrowseEndpoint
import io.github.aedev.flow.innertube.pages.HomePage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicHomeFeedViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = MutableStateFlow<AccountSession?>(null)
    private val store =
        mockk<AccountSessionStore> {
            every { session } returns sessions
            coEvery { current() } coAnswers { sessions.first() }
        }
    private val client = mockk<AccountFeedClient>()
    private val youTube = YouTube
    private val mapper =
        mockk<MusicRecommendationAlgorithm> {
            every { parseHomeSections(any()) } answers {
                firstArg<HomePage>().sections.map { MusicSection(title = it.title, tracks = emptyList()) }
            }
        }

    private fun page(
        vararg titles: String,
        continuation: String? = null,
        chips: List<HomePage.Chip>? = null,
    ) = HomePage(
        chips = chips,
        sections = titles.map { HomePage.Section(title = it, label = null, thumbnail = null, endpoint = null, items = emptyList()) },
        continuation = continuation,
    )

    private fun viewModel() = MusicHomeFeedViewModel(store, client, youTube, mapper)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(YouTube)
    }

    @After
    fun tearDown() {
        unmockkObject(YouTube)
        Dispatchers.resetMain()
    }

    @Test
    fun `every continuation page is followed and appended in order`() =
        runTest(dispatcher) {
            coEvery { youTube.home(continuation = null, params = null, via = any()) } returns
                Result.success(page("Take it easy", "Long listens", continuation = "c1"))
            coEvery { youTube.home(continuation = "c1", params = null, via = any()) } returns
                Result.success(page("Forgotten favorites", "Quick picks", continuation = "c2"))
            coEvery { youTube.home(continuation = "c2", params = null, via = any()) } returns
                Result.success(page("Covers and remixes"))
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()

            val state = vm.state.value
            assertThat(state.sections.map { it.title })
                .containsExactly("Take it easy", "Long listens", "Forgotten favorites", "Quick picks", "Covers and remixes")
                .inOrder()
            assertThat(state.isLoading).isFalse()
            assertThat(state.isLoadingMore).isFalse()
        }

    @Test
    fun `a repeated continuation token ends the walk instead of looping`() =
        runTest(dispatcher) {
            coEvery { youTube.home(continuation = null, params = null, via = any()) } returns
                Result.success(page("First", continuation = "loop"))
            coEvery { youTube.home(continuation = "loop", params = null, via = any()) } returns
                Result.success(page("Second", continuation = "loop"))
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()

            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("First", "Second").inOrder()
            coVerify(exactly = 1) { youTube.home(continuation = "loop", params = null, via = any()) }
        }

    @Test
    fun `a signed-in session reads the account's home, not the anonymous one`() =
        runTest(dispatcher) {
            sessions.value = AccountSession(cookie = "SAPISID=a")
            coEvery { client.musicHome(continuation = null, params = null) } returns Result.success(page("Mine"))
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()

            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("Mine")
            coVerify(exactly = 0) { youTube.home(any(), any(), any()) }
        }

    @Test
    fun `the home loads once within its freshness window`() =
        runTest(dispatcher) {
            coEvery { youTube.home(continuation = null, params = null, via = any()) } returns Result.success(page("Once"))
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            vm.load()
            advanceUntilIdle()

            coVerify(exactly = 1) { youTube.home(continuation = null, params = null, via = any()) }
        }

    @Test
    fun `a chip reloads with its params and selecting it again clears it`() =
        runTest(dispatcher) {
            val relax = HomePage.Chip("Relax", BrowseEndpoint(browseId = "FEmusic_home", params = "relax"), null)
            coEvery { youTube.home(continuation = null, params = null, via = any()) } returns
                Result.success(page("Default", chips = listOf(relax)))
            coEvery { youTube.home(continuation = null, params = "relax", via = any()) } returns Result.success(page("Calm"))
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            vm.selectChip(relax)
            advanceUntilIdle()
            assertThat(vm.state.value.selectedChip).isEqualTo(relax)
            assertThat(vm.state.value.chips).containsExactly(relax)
            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("Calm")

            vm.selectChip(relax)
            advanceUntilIdle()
            assertThat(vm.state.value.selectedChip).isNull()
            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("Default")
        }

    @Test
    fun `signing in swaps the anonymous home for the account's`() =
        runTest(dispatcher) {
            coEvery { youTube.home(continuation = null, params = null, via = any()) } returns Result.success(page("Anonymous"))
            coEvery { client.musicHome(continuation = null, params = null) } returns Result.success(page("Mine"))
            val vm = viewModel()
            vm.load()
            advanceUntilIdle()

            sessions.value = AccountSession(cookie = "SAPISID=a")
            advanceUntilIdle()

            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("Mine")
        }

    @Test
    fun `a failed first page is shown as an error and retried on the next visit`() =
        runTest(dispatcher) {
            val failure = CompletableDeferred<Unit>()
            coEvery { youTube.home(continuation = null, params = null, via = any()) } coAnswers {
                if (failure.isCompleted) Result.success(page("Recovered")) else Result.failure(IllegalStateException("offline"))
            }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.error).isEqualTo("offline")

            failure.complete(Unit)
            vm.load()
            advanceUntilIdle()
            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("Recovered")
        }

    @Test
    fun `a superseded load never writes over the load that replaced it`() =
        runTest(dispatcher) {
            val relax = HomePage.Chip("Relax", BrowseEndpoint(browseId = "FEmusic_home", params = "relax"), null)
            val stalled = CompletableDeferred<Unit>()
            // Mirrors YouTube.home: runCatching turns the cancellation into a failed Result, which reaches
            // the caller only after the replacing load has finished (the HTTP call winds down first).
            coEvery { youTube.home(continuation = null, params = null, via = any()) } coAnswers {
                runCatching {
                    try {
                        stalled.await()
                        page("Old")
                    } finally {
                        withContext(NonCancellable) { delay(1_000) }
                    }
                }
            }
            coEvery { youTube.home(continuation = null, params = "relax", via = any()) } returns Result.success(page("Calm"))
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            vm.selectChip(relax)
            advanceUntilIdle()

            val state = vm.state.value
            assertThat(state.error).isNull()
            assertThat(state.isLoading).isFalse()
            assertThat(state.sections.map { it.title }).containsExactly("Calm")
        }

    @Test
    fun `an expired account falls back to the anonymous home`() =
        runTest(dispatcher) {
            sessions.value = AccountSession(cookie = "SAPISID=a")
            coEvery { client.musicHome(continuation = null, params = null) } coAnswers {
                sessions.value = AccountSession(cookie = "SAPISID=a", expired = true)
                Result.failure(IllegalStateException("signed out"))
            }
            coEvery { youTube.home(continuation = null, params = null, via = any()) } returns Result.success(page("Anonymous"))
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()

            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("Anonymous")
            assertThat(vm.state.value.error).isNull()
        }

    @Test
    fun `a refresh keeps the current shelves until the new first page arrives`() =
        runTest(dispatcher) {
            val refreshed = CompletableDeferred<Result<HomePage>>()
            var calls = 0
            coEvery { youTube.home(continuation = null, params = null, via = any()) } coAnswers {
                if (calls++ == 0) Result.success(page("Before")) else refreshed.await()
            }
            val vm = viewModel()
            vm.load()
            advanceUntilIdle()

            vm.load(force = true)
            advanceUntilIdle()
            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("Before")
            assertThat(vm.state.value.isLoading).isTrue()

            refreshed.complete(Result.success(page("After")))
            advanceUntilIdle()
            assertThat(
                vm.state.value.sections
                    .map { it.title },
            ).containsExactly("After")
        }
}
