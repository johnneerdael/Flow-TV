package io.github.aedev.flow.ui.tv.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.screens.account.AccountFeedsViewModel
import io.github.aedev.flow.ui.screens.account.AccountVideoSurface
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.components.TvVideoCard
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.flow.distinctUntilChanged

private const val FEED_GRID_COLUMNS = 3
private const val LOAD_MORE_ROWS_AHEAD = 2

/** Signed-in Home or Subscriptions: the account's own YouTube feed as a grid, with continuation paging. */
@Composable
fun TvAccountVideoFeedScreen(
    surface: AccountVideoSurface,
    title: String,
    viewModel: AccountFeedsViewModel,
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.videoFeeds.getValue(surface).collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    val listState = rememberLazyListState()
    val rows = state.videos.chunked(FEED_GRID_COLUMNS)
    LaunchedEffect(surface) { viewModel.loadVideoFeed(surface) }
    LaunchedEffect(listState, surface) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: -1
        }.distinctUntilChanged()
            .collect { last ->
                if (last >=
                    listState.layoutInfo.totalItemsCount - LOAD_MORE_ROWS_AHEAD
                ) {
                    viewModel.loadMoreVideoFeed(surface)
                }
            }
    }

    TvScreenScaffold(
        title = title,
        modifier = modifier,
        action = {
            TvButton(
                text = stringResource(R.string.action_refresh),
                onClick = { viewModel.loadVideoFeed(surface, force = true) },
                icon = Icons.Outlined.Refresh,
            )
        },
    ) {
        ProvideTvColumnPivot {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val cardWidth =
                    (maxWidth - dimens.overscanHorizontal * 2 - dimens.itemSpacing * (FEED_GRID_COLUMNS - 1)) / FEED_GRID_COLUMNS
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = dimens.overscanVertical),
                ) {
                    when {
                        state.isLoading && rows.isEmpty() -> {
                            item(key = "account-feed-loading") { TvShimmerRow() }
                        }

                        rows.isEmpty() -> {
                            item(key = "account-feed-empty") {
                                TvMessageState(
                                    title =
                                        stringResource(
                                            if (state.error !=
                                                null
                                            ) {
                                                R.string.tv_error_loading
                                            } else {
                                                R.string.tv_library_empty
                                            },
                                        ),
                                    message = state.error,
                                    modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
                                )
                            }
                        }

                        else -> {
                            items(rows, key = { row -> row.first().id }) { row ->
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
                    }
                }
            }
        }
    }
}
