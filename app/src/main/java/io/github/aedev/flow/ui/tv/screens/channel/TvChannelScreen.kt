package io.github.aedev.flow.ui.tv.screens.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.tv.catalog.TvPagedMediaGrid
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.FilterOption

/** A video plugin's channel: header, subscribe, its tabs as chips, and each tab's paged grid. */
@Composable
fun TvChannelScreen(
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaylist: (String) -> Unit = {},
    viewModel: TvChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val subscribed by viewModel.isSubscribed.collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    LaunchedEffect(viewModel) { viewModel.load() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(top = dimens.overscanVertical),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvChannelHeader(header = state.header, subscribed = subscribed, onToggleSubscription = viewModel::toggleSubscription)

        when {
            state.isLoading && state.header == null -> {
                TvLoadingState(Modifier.weight(1f))
            }

            state.error != null && state.header == null -> {
                TvMessageState(
                    title = stringResource(R.string.tv_error_loading),
                    message = state.error,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                val selected = state.selectedFilter
                TvChannelTabRow(filters = state.filters, selected = selected, onSelect = viewModel::selectFilter)
                val tabHeaders by viewModel.tabHeaders.collectAsStateWithLifecycle()
                val about = tabHeaders[viewModel.requestFilter(selected?.id)] ?: state.header
                val items = remember(selected?.id) { viewModel.items(selected?.id) }.collectAsLazyPagingItems()
                TvPagedMediaGrid(
                    items = items,
                    onVideoClick = onVideoClick,
                    onOpenPlaylist = onOpenPlaylist,
                    modifier = Modifier.weight(1f),
                    empty = { TvChannelAbout(about, Modifier.weight(1f)) },
                )
            }
        }
    }
}

@Composable
private fun TvChannelHeader(
    header: EntityHeader?,
    subscribed: Boolean,
    onToggleSubscription: () -> Unit,
) {
    val dimens = LocalTvDimens.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.overscanHorizontal),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = header?.artwork?.url,
            contentDescription = null,
            modifier =
                Modifier
                    .size(88.dp)
                    .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = header?.title.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            header?.details?.takeIf { it.isNotEmpty() }?.let { details ->
                Text(
                    text = details.joinToString(separator = " • "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TvButton(
            text = stringResource(if (subscribed) R.string.subscribed else R.string.subscribe),
            onClick = onToggleSubscription,
        )
    }
}

@Composable
private fun TvChannelTabRow(
    filters: List<FilterOption>,
    selected: FilterOption?,
    onSelect: (String) -> Unit,
) {
    val dimens = LocalTvDimens.current
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .tvRowFocus(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
    ) {
        itemsIndexed(filters, key = { _, filter -> filter.id }) { index, filter ->
            TvFilterChip(
                label = filter.label,
                selected = filter == selected,
                onClick = { onSelect(filter.id) },
                modifier = if (index == 0) Modifier.tvInitialFocus() else Modifier,
            )
        }
    }
}

/** A tab that lists nothing, such as About, shows what the channel says about itself. */
@Composable
private fun TvChannelAbout(
    header: EntityHeader?,
    modifier: Modifier,
) {
    val dimens = LocalTvDimens.current
    val description = header?.description
    if (description.isNullOrBlank()) {
        TvMessageState(title = stringResource(R.string.tv_library_empty), modifier = modifier)
        return
    }
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimens.overscanHorizontal),
    ) {
        Text(text = description, style = MaterialTheme.typography.bodyLarge)
    }
}
