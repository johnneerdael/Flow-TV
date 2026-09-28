package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.music.MusicHomeFeedViewModel
import io.github.aedev.flow.ui.tv.components.TvCatalogCollection
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.MetadataItem

/** TV music home: the music provider's home page — its filters, then every block in the order it is served. */
@Composable
fun TvMusicScreen(
    onTrackClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onOpenCollection: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicHomeFeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accountExpired by viewModel.isAccountExpired.collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    LaunchedEffect(viewModel) { viewModel.load() }
    val firstShelfFocus = remember { FocusRequester() }
    // The app opens on this tab with only the rail focusable until the feed arrives; move into the
    // content once, so a later return to the tab from the rail keeps focus where the user put it.
    var openedOnContent by rememberSaveable { mutableStateOf(false) }
    val blocks = state.blocks
    val trackFor = remember(viewModel) { viewModel::track }
    val hasShelves = blocks.isNotEmpty()
    val open: (EntityRef) -> Unit = { ref ->
        when (ref.kind) {
            EntityKind.ALBUM, EntityKind.PLAYLIST -> onOpenCollection(ref.providerId)
            EntityKind.ARTIST -> onOpenArtist(ref.providerId)
            else -> Unit
        }
    }
    LaunchedEffect(hasShelves) {
        if (hasShelves && !openedOnContent) {
            withFrameNanos { }
            runCatching { firstShelfFocus.requestFocus() }
            openedOnContent = true
        }
    }

    TvScreenScaffold(
        title = null,
        modifier = modifier,
        subtitle = if (accountExpired) stringResource(R.string.tv_account_session_expired) else null,
    ) {
        // The moods stay pinned above the shelves, as in YouTube Music: in the scrolling list, pivoting a
        // shelf into place pushed them off the top.
        Column(Modifier.fillMaxSize()) {
            if (state.filters.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().tvRowFocus(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal, vertical = 8.dp),
                ) {
                    items(state.filters, key = { it.id }) { filter ->
                        TvFilterChip(
                            label = filter.label,
                            selected = filter.id == state.selectedFilterId,
                            onClick = { viewModel.selectFilter(filter) },
                        )
                    }
                }
            }
            ProvideTvColumnPivot {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = dimens.overscanVertical),
                ) {
                    when {
                        state.isLoading && blocks.isEmpty() -> {
                            item(key = "music-loading") { TvShimmerRow() }
                        }

                        state.error != null && blocks.isEmpty() -> {
                            item(key = "music-error") {
                                Box(Modifier.fillMaxWidth().padding(horizontal = dimens.overscanHorizontal)) {
                                    TvMessageState(title = stringResource(R.string.tv_error_loading), message = state.error)
                                }
                            }
                        }

                        blocks.isEmpty() && !state.isLoadingMore -> {
                            item(key = "music-empty") {
                                Box(Modifier.fillMaxWidth().padding(horizontal = dimens.overscanHorizontal)) {
                                    TvMessageState(title = stringResource(R.string.tv_music_empty))
                                }
                            }
                        }

                        else -> {
                            itemsIndexed(blocks, key = { _, block -> block.id }) { index, block ->
                                val blockModifier = if (index == 0) Modifier.focusRequester(firstShelfFocus).focusGroup() else Modifier
                                when (block) {
                                    is CollectionBlock -> {
                                        TvHomeCollection(
                                            collection = block,
                                            trackFor = trackFor,
                                            onTrackClick = onTrackClick,
                                            onPlayMix = onPlayMix,
                                            onOpen = open,
                                            modifier = blockModifier,
                                        )
                                    }
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
}

@Composable
private fun TvHomeCollection(
    collection: CollectionBlock,
    trackFor: (MetadataItem) -> MusicTrack?,
    onTrackClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onOpen: (EntityRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val queueTitle = collection.header?.title.orEmpty()
    val tracks = remember(collection.items) { collection.items.associate { it.id to trackFor(it) } }
    val queue = remember(tracks) { tracks.values.filterNotNull() }
    TvCatalogCollection(
        collection = collection,
        onItemClick = { item ->
            val track = tracks[item.id]
            if (track != null) onPlayMix(track) else onOpen(item.entity)
        },
        onPlayAll = { onTrackClick(queue.first(), queue, queueTitle) }.takeIf { queue.isNotEmpty() && queue.size == collection.items.size },
        onOpen = onOpen,
        modifier = modifier,
    )
}
