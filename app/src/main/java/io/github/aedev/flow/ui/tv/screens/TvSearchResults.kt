package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.paging.SearchResultItem
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.ui.screens.music.MusicSearchUiState
import io.github.aedev.flow.ui.screens.music.convertSongToMusicTrack
import io.github.aedev.flow.ui.tv.components.TvArtistCard
import io.github.aedev.flow.ui.tv.components.TvChannelCard
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvMusicCard
import io.github.aedev.flow.ui.tv.components.TvMusicCollectionCard
import io.github.aedev.flow.ui.tv.components.TvPlaylistCard
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.components.TvVideoCard
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.ProvideTvRowPivot
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

@Composable
internal fun TvVideoSearchResults(
    query: String,
    results: LazyPagingItems<SearchResultItem>,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTvDimens.current
    when {
        query.isBlank() -> {
            TvMessageState(
                title = stringResource(R.string.tv_search_empty),
                modifier = modifier,
            )
        }

        results.loadState.refresh is LoadState.Loading && results.itemCount == 0 -> {
            TvLoadingState(modifier = modifier)
        }

        results.loadState.refresh is LoadState.Error && results.itemCount == 0 -> {
            TvMessageState(
                title = stringResource(R.string.tv_error_loading),
                modifier = modifier,
            )
        }

        results.loadState.refresh is LoadState.NotLoading && results.itemCount == 0 -> {
            TvMessageState(
                title = stringResource(R.string.tv_search_no_results),
                modifier = modifier,
            )
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = dimens.videoCardWidth),
                modifier = modifier.tvRowFocus(),
                contentPadding = PaddingValues(bottom = dimens.overscanVertical, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
            ) {
                items(
                    count = results.itemCount,
                    key =
                        results.itemKey { item ->
                            when (item) {
                                is SearchResultItem.VideoResult -> {
                                    "video:${item.video.id}"
                                }

                                is SearchResultItem.ChannelResult -> {
                                    "channel:${item.channel.id}"
                                }

                                is SearchResultItem.PlaylistResult -> {
                                    "playlist:${item.playlist.id}"
                                }

                                is SearchResultItem.ShelfResult -> {
                                    "shelf:${item.id}"
                                }
                            }
                        },
                ) { index ->
                    when (val item = results[index]) {
                        is SearchResultItem.VideoResult -> {
                            TvVideoCard(
                                video = item.video,
                                onClick = { onVideoClick(item.video) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is SearchResultItem.ChannelResult -> {
                            TvChannelCard(
                                channel = item.channel,
                                onClick = { onChannelClick(item.channel.url.ifBlank { item.channel.id }) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is SearchResultItem.PlaylistResult -> {
                            TvPlaylistCard(
                                playlist = item.playlist,
                                onClick = { onOpenPlaylist(item.playlist.id) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is SearchResultItem.ShelfResult -> {
                            item.videos.firstOrNull()?.let { video ->
                                TvVideoCard(
                                    video = video,
                                    onClick = { onVideoClick(video) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        null -> {
                            Unit
                        }
                    }
                }

                if (results.loadState.append is LoadState.Loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TvLoadingState()
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvMusicSearchResults(
    query: String,
    state: MusicSearchUiState,
    filtered: Boolean,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onOpenMusicCollection: (String) -> Unit,
    onOpenMusicArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTvDimens.current
    val searchSource = stringResource(R.string.search_source_template, query)
    val summaries =
        state.searchSummary
            ?.summaries
            .orEmpty()
            .filter { it.items.isNotEmpty() }
    val loading = state.isSearching || state.isLoading

    when {
        query.isBlank() -> {
            TvMessageState(
                title = stringResource(R.string.tv_search_empty),
                modifier = modifier,
            )
        }

        filtered -> {
            when {
                loading && state.filteredResults.isEmpty() -> {
                    TvLoadingState(modifier = modifier)
                }

                state.filteredResults.isEmpty() -> {
                    TvMessageState(
                        title = stringResource(R.string.tv_search_no_results),
                        modifier = modifier,
                    )
                }

                else -> {
                    val songs =
                        remember(state.filteredResults) {
                            state.filteredResults.filterIsInstance<SongItem>().map(::convertSongToMusicTrack)
                        }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = dimens.musicCardWidth),
                        modifier = modifier.tvRowFocus(),
                        contentPadding = PaddingValues(bottom = dimens.overscanVertical, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                        verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                    ) {
                        gridItemsIndexed(
                            state.filteredResults,
                            key = { index, item -> "$index:${item.id}" },
                        ) { _, item ->
                            TvMusicResultCard(
                                item = item,
                                sectionSongs = songs,
                                searchSource = searchSource,
                                onPlayTrack = onPlayTrack,
                                onOpenMusicCollection = onOpenMusicCollection,
                                onOpenMusicArtist = onOpenMusicArtist,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        loading && summaries.isEmpty() -> {
            TvLoadingState(modifier = modifier)
        }

        summaries.isEmpty() -> {
            TvMessageState(
                title = stringResource(R.string.tv_search_no_results),
                modifier = modifier,
            )
        }

        else -> {
            ProvideTvColumnPivot {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding =
                        PaddingValues(
                            top = 8.dp,
                            bottom = dimens.overscanVertical,
                        ),
                ) {
                    itemsIndexed(
                        summaries,
                        key = { index, summary -> "music-section:$index:${summary.title}" },
                    ) { _, summary ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvSectionHeader(title = summary.title)
                            val sectionSongs =
                                remember(summary) {
                                    summary.items.filterIsInstance<SongItem>().map(::convertSongToMusicTrack)
                                }
                            ProvideTvRowPivot {
                                LazyRow(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .tvRowFocus(),
                                    horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                                    contentPadding = PaddingValues(vertical = 10.dp),
                                ) {
                                    itemsIndexed(
                                        summary.items,
                                        key = { itemIndex, item -> "$itemIndex:${item.id}" },
                                    ) { _, item ->
                                        TvMusicResultCard(
                                            item = item,
                                            sectionSongs = sectionSongs,
                                            searchSource = searchSource,
                                            onPlayTrack = onPlayTrack,
                                            onOpenMusicCollection = onOpenMusicCollection,
                                            onOpenMusicArtist = onOpenMusicArtist,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvMusicResultCard(
    item: YTItem,
    sectionSongs: List<MusicTrack>,
    searchSource: String,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onOpenMusicCollection: (String) -> Unit,
    onOpenMusicArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is SongItem -> {
            val track = remember(item) { convertSongToMusicTrack(item) }
            TvMusicCard(
                track = track,
                onClick = {
                    onPlayTrack(track, sectionSongs.ifEmpty { listOf(track) }, searchSource)
                },
                modifier = modifier,
            )
        }

        // Albums open the collection page via browseId — same id mobile
        // passes to its album page (AlbumItem.id == browseId).
        is AlbumItem -> {
            TvMusicCollectionCard(
                title = item.title,
                subtitle = item.artists?.joinToString { it.name },
                thumbnailUrl = item.thumbnail,
                onClick = { onOpenMusicCollection(item.id) },
                modifier = modifier,
            )
        }

        is PlaylistItem -> {
            TvMusicCollectionCard(
                title = item.title,
                subtitle = item.author?.name,
                thumbnailUrl = item.thumbnail.orEmpty(),
                onClick = { onOpenMusicCollection(item.id) },
                modifier = modifier,
            )
        }

        is ArtistItem -> {
            TvArtistCard(
                name = item.title,
                thumbnailUrl = item.thumbnail.orEmpty(),
                onClick = { onOpenMusicArtist(item.id) },
                modifier = modifier,
            )
        }
    }
}
