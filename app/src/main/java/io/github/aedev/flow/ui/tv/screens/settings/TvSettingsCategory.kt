package io.github.aedev.flow.ui.tv.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.aedev.flow.R

/** Categories of the two-pane TV settings surface. Pure model — unit-testable. */
enum class TvSettingsCategory(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    ACCOUNT(R.string.tv_settings_account, Icons.Outlined.AccountCircle),
    PLAYBACK(R.string.playback_header, Icons.Outlined.PlayCircleOutline),
    VISUALIZATIONS(R.string.tv_settings_visualizations, Icons.Outlined.GraphicEq),
    QUALITY(R.string.quality, Icons.Outlined.Tune),
    CONTENT(R.string.settings_header_content_playback, Icons.Outlined.Shield),
    FLOW_ENGINE(R.string.tv_settings_flow_engine, Icons.Outlined.Psychology),
    ABOUT(R.string.about, Icons.Outlined.Info),
}
