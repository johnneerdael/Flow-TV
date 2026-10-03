package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.ui.tv.components.TvToggleRow

@Composable
internal fun TvPlaylistMirrorOption(
    target: InstalledPlugin,
    enabled: Boolean,
    available: Boolean,
    onEnabled: (Boolean) -> Unit,
) {
    TvToggleRow(
        label = stringResource(R.string.playlist_mirror_option, target.manifest.name),
        checked = enabled,
        onCheckedChange = { if (available || !it) onEnabled(it) },
        supportingText = stringResource(if (available) R.string.playlist_mirror_description else R.string.playlist_mirror_sign_in),
    )
}
