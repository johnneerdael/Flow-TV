package io.github.aedev.flow.ui.tv.screens.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.tv.catalog.TvPagedMediaGrid
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.launch

/** A video plugin's playlist: its header's title and details, Play all and Shuffle, and its paged videos. */
@Composable
internal fun TvRemotePlaylistContent(
    viewModel: TvPlaylistViewModel,
    onVideoClick: (Video) -> Unit,
    onPlayPlaylist: (List<Video>, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val header = state.header
    val title = header?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tv_library_playlists)
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()

    fun play(shuffle: Boolean) {
        scope.launch {
            val videos = viewModel.queue()
            if (videos.isNotEmpty()) onPlayPlaylist(if (shuffle) videos.shuffled() else videos, title)
        }
    }

    TvScreenScaffold(
        title = title,
        modifier = modifier,
        subtitle = header?.details?.takeIf { it.isNotEmpty() }?.joinToString(separator = " • "),
    ) {
        when {
            state.isLoading -> {
                TvLoadingState()
            }

            state.error != null -> {
                TvMessageState(title = stringResource(R.string.tv_error_loading), message = state.error)
            }

            else -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (header?.tracks != null) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = dimens.overscanHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TvButton(
                                text = stringResource(R.string.play_all),
                                onClick = { play(shuffle = false) },
                                icon = Icons.Outlined.PlayArrow,
                                modifier = Modifier.tvInitialFocus(),
                            )
                            TvButton(
                                text = stringResource(R.string.shuffle),
                                onClick = { play(shuffle = true) },
                                icon = Icons.Outlined.Shuffle,
                            )
                        }
                    }
                    TvPagedMediaGrid(
                        items = viewModel.videos.collectAsLazyPagingItems(),
                        onVideoClick = onVideoClick,
                        onOpenPlaylist = {},
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                start = dimens.overscanHorizontal,
                                end = dimens.overscanHorizontal,
                                top = 12.dp,
                                bottom = dimens.overscanVertical,
                            ),
                    )
                }
            }
        }
    }
}
