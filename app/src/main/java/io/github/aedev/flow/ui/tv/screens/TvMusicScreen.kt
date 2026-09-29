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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.catalog.catalogBlocks
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityRef

/** TV music home: the music provider's home page — its filters, then every block in the order it is served. */
@Composable
fun TvMusicScreen(
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onOpen: (EntityRef) -> Unit,
    onOpenPlugins: () -> Unit,
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
    val hasShelves = blocks.isNotEmpty()
    val playMix by rememberUpdatedState(onPlayMix)
    val playCollection by rememberUpdatedState(onPlayCollection)
    val open by rememberUpdatedState(onOpen)
    val actions =
        remember(viewModel) {
            TvCatalogActions(
                trackFor = viewModel::track,
                onPlayMix = { playMix(it) },
                onPlayList = { track, queue, source, radioPlaylistId -> playCollection(track, queue, source, radioPlaylistId) },
                onOpen = { open(it) },
            )
        }
    LaunchedEffect(hasShelves, state.needsPlugin) {
        if ((hasShelves || state.needsPlugin) && !openedOnContent) {
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

                        state.needsPlugin && blocks.isEmpty() -> {
                            item(key = "music-needs-plugin") {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.overscanHorizontal),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    TvMessageState(
                                        title = stringResource(R.string.tv_plugins_empty_home),
                                        message = stringResource(R.string.tv_plugins_empty_home_message),
                                    )
                                    TvButton(
                                        text = stringResource(R.string.tv_plugins_add),
                                        onClick = onOpenPlugins,
                                        modifier = Modifier.focusRequester(firstShelfFocus),
                                    )
                                }
                            }
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
                            catalogBlocks(
                                blocks = blocks,
                                actions = actions,
                                horizontalPadding = dimens.overscanHorizontal,
                                firstBlockModifier = Modifier.focusRequester(firstShelfFocus).focusGroup(),
                            )
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
