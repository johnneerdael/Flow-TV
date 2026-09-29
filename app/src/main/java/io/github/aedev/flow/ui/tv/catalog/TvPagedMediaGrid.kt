package io.github.aedev.flow.ui.tv.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.ui.tv.components.TV_GRID_COLUMNS
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvPlaylistCard
import io.github.aedev.flow.ui.tv.components.TvVideoCard
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.MetadataItem

/**
 * A video plugin's paged items as the TV's three-column video grid: videos play, playlists open.
 * The next page is read as the grid nears its end. [empty] stands in when the plugin lists nothing.
 */
@Composable
internal fun TvPagedMediaGrid(
    items: LazyPagingItems<MetadataItem>,
    onVideoClick: (Video) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    empty: @Composable () -> Unit = { TvMessageState(title = stringResource(R.string.tv_library_empty), modifier = modifier) },
) {
    val dimens = LocalTvDimens.current
    val refresh = items.loadState.refresh
    when {
        items.itemCount == 0 && refresh is LoadState.Loading -> {
            TvLoadingState(modifier)
        }

        items.itemCount == 0 && refresh is LoadState.Error -> {
            TvMessageState(
                title = stringResource(R.string.tv_error_loading),
                message = refresh.error.listenerMessage,
                modifier = modifier,
            )
        }

        items.itemCount == 0 -> {
            empty()
        }

        else -> {
            ProvideTvColumnPivot {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(TV_GRID_COLUMNS),
                    modifier = modifier.tvRowFocus(),
                    contentPadding =
                        contentPadding ?: PaddingValues(
                            start = dimens.overscanHorizontal,
                            end = dimens.overscanHorizontal,
                            bottom = dimens.overscanVertical,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                    verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                ) {
                    items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                        items[index]?.let { item -> TvPagedMediaCard(item, onVideoClick, onOpenPlaylist) }
                    }
                    if (items.loadState.append is LoadState.Loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) { TvLoadingState() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPagedMediaCard(
    item: MetadataItem,
    onVideoClick: (Video) -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    when {
        item.entity.kind.isPlayable -> {
            val video = remember(item) { item.toTvVideo() }
            TvVideoCard(
                video = video,
                onClick = { onVideoClick(video) },
                details = item.details,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item.entity.kind == EntityKind.PLAYLIST -> {
            val playlist = remember(item) { item.toTvPlaylist() }
            TvPlaylistCard(
                playlist = playlist,
                onClick = { onOpenPlaylist(playlist.id) },
                badge = item.details.lastOrNull(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
