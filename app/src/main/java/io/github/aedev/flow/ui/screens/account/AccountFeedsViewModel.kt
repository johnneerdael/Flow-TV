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
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.innertube.pages.HomePage
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

data class AccountVideoFeedState(
    val videos: List<Video> = emptyList(),
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

        private val _history = MutableStateFlow(AccountVideoFeedState())
        val history: StateFlow<AccountVideoFeedState> = _history.asStateFlow()

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

        fun loadHistory(force: Boolean = false) {
            if (!force && _history.value.loadedAtMs.isFresh()) return
            launchOnce("history") {
                _history.update { it.copy(isLoading = true, error = null) }
                client
                    .watchHistory()
                    .onSuccess { feed ->
                        Log.d(TAG, "history: ${feed.videos.size} videos")
                        _history.value = AccountVideoFeedState(feed.videos, loadedAtMs = now())
                    }.onFailure { error ->
                        Log.w(TAG, "history failed", error)
                        _history.update { it.copy(isLoading = false, error = error.message) }
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
            _history.value = AccountVideoFeedState()
            _musicLibrary.value = AccountMusicLibraryState()
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
