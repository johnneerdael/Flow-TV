package io.github.aedev.flow.ui.tv.screens.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.account.AccountFeedsViewModel
import io.github.aedev.flow.ui.screens.account.AccountVideoSurface
import io.github.aedev.flow.ui.tv.components.TvMediaRow
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvMusicCard
import io.github.aedev.flow.ui.tv.components.TvPlaylistCard
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.components.TvVideoCard
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.launch

private const val ACCOUNT_GRID_COLUMNS = 3

internal enum class TvAccountLibrarySection(
    @StringRes val titleRes: Int,
) {
    YOUTUBE_HISTORY(R.string.tv_account_history),
    LIKED_MUSIC(R.string.tv_account_liked_music),
    PLAYLISTS(R.string.tv_account_playlists),
}

@Composable
internal fun TvAccountLibraryContent(
    section: TvAccountLibrarySection,
    viewModel: AccountFeedsViewModel,
    onVideoClick: (Video) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
) {
    val library by viewModel.musicLibrary.collectAsStateWithLifecycle()
    val history by viewModel.videoFeeds.getValue(AccountVideoSurface.HISTORY).collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val sectionTitle = stringResource(section.titleRes)
    val recentlyPlayedTitle = stringResource(R.string.tv_account_recently_played)
    LaunchedEffect(section) {
        viewModel.loadMusicLibrary()
        if (section == TvAccountLibrarySection.YOUTUBE_HISTORY) viewModel.loadVideoFeed(AccountVideoSurface.HISTORY)
    }

    val sectionError =
        when (section) {
            TvAccountLibrarySection.YOUTUBE_HISTORY -> history.error ?: library.historyError
            TvAccountLibrarySection.LIKED_MUSIC -> library.likedError
            TvAccountLibrarySection.PLAYLISTS -> library.playlistsError
        }
    val loading = library.isLoading || (section == TvAccountLibrarySection.YOUTUBE_HISTORY && history.isLoading)
    val empty =
        when (section) {
            TvAccountLibrarySection.YOUTUBE_HISTORY -> library.recentlyPlayed.isEmpty() && history.videos.isEmpty()
            TvAccountLibrarySection.LIKED_MUSIC -> library.likedMusic.isEmpty()
            TvAccountLibrarySection.PLAYLISTS -> library.playlists.isEmpty()
        }

    ProvideTvColumnPivot {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cardWidth =
                (maxWidth - dimens.overscanHorizontal * 2 - dimens.itemSpacing * (ACCOUNT_GRID_COLUMNS - 1)) / ACCOUNT_GRID_COLUMNS
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = dimens.overscanVertical),
            ) {
                when {
                    loading && empty -> {
                        item(key = "account-library-loading") { TvShimmerRow() }
                    }

                    empty -> {
                        item(key = "account-library-empty") {
                            TvMessageState(
                                title = stringResource(if (sectionError != null) R.string.tv_error_loading else R.string.tv_library_empty),
                                message = sectionError,
                                modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
                            )
                        }
                    }

                    section == TvAccountLibrarySection.YOUTUBE_HISTORY -> {
                        if (library.recentlyPlayed.isNotEmpty()) {
                            item(key = "account-recently-played") {
                                val tracks = library.recentlyPlayed
                                TvMediaRow(items = tracks, key = MusicTrack::videoId, title = recentlyPlayedTitle) { track ->
                                    TvMusicCard(track = track, onClick = { onPlayTrack(track, tracks, recentlyPlayedTitle) })
                                }
                            }
                        }
                        items(history.videos.chunked(ACCOUNT_GRID_COLUMNS), key = { row -> row.first().id }) { row ->
                            Row(
                                modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
                                horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                            ) {
                                row.forEach { video ->
                                    TvVideoCard(video = video, onClick = { onVideoClick(video) }, modifier = Modifier.width(cardWidth))
                                }
                            }
                        }
                    }

                    section == TvAccountLibrarySection.LIKED_MUSIC -> {
                        items(library.likedMusic.chunked(ACCOUNT_GRID_COLUMNS * 2), key = { row -> row.first().videoId }) { row ->
                            TvMediaRow(items = row, key = MusicTrack::videoId) { track ->
                                TvMusicCard(track = track, onClick = { onPlayTrack(track, library.likedMusic, sectionTitle) })
                            }
                        }
                    }

                    else -> {
                        item(key = "account-playlists") {
                            TvMediaRow(items = library.playlists, key = { it.id }, title = sectionTitle) { playlist ->
                                TvPlaylistCard(
                                    playlist = playlist,
                                    onClick = {
                                        scope.launch {
                                            val tracks = viewModel.playlistTracks(playlist.id).accountPlayable()
                                            tracks.firstOrNull()?.let { onPlayTrack(it, tracks, playlist.name) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
