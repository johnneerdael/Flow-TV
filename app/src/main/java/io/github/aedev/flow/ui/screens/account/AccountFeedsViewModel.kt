package io.github.aedev.flow.ui.screens.account

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountFeedClient
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.data.account.LIKED_MUSIC_PLAYLIST_ID
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.MusicRecommendationAlgorithm
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.innertube.pages.account.AccountVideoFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AccountVideoSurface { HOME, SUBSCRIPTIONS, HISTORY }

data class AccountVideoFeedState(
    val videos: List<Video> = emptyList(),
    val continuation: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loadedAtMs: Long = 0L,
)

data class AccountMusicState(
    val sections: List<MusicSection> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val loadedAtMs: Long = 0L,
)

data class AccountMusicLibraryState(
    val likedMusic: List<MusicTrack> = emptyList(),
    val recentlyPlayed: List<MusicTrack> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val isLoading: Boolean = false,
    val likedError: String? = null,
    val historyError: String? = null,
    val playlistsError: String? = null,
    val loadedAtMs: Long = 0L,
)

@HiltViewModel
class AccountFeedsViewModel
    @Inject
    constructor(
        private val store: AccountSessionStore,
        private val client: AccountFeedClient,
        private val musicMapper: MusicRecommendationAlgorithm,
    ) : ViewModel() {
        val session: StateFlow<AccountSession?> =
            store.session.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)
        val isSignedIn: StateFlow<Boolean> =
            store.session
                .map { it != null && !it.expired }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)
        val isExpired: StateFlow<Boolean> =
            store.session
                .map { it?.expired == true }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)

        private val videoStates = AccountVideoSurface.entries.associateWith { MutableStateFlow(AccountVideoFeedState()) }
        val videoFeeds: Map<AccountVideoSurface, StateFlow<AccountVideoFeedState>> = videoStates.mapValues { it.value.asStateFlow() }

        private val _music = MutableStateFlow(AccountMusicState())
        val music: StateFlow<AccountMusicState> = _music.asStateFlow()

        private val _musicLibrary = MutableStateFlow(AccountMusicLibraryState())
        val musicLibrary: StateFlow<AccountMusicLibraryState> = _musicLibrary.asStateFlow()

        private val jobs = mutableMapOf<String, Job>()

        init {
            // Feeds belong to one session: a sign-in over another account, or a re-sign-in after
            // expiry, must never keep showing what was loaded before.
            viewModelScope.launch {
                store.session
                    .map { it?.cookie to it?.expired }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { resetFeeds() }
            }
        }

        fun loadVideoFeed(
            surface: AccountVideoSurface,
            force: Boolean = false,
        ) {
            val state = videoStates.getValue(surface)
            if (!force && state.value.loadedAtMs.isFresh()) return
            launchOnce("video-$surface") {
                state.update { it.copy(isLoading = true, error = null) }
                fetch(surface, null)
                    .onSuccess { feed ->
                        Log.d(TAG, "$surface: ${feed.videos.size} videos")
                        state.value = AccountVideoFeedState(feed.videos, feed.continuation, loadedAtMs = now())
                    }.onFailure { error ->
                        Log.w(TAG, "$surface failed", error)
                        state.update { it.copy(isLoading = false, error = error.message) }
                    }
            }
        }

        fun loadMoreVideoFeed(surface: AccountVideoSurface) {
            val state = videoStates.getValue(surface)
            val continuation = state.value.continuation ?: return
            if (state.value.isLoading) return
            launchOnce("more-$surface") {
                state.update { it.copy(isLoading = true) }
                fetch(surface, continuation)
                    .onSuccess { feed ->
                        state.update {
                            it.copy(
                                videos = (it.videos + feed.videos).distinctBy(Video::id),
                                continuation = feed.continuation,
                                isLoading = false,
                            )
                        }
                    }.onFailure { error -> state.update { it.copy(isLoading = false, error = error.message) } }
            }
        }

        fun loadMusicHome(force: Boolean = false) {
            if (!force && _music.value.loadedAtMs.isFresh()) return
            launchOnce("music-home") {
                _music.update { it.copy(isLoading = true, error = null) }
                client
                    .musicHome()
                    .onSuccess { home ->
                        val sections = musicMapper.parseHomeSections(home)
                        Log.d(TAG, "music home: ${home.sections.size} sections, ${sections.sumOf { it.tracks.size }} tracks")
                        _music.value = AccountMusicState(sections, loadedAtMs = now())
                    }.onFailure { error ->
                        Log.w(TAG, "music home failed", error)
                        _music.update { it.copy(isLoading = false, error = error.message) }
                    }
            }
        }

        fun loadMusicLibrary(force: Boolean = false) {
            if (!force && _musicLibrary.value.loadedAtMs.isFresh()) return
            launchOnce("music-library") {
                _musicLibrary.update { it.copy(isLoading = true) }
                val liked = async { client.playlist(LIKED_MUSIC_PLAYLIST_ID) }
                val history = async { client.musicHistory() }
                val playlists = async { client.libraryPlaylists() }
                val likedResult = liked.await()
                val historyResult = history.await()
                val playlistsResult = playlists.await()
                listOf(likedResult, historyResult, playlistsResult).mapNotNull { it.exceptionOrNull() }.forEach {
                    Log.w(TAG, "music library part failed", it)
                }
                _musicLibrary.value =
                    AccountMusicLibraryState(
                        likedMusic =
                            likedResult
                                .getOrNull()
                                ?.songs
                                ?.let { tracks(it) }
                                .orEmpty(),
                        recentlyPlayed =
                            historyResult
                                .getOrNull()
                                ?.sections
                                .orEmpty()
                                .flatMap { tracks(it.songs) },
                        playlists =
                            playlistsResult
                                .getOrNull()
                                ?.items
                                .orEmpty()
                                .filterIsInstance<PlaylistItem>()
                                .map { it.toPlaylist() },
                        likedError = likedResult.exceptionOrNull()?.message,
                        historyError = historyResult.exceptionOrNull()?.message,
                        playlistsError = playlistsResult.exceptionOrNull()?.message,
                        loadedAtMs = now(),
                    )
            }
        }

        suspend fun playlistTracks(playlistId: String): List<MusicTrack> =
            client
                .playlist(playlistId)
                .getOrNull()
                ?.songs
                ?.let { tracks(it) }
                .orEmpty()

        fun signOut() {
            resetFeeds()
            viewModelScope.launch { store.clear() }
        }

        private fun resetFeeds() {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
            videoStates.values.forEach { it.value = AccountVideoFeedState() }
            _music.value = AccountMusicState()
            _musicLibrary.value = AccountMusicLibraryState()
        }

        private suspend fun fetch(
            surface: AccountVideoSurface,
            continuation: String?,
        ): Result<AccountVideoFeed> =
            when (surface) {
                AccountVideoSurface.HOME -> client.videoHome(continuation)
                AccountVideoSurface.SUBSCRIPTIONS -> client.subscriptionsFeed(continuation)
                AccountVideoSurface.HISTORY -> client.watchHistory(continuation)
            }

        private fun tracks(items: List<YTItem>): List<MusicTrack> =
            musicMapper
                .parseHomeSections(
                    HomePage(
                        chips = null,
                        sections = listOf(HomePage.Section(title = "", label = null, thumbnail = null, endpoint = null, items = items)),
                    ),
                ).flatMap { it.tracks }

        private fun PlaylistItem.toPlaylist() =
            Playlist(id = id, name = title, thumbnailUrl = thumbnail.orEmpty(), videoCount = 0, isLocal = false)

        private fun launchOnce(
            key: String,
            block: suspend CoroutineScope.() -> Unit,
        ) {
            if (jobs[key]?.isActive == true) return
            jobs[key] = viewModelScope.launch(block = block)
        }

        private fun Long.isFresh(): Boolean = this > 0L && now() - this < FRESH_FOR_MS

        private fun now(): Long = System.currentTimeMillis()

        private companion object {
            const val TAG = "AccountFeeds"
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
            const val FRESH_FOR_MS = 10 * 60_000L
        }
    }
