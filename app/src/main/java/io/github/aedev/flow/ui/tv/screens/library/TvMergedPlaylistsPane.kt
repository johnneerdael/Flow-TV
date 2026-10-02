package io.github.aedev.flow.ui.tv.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.ProviderLibraryItem
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvMusicCollectionCard
import io.github.aedev.flow.ui.tv.components.TvMusicTrackRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvAcceleratedDpad
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityRef

@Composable
internal fun TvMergedPlaylistsPane(
    onOpenPlaylist: (String) -> Unit,
    onOpenMusicCollection: (String) -> Unit,
    onOpenProviderCatalog: (String, EntityRef) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
    initiallyLiked: Boolean = false,
    viewModel: TvMergedLibraryViewModel = hiltViewModel(),
) {
    val input = LocalInputModeManager.current
    LaunchedEffect(viewModel) { input.requestInputMode(InputMode.Keyboard) }
    val installed by viewModel.installedIdentity.collectAsStateWithLifecycle()
    LaunchedEffect(installed) { viewModel.open() }
    var liked by rememberSaveable(initiallyLiked) { mutableStateOf(initiallyLiked) }
    BackHandler(liked) { liked = false }
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val localLiked by viewModel.localLikedSongs.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        errors.keys.forEach { provider ->
            Text(
                stringResource(R.string.library_provider_unavailable, provider),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (errors.isNotEmpty()) TvButton(stringResource(R.string.music_folders_refresh), { viewModel.open(force = true) })
        if (liked) {
            TvMergedLikedSongs(localLiked, viewModel.providerLikedSongs.collectAsLazyPagingItems(), { liked = false }, onPlayTrack)
        } else {
            val video by viewModel.localPlaylists.collectAsStateWithLifecycle(emptyList())
            val music by viewModel.localMusicPlaylists.collectAsStateWithLifecycle(emptyList())
            TvMergedPlaylistGrid(
                video.filterNot { it.id in SPECIAL_PLAYLISTS },
                music.filterNot { it.id in SPECIAL_PLAYLISTS },
                viewModel.providerPlaylists.collectAsLazyPagingItems(),
                { liked = true },
                onOpenPlaylist,
                onOpenMusicCollection,
                onOpenProviderCatalog,
            )
        }
    }
}

@Composable
internal fun TvMergedPlaylistGrid(
    video: List<PlaylistInfo>,
    music: List<PlaylistInfo>,
    providers: LazyPagingItems<ProviderLibraryItem>,
    onLikedSongs: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenMusicCollection: (String) -> Unit,
    onOpenProviderCatalog: (String, EntityRef) -> Unit,
) {
    val dimens = LocalTvDimens.current
    val appName = stringResource(R.string.app_name)
    ProvideTvColumnPivot {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier =
                Modifier
                    .fillMaxSize()
                    .tvInitialFocus("merged-playlists")
                    .focusGroup()
                    .tvAcceleratedDpad(),
            contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
            horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
        ) {
            item(key = "liked-songs") {
                TvMusicCollectionCard(
                    stringResource(R.string.library_liked_songs),
                    stringResource(R.string.library_all_providers),
                    "",
                    onLikedSongs,
                    Modifier.fillMaxWidth(),
                )
            }
            items(music.size, key = { "app-music:${music[it].id}" }) { index ->
                val info = music[index]
                TvMusicCollectionCard(
                    info.name,
                    appName,
                    info.thumbnailUrl.orEmpty(),
                    { onOpenMusicCollection(info.id) },
                    Modifier.fillMaxWidth(),
                )
            }
            items(video.size, key = { "app-video:${video[it].id}" }) { index ->
                val info = video[index]
                TvMusicCollectionCard(info.name, appName, info.thumbnailUrl.orEmpty(), { onOpenPlaylist(info.id) }, Modifier.fillMaxWidth())
            }
            items(providers.itemCount, key = providers.itemKey { it.key }) { index ->
                providers[index]?.let { scoped ->
                    val item = scoped.item
                    TvMusicCollectionCard(
                        item.title,
                        scoped.provider.name,
                        item.artwork?.url.orEmpty(),
                        { onOpenProviderCatalog(scoped.provider.id, item.entity) },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            if (providers.loadState.refresh is LoadState.Loading || providers.loadState.append is LoadState.Loading) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { TvLoadingState() }
            }
            if (providers.loadState.refresh is LoadState.Error || providers.loadState.append is LoadState.Error) {
                item(key = "retry", span = { GridItemSpan(maxLineSpan) }) { TvButton(stringResource(R.string.retry), providers::retry) }
            }
        }
    }
}

@Composable
private fun TvMergedLikedSongs(
    local: List<MusicTrack>,
    providers: LazyPagingItems<ProviderLibraryItem>,
    onBack: () -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
) {
    val dimens = LocalTvDimens.current
    val title = stringResource(R.string.library_liked_songs)
    val snapshot = providers.itemSnapshotList
    val queue = remember(local, snapshot) { mergedLikedQueue(local, snapshot.items) }
    ProvideTvColumnPivot {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .tvInitialFocus("merged-liked-songs")
                    .focusGroup()
                    .tvAcceleratedDpad(),
            contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
            verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
        ) {
            item(key = "back") { TvButton(stringResource(R.string.music_folders_editor_back), onBack) }
            items(local, key = { "app:${it.videoId}" }) { track ->
                TvMusicTrackRow(track, {
                    val selected = selectedLikedTrack(track, queue)
                    onPlayTrack(selected, queue, title)
                })
            }
            items(providers.itemCount, key = providers.itemKey { it.key }) { index ->
                providers[index]?.let { item ->
                    item.playableTrack?.let { track ->
                        TvMusicTrackRow(track, {
                            val selected = selectedLikedTrack(track, queue)
                            onPlayTrack(selected, queue, title)
                        })
                    }
                }
            }
            if (providers.loadState.refresh is LoadState.Loading || providers.loadState.append is LoadState.Loading) {
                item(key = "loading") { TvLoadingState() }
            } else if (providers.loadState.refresh is LoadState.Error || providers.loadState.append is LoadState.Error) {
                item(key = "retry") { TvButton(stringResource(R.string.retry), providers::retry) }
            } else if (queue.isEmpty()) {
                item(key = "empty") { TvMessageState(stringResource(R.string.tv_library_empty)) }
            }
        }
    }
}

private val SPECIAL_PLAYLISTS =
    setOf(
        PlaylistRepository.WATCH_LATER_ID,
        PlaylistRepository.SAVED_SHORTS_ID,
        PlaylistRepository.LIKED_VIDEOS_ID,
        PlaylistRepository.LIKED_MUSIC_ID,
    )
