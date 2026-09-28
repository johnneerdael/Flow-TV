package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SearchHistoryItem
import io.github.aedev.flow.ui.tv.components.TvIconButton
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

private val ForgetButtonSize = 48.dp

/**
 * What the results area shows before anything is typed: the recent searches, each opened with
 * center and removed with the button to its right, and a row that clears them all.
 */
@Composable
internal fun TvRecentSearches(
    history: List<SearchHistoryItem>,
    onPick: (String) -> Unit,
    onForget: (SearchHistoryItem) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTvDimens.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = dimens.overscanVertical),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            TvSectionHeader(title = stringResource(R.string.recent_searches))
        }
        items(history, key = { it.id }) { item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvNavRow(
                    label = item.query,
                    onClick = { onPick(item.query) },
                    leadingIcon = Icons.Outlined.History,
                    modifier = Modifier.weight(1f),
                )
                TvIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.remove_from_history),
                    onClick = { onForget(item) },
                    size = ForgetButtonSize,
                )
            }
        }
        item(key = "clear") {
            TvNavRow(
                label = stringResource(R.string.clear_search_history),
                onClick = onClear,
                leadingIcon = Icons.Outlined.DeleteSweep,
            )
        }
    }
}
