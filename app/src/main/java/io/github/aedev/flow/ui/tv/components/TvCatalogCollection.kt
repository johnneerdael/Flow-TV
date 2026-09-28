package io.github.aedev.flow.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.tv.focus.ProvideTvRowPivot
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem

/**
 * One catalog collection on TV: its header (context line, avatar, Play all) above either a shelf
 * of cards in the shape each item asks for, or compact track rows read down each column.
 */
@Composable
fun TvCatalogCollection(
    collection: CollectionBlock,
    onItemClick: (MetadataItem) -> Unit,
    onPlayAll: (() -> Unit)?,
    onOpen: (EntityRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTvDimens.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        collection.header?.let { header ->
            TvCatalogHeader(
                header = header,
                onPlayAll = onPlayAll,
                onOpen = header.target?.takeIf { it.kind.isBrowsable }?.let { target -> { onOpen(target) } },
                modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
            )
        }
        when (collection.layout) {
            CollectionLayout.HORIZONTAL_SHELF -> TvCatalogShelf(collection, onItemClick)
            CollectionLayout.MULTI_COLUMN_LIST -> TvCatalogTrackColumns(collection, onItemClick)
        }
    }
}

@Composable
private fun TvCatalogHeader(
    header: CollectionHeader,
    onPlayAll: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTvDimens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        header.avatar?.let { avatar ->
            Surface(
                shape = if (header.target?.kind?.isPerson == true) CircleShape else MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                AsyncImage(
                    model = avatar.url,
                    contentDescription = null,
                    modifier = Modifier.size(dimens.collectionAvatarSize),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            header.context?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvSectionHeader(title = header.title)
        }
        onOpen?.let {
            TvIconButton(
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.tv_catalog_open, header.title),
                onClick = it,
            )
        }
        onPlayAll?.let {
            TvButton(
                text = stringResource(R.string.play_all),
                onClick = it,
                icon = Icons.Outlined.PlayArrow,
            )
        }
    }
}

@Composable
private fun TvCatalogShelf(
    collection: CollectionBlock,
    onItemClick: (MetadataItem) -> Unit,
) {
    TvMediaRow(items = collection.items, key = MetadataItem::id) { item ->
        val artwork = item.artwork?.url.orEmpty()
        when (item.view ?: collection.defaultItemView) {
            ItemView.ARTIST_PORTRAIT -> {
                TvArtistCard(name = item.title, thumbnailUrl = artwork, onClick = { onItemClick(item) })
            }

            ItemView.LANDSCAPE_CARD -> {
                TvMusicLandscapeCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = artwork,
                    onClick = { onItemClick(item) },
                )
            }

            ItemView.COVER_CARD, ItemView.TRACK_ROW -> {
                TvMusicCollectionCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = artwork,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

@Composable
private fun TvCatalogTrackColumns(
    collection: CollectionBlock,
    onItemClick: (MetadataItem) -> Unit,
) {
    val dimens = LocalTvDimens.current
    val rows = minOf(TRACK_ROWS, collection.items.size)
    ProvideTvRowPivot {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(dimens.trackRowHeight * rows)
                    .tvRowFocus(),
            contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
            horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
        ) {
            items(collection.items, key = MetadataItem::id) { item ->
                TvMusicTrackRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.artwork?.url.orEmpty(),
                    durationSeconds = item.durationSeconds ?: 0,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.width(dimens.trackColumnWidth),
                )
            }
        }
    }
}

/** What the TV app has a page for; a listener's profile has none yet. */
private val EntityKind.isBrowsable: Boolean
    get() = this == EntityKind.ARTIST || this == EntityKind.ALBUM || this == EntityKind.PLAYLIST

private const val TRACK_ROWS = 4
