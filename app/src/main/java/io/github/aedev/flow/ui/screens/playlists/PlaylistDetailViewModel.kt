package io.github.aedev.flow.ui.screens.playlists

import android.content.Context
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.engagement.LikedMediaUseCase
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.WatchLaterCleanup
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.playlist.PlaylistTransfer
import io.github.aedev.flow.ui.components.library.PlaylistSortOrder
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionUndo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHARING_TIMEOUT_MS = 5_000L

data class PlaylistUiMessage(
    @param:StringRes val stringRes: Int = 0,
    @param:PluralsRes val pluralRes: Int = 0,
    val count: Int = 0,
    val args: List<Any> = emptyList(),
    val undo: QuickActionUndo? = null,
)

data class PlaylistDetailUiState(
    val playlistName: String = "",
    val description: String = "",
    val isPrivate: Boolean = false,
    val videos: List<Video> = emptyList(),
    val thumbnailUrl: String = "",
    val isLocalPlaylist: Boolean = false,
    val isSaved: Boolean = false,
    val isWatchLater: Boolean = false,
    /** Liked videos: read from the likes, where removing a video unlikes it. */
    val isLikes: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@HiltViewModel
class PlaylistDetailViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: PlaylistRepository,
        private val playerPreferences: PlayerPreferences,
        private val watchLaterCleanup: WatchLaterCleanup,
        private val transfer: PlaylistTransfer,
        private val likedVideos: LikedVideosRepository,
        private val likedMedia: LikedMediaUseCase,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        val playlistId: String = checkNotNull(savedStateHandle["playlistId"])
        private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARING_TIMEOUT_MS)

        private val _uiState = MutableStateFlow(PlaylistDetailUiState())
        val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

        private val _messages = Channel<PlaylistUiMessage>(Channel.BUFFERED)
        val messages: Flow<PlaylistUiMessage> = _messages.receiveAsFlow()

        val sortOrder: StateFlow<PlaylistSortOrder> =
            combine(
                playerPreferences.playlistSortOrder(playlistId),
                _uiState.map { it.isLocalPlaylist to it.isLikes }.distinctUntilChanged(),
            ) { stored, (isLocal, isLikes) ->
                PlaylistSortOrder
                    .fromStorageValue(stored)
                    .takeIf { it in PlaylistSortOrder.availableFor(isLocal, isLikes) } ?: PlaylistSortOrder.defaultFor(isLikes)
            }.stateIn(viewModelScope, sharing, PlaylistSortOrder.MANUAL)

        init {
            loadPlaylist()
        }

        fun retry() {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            loadPlaylist()
        }

        fun updatePlaylist(
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                repository.updatePlaylistMetadata(playlistId, name, description, _uiState.value.isPrivate)
                _uiState.update { it.copy(playlistName = name, description = description) }
            }
        }

        fun deletePlaylist() {
            viewModelScope.launch {
                repository.deletePlaylist(playlistId)
            }
        }

        fun exportTo(target: Uri) {
            viewModelScope.launch {
                val state = _uiState.value
                val saved = transfer.writeTo(target, state.playlistName, state.description, state.videos)
                _messages.send(PlaylistUiMessage(stringRes = if (saved) R.string.playlist_exported else R.string.playlist_export_failed))
            }
        }

        private fun loadPlaylist() {
            viewModelScope.launch {
                if (playlistId == PlaylistRepository.WATCH_LATER_ID) {
                    loadWatchLater()
                    return@launch
                }
                if (playlistId == PlaylistRepository.LIKED_VIDEOS_ID) {
                    loadLikedVideos()
                    return@launch
                }

                val localInfo = repository.getPlaylistInfo(playlistId)
                if (localInfo != null) {
                    loadLocalPlaylist(localInfo)
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMessage = context.getString(R.string.playlist_load_failed)) }
                }
            }
        }

        private suspend fun loadWatchLater() {
            _uiState.update {
                it.copy(
                    playlistName = context.getString(R.string.watch_later),
                    description = "",
                    isPrivate = true,
                    isLocalPlaylist = true,
                    isSaved = false,
                    isWatchLater = true,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            var sweepStarted = false
            repository.getVideoOnlyWatchLaterFlow().collect { videos ->
                _uiState.update {
                    it.copy(
                        videos = videos,
                        thumbnailUrl = videos.firstOrNull()?.thumbnailUrl.orEmpty(),
                    )
                }
                if (!sweepStarted && videos.isNotEmpty()) {
                    sweepStarted = true
                    viewModelScope.launch { sweepWatched(videos) }
                }
            }
        }

        private suspend fun loadLikedVideos() {
            _uiState.update {
                it.copy(
                    playlistName = context.getString(R.string.liked_videos_playlist),
                    description = "",
                    isPrivate = true,
                    isLocalPlaylist = true,
                    isSaved = false,
                    isLikes = true,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            likedVideos.getLikedVideosFlow().collect { likes ->
                val videos = likes.map { it.toPlaylistVideo() }
                _uiState.update { it.copy(videos = videos, thumbnailUrl = videos.firstOrNull()?.thumbnailUrl.orEmpty()) }
            }
        }

        private suspend fun sweepWatched(videos: List<Video>) {
            val removed = watchLaterCleanup.sweep(videos)
            if (removed.isEmpty()) return
            _messages.send(
                PlaylistUiMessage(
                    pluralRes = R.plurals.watch_later_watched_removed,
                    count = removed.size,
                    args = listOf(removed.size),
                    undo = QuickActionUndo.PlaylistRemoval(removed),
                ),
            )
        }

        private suspend fun loadLocalPlaylist(localInfo: PlaylistInfo) {
            val isSaved = repository.isExternalPlaylistSaved(playlistId)
            _uiState.update {
                it.copy(
                    playlistName = localInfo.name,
                    description = localInfo.description,
                    isPrivate = localInfo.isPrivate,
                    thumbnailUrl = localInfo.thumbnailUrl,
                    isLocalPlaylist = true,
                    isSaved = isSaved,
                    isWatchLater = false,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            repository.getPlaylistVideosWithAddedAtFlow(playlistId).collect { videos ->
                _uiState.update { it.copy(videos = videos) }
            }
        }
    }

private fun io.github.aedev.flow.data.music.model.MusicTrack.toPlaylistVideo(): Video =
    Video(
        id = videoId,
        title = title,
        channelName = artist,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = views,
        uploadDate = "",
        isMusic = true,
    )
