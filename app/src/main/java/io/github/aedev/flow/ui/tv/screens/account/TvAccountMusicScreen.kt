package io.github.aedev.flow.ui.tv.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.account.AccountFeedsViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvMediaRow
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvMusicCard
import io.github.aedev.flow.ui.tv.components.TvMusicCollectionCard
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/** Signed-in Music tab: the account's own YouTube Music home, section by section. */
@Composable
fun TvAccountMusicScreen(
    viewModel: AccountFeedsViewModel,
    onTrackClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onOpenCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.music.collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    LaunchedEffect(viewModel) { viewModel.loadMusicHome() }
    val shelves =
        remember(state.sections) {
            state.sections
                .map { it.title to it.tracks.accountBrowsable() }
                .filter { it.second.isNotEmpty() }
        }

    TvScreenScaffold(
        title = stringResource(R.string.screen_title_music),
        modifier = modifier,
        action = {
            TvButton(
                text = stringResource(R.string.action_refresh),
                onClick = { viewModel.loadMusicHome(force = true) },
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
                when {
                    state.isLoading && shelves.isEmpty() -> {
                        item(key = "account-music-loading") { TvShimmerRow() }
                    }

                    state.error != null && shelves.isEmpty() -> {
                        item(key = "account-music-error") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = dimens.overscanHorizontal)) {
                                TvMessageState(title = stringResource(R.string.tv_error_loading), message = state.error)
                            }
                        }
                    }

                    shelves.isEmpty() -> {
                        item(key = "account-music-empty") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = dimens.overscanHorizontal)) {
                                TvMessageState(title = stringResource(R.string.tv_music_empty))
                            }
                        }
                    }

                    else -> {
                        shelves.forEachIndexed { index, (title, tracks) ->
                            item(key = "account-music-$index") {
                                val songs = tracks.accountPlayable()
                                TvMediaRow(items = tracks, key = MusicTrack::videoId, title = title) { item ->
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
                        }
                    }
                }
            }
        }
    }
}

internal fun List<MusicTrack>.accountPlayable(): List<MusicTrack> =
    asSequence()
        .filter { it.itemType == MusicItemType.SONG && it.videoId.isNotBlank() }
        .distinctBy(MusicTrack::videoId)
        .toList()

/** Songs to play plus albums and playlists to open; a new account's home is mostly the latter. */
internal fun List<MusicTrack>.accountBrowsable(): List<MusicTrack> =
    asSequence()
        .filter { it.itemType != MusicItemType.ARTIST && it.videoId.isNotBlank() }
        .distinctBy(MusicTrack::videoId)
        .toList()
