package io.github.aedev.flow.ui.tv.screens.account

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.catalog.TvPagedMediaGrid
import io.github.aedev.flow.ui.tv.catalog.catalogBlocks
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityRef

/** What the account's library sections do when an item is picked; the screen owns playback and navigation. */
internal class TvAccountLibraryCallbacks(
    val onVideoClick: (Video) -> Unit,
    val onOpenPlaylist: (String) -> Unit,
    val onPlayMix: (MusicTrack) -> Unit,
    val onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    val onOpenCatalog: (EntityRef) -> Unit,
)

/** One section of the account's library: the watch history as a paged video grid, the rest as catalog pages. */
@Composable
internal fun TvAccountLibraryContent(
    section: TvAccountLibrarySection,
    viewModel: TvAccountLibraryViewModel,
    callbacks: TvAccountLibraryCallbacks,
) {
    val dimens = LocalTvDimens.current
    if (section.isVideoGrid) {
        TvPagedMediaGrid(
            items = viewModel.watchHistory.collectAsLazyPagingItems(),
            onVideoClick = callbacks.onVideoClick,
            onOpenPlaylist = callbacks.onOpenPlaylist,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LaunchedEffect(section) { viewModel.open(section) }
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val state = sections[section]
    val current by rememberUpdatedState(callbacks)
    val actions =
        remember(viewModel) {
            TvCatalogActions(
                trackFor = viewModel::track,
                onPlayMix = { current.onPlayMix(it) },
                onPlayList = { track, queue, source, radio -> current.onPlayCollection(track, queue, source, radio) },
                onOpen = { current.onOpenCatalog(it) },
            )
        }
    when {
        state == null || (state.isLoading && state.blocks.isEmpty()) -> {
            TvShimmerRow()
        }

        state.blocks.isEmpty() -> {
            TvMessageState(
                title = stringResource(if (state.error != null) R.string.tv_error_loading else R.string.tv_library_empty),
                message = state.error,
                modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
            )
        }

        else -> {
            ProvideTvColumnPivot {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = dimens.overscanVertical),
                ) {
                    catalogBlocks(state.blocks, actions, horizontalPadding = dimens.overscanHorizontal)
                }
            }
        }
    }
}
