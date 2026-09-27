package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

private val CoverSize = 104.dp
private val CoverFrameWidth = 1.5.dp
private const val TEXT_MAX_WIDTH_FRACTION = 0.6f

/**
 * The always-visible track identity for the now-playing screen: framed cover art with the artist
 * and, below it, the title — kept to one corner so a full-screen visual can own the rest.
 */
@Composable
internal fun TvNowPlayingTrackCorner(
    artist: String,
    title: String,
    artworkUrl: String?,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(TEXT_MAX_WIDTH_FRACTION),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(CoverFrameWidth, contentColor.copy(alpha = 0.8f)),
        ) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = title,
                modifier = Modifier.size(CoverSize),
                contentScale = ContentScale.Crop,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = artist,
                style = MaterialTheme.typography.headlineLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = contentColor.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
