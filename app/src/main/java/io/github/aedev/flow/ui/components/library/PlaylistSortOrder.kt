package io.github.aedev.flow.ui.components.library

import androidx.annotation.StringRes
import io.github.aedev.flow.R

enum class PlaylistSortOrder(
    val storageValue: String,
    @param:StringRes val labelRes: Int,
) {
    MANUAL("manual", R.string.playlist_sort_manual),
    DATE_ADDED_NEWEST("date_added_newest", R.string.playlist_sort_date_added_newest),
    DATE_ADDED_OLDEST("date_added_oldest", R.string.playlist_sort_date_added_oldest),
    MOST_POPULAR("most_popular", R.string.playlist_sort_most_popular),
    DATE_PUBLISHED_NEWEST("date_published_newest", R.string.playlist_sort_date_published_newest),
    DATE_PUBLISHED_OLDEST("date_published_oldest", R.string.playlist_sort_date_published_oldest),
    ;

    companion object {
        fun fromStorageValue(value: String?): PlaylistSortOrder = entries.firstOrNull { it.storageValue == value } ?: MANUAL

        /**
         * The orders a playlist has data for: a YouTube playlist carries no date a video was added,
         * and likes have no order of their own besides when each was liked.
         */
        fun availableFor(
            isLocalPlaylist: Boolean,
            isLikes: Boolean = false,
        ): List<PlaylistSortOrder> =
            when {
                isLikes -> entries.filterNot { it == MANUAL }
                isLocalPlaylist -> entries
                else -> entries.filterNot { it == DATE_ADDED_NEWEST || it == DATE_ADDED_OLDEST }
            }

        fun defaultFor(isLikes: Boolean): PlaylistSortOrder = if (isLikes) DATE_ADDED_NEWEST else MANUAL
    }
}
