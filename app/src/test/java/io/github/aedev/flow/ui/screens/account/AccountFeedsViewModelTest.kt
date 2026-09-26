package io.github.aedev.flow.ui.screens.account

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.account.AccountFeedClient
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.MusicRecommendationAlgorithm
import io.github.aedev.flow.innertube.pages.account.AccountVideoFeed
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountFeedsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store =
        mockk<AccountSessionStore> {
            every { session } returns flowOf(AccountSession(cookie = "SAPISID=x"))
            coEvery { clear() } just runs
        }
    private val client = mockk<AccountFeedClient>()
    private val mapper = mockk<MusicRecommendationAlgorithm>(relaxed = true)

    private fun video(id: String) =
        Video(id = id, title = id, channelName = "c", channelId = "c", thumbnailUrl = "", duration = 0, viewCount = 0, uploadDate = "")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a video feed loads once within its freshness window`() =
        runTest(dispatcher) {
            coEvery { client.videoHome(null) } returns Result.success(AccountVideoFeed(listOf(video("a")), "NEXT", true))
            val vm = AccountFeedsViewModel(store, client, mapper)
            vm.loadVideoFeed(AccountVideoSurface.HOME)
            advanceUntilIdle()
            vm.loadVideoFeed(AccountVideoSurface.HOME)
            advanceUntilIdle()
            assertThat(
                vm.videoFeeds
                    .getValue(AccountVideoSurface.HOME)
                    .value.videos
                    .map { it.id },
            ).containsExactly("a")
            coVerify(exactly = 1) { client.videoHome(null) }
        }

    @Test
    fun `load more appends new videos only`() =
        runTest(dispatcher) {
            coEvery { client.subscriptionsFeed(null) } returns Result.success(AccountVideoFeed(listOf(video("a")), "NEXT", true))
            coEvery { client.subscriptionsFeed("NEXT") } returns
                Result.success(AccountVideoFeed(listOf(video("a"), video("b")), null, null))
            val vm = AccountFeedsViewModel(store, client, mapper)
            vm.loadVideoFeed(AccountVideoSurface.SUBSCRIPTIONS)
            advanceUntilIdle()
            vm.loadMoreVideoFeed(AccountVideoSurface.SUBSCRIPTIONS)
            advanceUntilIdle()
            val state = vm.videoFeeds.getValue(AccountVideoSurface.SUBSCRIPTIONS).value
            assertThat(state.videos.map { it.id }).containsExactly("a", "b").inOrder()
            assertThat(state.continuation).isNull()
        }

    @Test
    fun `a failure is shown as an error`() =
        runTest(dispatcher) {
            coEvery { client.watchHistory(null) } returns Result.failure(IllegalStateException("boom"))
            val vm = AccountFeedsViewModel(store, client, mapper)
            vm.loadVideoFeed(AccountVideoSurface.HISTORY)
            advanceUntilIdle()
            assertThat(
                vm.videoFeeds
                    .getValue(AccountVideoSurface.HISTORY)
                    .value.error,
            ).isEqualTo("boom")
        }

    @Test
    fun `sign out discards an in-flight load`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Result<AccountVideoFeed>>()
            coEvery { client.videoHome(null) } coAnswers { gate.await() }
            val vm = AccountFeedsViewModel(store, client, mapper)
            vm.loadVideoFeed(AccountVideoSurface.HOME)
            advanceUntilIdle()
            vm.signOut()
            gate.complete(Result.success(AccountVideoFeed(listOf(video("late")), null, true)))
            advanceUntilIdle()
            assertThat(
                vm.videoFeeds
                    .getValue(AccountVideoSurface.HOME)
                    .value.videos,
            ).isEmpty()
            coVerify { store.clear() }
        }

    @Test
    fun `a failed liked-music fetch only fails that section`() =
        runTest(dispatcher) {
            coEvery { client.playlist(any()) } returns Result.failure(IllegalStateException("liked broke"))
            coEvery { client.musicHistory() } returns
                Result.success(
                    io.github.aedev.flow.innertube.pages
                        .HistoryPage(emptyList()),
                )
            coEvery { client.libraryPlaylists() } returns
                Result.success(
                    io.github.aedev.flow.innertube.pages
                        .LibraryPage(emptyList(), null),
                )
            val vm = AccountFeedsViewModel(store, client, mapper)
            vm.loadMusicLibrary()
            advanceUntilIdle()
            val state = vm.musicLibrary.value
            assertThat(state.likedError).isEqualTo("liked broke")
            assertThat(state.historyError).isNull()
            assertThat(state.playlistsError).isNull()
        }

    @Test
    fun `a new session discards feeds loaded for the previous one`() =
        runTest(dispatcher) {
            val sessions = kotlinx.coroutines.flow.MutableStateFlow<AccountSession?>(AccountSession(cookie = "SAPISID=a"))
            val switching = mockk<AccountSessionStore> { every { session } returns sessions }
            coEvery { client.videoHome(null) } returns Result.success(AccountVideoFeed(listOf(video("from-a")), null, true))
            val vm = AccountFeedsViewModel(switching, client, mapper)
            vm.loadVideoFeed(AccountVideoSurface.HOME)
            advanceUntilIdle()
            sessions.value = AccountSession(cookie = "SAPISID=b")
            advanceUntilIdle()
            assertThat(
                vm.videoFeeds
                    .getValue(AccountVideoSurface.HOME)
                    .value.videos,
            ).isEmpty()
        }
}
