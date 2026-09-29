package io.github.aedev.flow.ui.components.shared

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.pages.VideoCommentSort

enum class CommentSortFilter(
    @param:StringRes val labelRes: Int,
) {
    TOP(R.string.filter_top),
    NEWEST(R.string.filter_newest),
    OLDEST(R.string.filter_oldest),
}

/**
 * The continuation that serves [filter], out of the orders the section offered.
 *
 * The menu is matched by position rather than by title, because the titles arrive in the user's
 * language. YouTube serves two orders and no third: Oldest reads the chronological one and reverses
 * what it has, which is why it maps to the same continuation as Newest.
 */
fun videoCommentSortFor(
    options: List<VideoCommentSort>,
    filter: CommentSortFilter,
): VideoCommentSort? =
    when (filter) {
        CommentSortFilter.TOP -> options.getOrNull(0)
        CommentSortFilter.NEWEST, CommentSortFilter.OLDEST -> options.getOrNull(1)
    }
