package io.github.aedev.flow.ui.screens.player.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.shared.videoCommentSortFor
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel

/**
 * Everything the three comment surfaces read: the loaded list, its paging flags, the section's own
 * total, and the orders it offers.
 *
 * Collected once in the host so the bottom sheet, the fullscreen drawer and the supporting pane all
 * show the same page rather than each collecting their own.
 */
@Immutable
data class PlayerCommentsUiState(
    val comments: List<Comment> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val totalText: String? = null,
    val sortOptions: List<VideoCommentSort> = emptyList(),
)

/**
 * Applies a chip.
 *
 * Top and Newest are separate continuations, so picking one re-requests the section; Oldest reads
 * the chronological continuation and reverses it locally, which is why it selects the same one as
 * Newest.
 */
fun PlayerCommentsUiState.selectCommentSort(
    filter: CommentSortFilter,
    videoId: String,
    screenState: PlayerScreenState,
    viewModel: VideoPlayerViewModel,
) {
    screenState.commentSortFilter = filter
    videoCommentSortFor(sortOptions, filter)?.let { sort ->
        viewModel.selectCommentSort(videoId, sort)
    }
}
