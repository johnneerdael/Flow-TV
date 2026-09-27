package io.github.aedev.flow.ui.tv.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.aedev.flow.R

/** Stable top-level destinations for Flow's TV interface. */
enum class TvDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    MUSIC("music", R.string.nav_music, Icons.Outlined.MusicNote),
    SEARCH("search", R.string.search, Icons.Outlined.Search),
    LIBRARY("library", R.string.library, Icons.Outlined.VideoLibrary),
    SETTINGS("settings", R.string.settings, Icons.Outlined.Settings),
    ;

    companion object {
        val primary: List<TvDestination> = entries.toList()

        /** The tab the app opens on and Back converges to. */
        val start: TvDestination = MUSIC

        fun fromRoute(route: String?): TvDestination = entries.firstOrNull { it.route == route } ?: start
    }
}
