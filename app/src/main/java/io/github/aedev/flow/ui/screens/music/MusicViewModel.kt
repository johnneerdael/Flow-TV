package io.github.aedev.flow.ui.screens.music

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.PlaylistDetails
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The app's own music playlists, opened as a collection page. */
@HiltViewModel
class MusicViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val localPlaylistRepository: PlaylistRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MusicUiState())
        val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

        fun fetchPlaylistDetails(playlistId: String) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                _uiState.update { it.copy(isPlaylistLoading = true, playlistDetails = null, error = null) }
                val localPlaylist = localPlaylistRepository.getPlaylistInfo(playlistId)
                if (localPlaylist == null) {
                    _uiState.update {
                        it.copy(isPlaylistLoading = false, error = context.getString(R.string.error_failed_to_load_playlist))
                    }
                    return@launch
                }
                val videos = localPlaylistRepository.getPlaylistVideosFlow(playlistId).firstOrNull().orEmpty()
                val tracks =
                    videos.map { video ->
                        MusicTrack(
                            videoId = video.id,
                            title = video.title,
                            artist = video.channelName,
                            thumbnailUrl = video.thumbnailUrl,
                            duration = video.duration,
                            sourceUrl = "",
                        )
                    }
                val details =
                    PlaylistDetails(
                        id = localPlaylist.id,
                        title = localPlaylist.name,
                        thumbnailUrl = localPlaylist.thumbnailUrl,
                        author = context.getString(R.string.you),
                        trackCount = tracks.size,
                        description = localPlaylist.description,
                        tracks = tracks,
                    )
                _uiState.update { it.copy(isPlaylistLoading = false, playlistDetails = details) }
            }
        }

        fun clearPlaylistDetails() {
            _uiState.update { it.copy(playlistDetails = null) }
        }
    }

data class MusicUiState(
    val error: String? = null,
    val playlistDetails: PlaylistDetails? = null,
    val isPlaylistLoading: Boolean = false,
)
