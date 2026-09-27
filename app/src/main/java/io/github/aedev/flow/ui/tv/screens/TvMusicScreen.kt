package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.music.MusicHomeFeedViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvMediaRow
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvMusicCard
import io.github.aedev.flow.ui.tv.components.TvMusicCollectionCard
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/** TV music home: YouTube Music's home feed — mood chips, then every shelf in the order it is served. */
@Composable
fun TvMusicScreen(
    onTrackClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onOpenCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicHomeFeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accountExpired by viewModel.isAccountExpired.collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    LaunchedEffect(viewModel) { viewModel.load() }
    val shelves =
        remember(state.sections) {
            state.sections
                .map { it.title to it.tracks.browsable() }
                .filter { (_, items) -> items.isNotEmpty() }
        }

    TvScreenScaffold(
        title = stringResource(R.string.screen_title_music),
        modifier = modifier,
        subtitle = if (accountExpired) stringResource(R.string.tv_account_session_expired) else null,
        action = {
            TvButton(
                text = stringResource(R.string.action_refresh),
                onClick = { viewModel.load(force = true) },
                icon = Icons.Outlined.Refresh,
            )
        },
    ) {
        ProvideTvColumnPivot {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = dimens.overscanVertical),
            ) {
                if (state.chips.isNotEmpty()) {
                    item(key = "music-chips") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().tvRowFocus(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal, vertical = 4.dp),
                        ) {
                            items(state.chips, key = { it.title }) { chip ->
                                TvFilterChip(
                                    label = chip.title,
                                    selected = state.isSelected(chip),
                                    onClick = { viewModel.selectChip(chip) },
                                )
                            }
                        }
                    }
                }
                when {
                    state.isLoading && shelves.isEmpty() -> {
                        item(key = "music-loading") { TvShimmerRow() }
                    }

                    state.error != null && shelves.isEmpty() -> {
                        item(key = "music-error") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = dimens.overscanHorizontal)) {
                                TvMessageState(title = stringResource(R.string.tv_error_loading), message = state.error)
                            }
                        }
                    }

                    shelves.isEmpty() && !state.isLoadingMore -> {
                        item(key = "music-empty") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = dimens.overscanHorizontal)) {
                                TvMessageState(title = stringResource(R.string.tv_music_empty))
                            }
                        }
                    }

                    else -> {
                        shelves.forEachIndexed { index, (title, items) ->
                            item(key = "music-shelf-$index") {
                                TvMusicHomeShelf(
                                    title = title,
                                    items = items,
                                    onTrackClick = onTrackClick,
                                    onOpenCollection = onOpenCollection,
                                )
                            }
                        }
                        if (state.isLoadingMore) {
                            item(key = "music-loading-more") { TvShimmerRow() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvMusicHomeShelf(
    title: String,
    items: List<MusicTrack>,
    onTrackClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onOpenCollection: (String) -> Unit,
) {
    val songs = remember(items) { items.filter { it.itemType == MusicItemType.SONG } }
    TvMediaRow(items = items, key = MusicTrack::videoId, title = title) { item ->
        if (item.itemType == MusicItemType.SONG) {
            TvMusicCard(track = item, onClick = { onTrackClick(item, songs, title) })
        } else {
            TvMusicCollectionCard(
                title = item.title,
                subtitle = item.artist.ifBlank { null },
                thumbnailUrl = item.thumbnailUrl,
                onClick = { onOpenCollection(item.videoId) },
            )
        }
    }
}

/** Songs and videos to play plus albums and playlists to open; artists have no card on a home shelf. */
private fun List<MusicTrack>.browsable(): List<MusicTrack> =
    asSequence()
        .filter { it.itemType != MusicItemType.ARTIST && it.videoId.isNotBlank() }
        .distinctBy(MusicTrack::videoId)
        .toList()
