package io.github.aedev.flow.ui.tv.screens.playlist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.paging.PluginPagingSource
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.ui.tv.catalog.toTvVideo
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.TracksRequest
import javax.inject.Inject

/** Where a playlist route's id lives: the app's own library, or the video plugin. */
enum class TvPlaylistSource { LOCAL, REMOTE }

data class TvRemotePlaylistState(
    val header: EntityHeader? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * The playlist route: an id the app's library knows stays local (its own screen and view model);
 * any other is a playlist of the video plugin, its videos paged as they are scrolled to and read
 * whole, once, for Play all and Shuffle.
 */
@HiltViewModel
class TvPlaylistViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val library: PlaylistRepository,
        private val video: PluginVideoProvider,
    ) : ViewModel() {
        private val playlistId: String = checkNotNull(savedStateHandle[PLAYLIST_ARG])
        private val playlist = EntityRef(EntityKind.PLAYLIST, playlistId)

        private val _source = MutableStateFlow<TvPlaylistSource?>(null)
        val source: StateFlow<TvPlaylistSource?> = _source.asStateFlow()

        private val _state = MutableStateFlow(TvRemotePlaylistState())
        val state: StateFlow<TvRemotePlaylistState> = _state.asStateFlow()

        private var firstPage: MetadataPage? = null
        private var job: Job? = null
        private var queued: List<Video> = emptyList()
        private var reading: Deferred<List<Video>>? = null

        val videos: Flow<PagingData<MetadataItem>> by lazy {
            val known = firstPage.also { firstPage = null }
            Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                PluginPagingSource(known) { cursor -> video.page(playlist, cursor = cursor) }
            }.flow.cachedIn(viewModelScope)
        }

        fun load() {
            if (job != null) return
            job =
                viewModelScope.launch {
                    val local =
                        playlistId == PlaylistRepository.WATCH_LATER_ID ||
                            playlistId == PlaylistRepository.LIKED_VIDEOS_ID ||
                            library.getPlaylistInfo(playlistId) != null
                    _source.value = if (local) TvPlaylistSource.LOCAL else TvPlaylistSource.REMOTE
                    if (local) return@launch
                    video
                        .page(playlist)
                        .onSuccess { page ->
                            firstPage = page
                            _state.value =
                                TvRemotePlaylistState(header = page.blocks.firstNotNullOfOrNull { it as? EntityHeader }, isLoading = false)
                        }.onFailure { error ->
                            Log.w(TAG, "playlist $playlistId failed", error)
                            _state.update { it.copy(isLoading = false, error = error.listenerMessage) }
                        }
                }
        }

        /**
         * Every video of the playlist in order, for Play all and Shuffle: read once, then kept. A mix
         * never ends, so the read stops after a bounded number of pages.
         */
        suspend fun queue(): List<Video> {
            if (queued.isNotEmpty()) return queued
            val read = reading?.takeIf { it.isActive } ?: viewModelScope.async { readQueue() }.also { reading = it }
            return read.await().also { queued = it }
        }

        private suspend fun readQueue(): List<Video> {
            val videos = mutableListOf<Video>()
            var cursor: String? = null
            var pages = 0
            do {
                val list =
                    video.tracks(TracksRequest(playlist, cursor)).getOrElse { error ->
                        Log.w(TAG, "tracks of $playlistId failed", error)
                        break
                    }
                videos += list.tracks.map { it.toTvVideo() }
                cursor = list.next
            } while (cursor != null && ++pages < MAX_QUEUE_PAGES)
            return videos.distinctBy { it.id }
        }

        companion object {
            const val PLAYLIST_ARG = "playlistId"
            private const val TAG = "TvPlaylist"
            private const val PAGE_SIZE = 100
            private const val MAX_QUEUE_PAGES = 10
        }
    }
