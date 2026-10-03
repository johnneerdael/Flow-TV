package io.github.aedev.flow.ui.tv.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.github.aedev.flow.ui.tv.components.TvButton

@Composable
internal fun TvPlaylistMirrorStatus(
    state: PlaylistMirrorState,
    retry: () -> Unit,
) {
    if (!state.isPreparing && !state.ready && state.error == null) return
    Column {
        Text(
            text =
                when {
                    state.error != null -> stringResource(R.string.playlist_mirror_failed)
                    state.ready -> stringResource(R.string.playlist_mirror_ready, state.matched, state.missing)
                    state.total == 0 -> stringResource(R.string.playlist_mirror_starting)
                    else -> stringResource(R.string.playlist_mirror_progress, state.matched, state.total, state.missing)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.error != null) TvButton(text = stringResource(R.string.playlist_mirror_retry), onClick = retry)
    }
}
