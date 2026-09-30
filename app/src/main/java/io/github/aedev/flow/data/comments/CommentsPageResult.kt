package io.github.aedev.flow.data.comments

import io.github.aedev.flow.data.model.Comment

/** One order a video's comment section offers, and whether it is the one showing. */
data class VideoCommentSort(
    val title: String,
    val token: String,
    val selected: Boolean,
)

/** One page of comments, the [continuation] that fetches the next and the orders the section offers. */
data class CommentsPageResult(
    val comments: List<Comment> = emptyList(),
    val continuation: String? = null,
    val sortOptions: List<VideoCommentSort> = emptyList(),
    val totalText: String? = null,
    val totalCount: Long? = null,
) {
    val hasMore: Boolean get() = continuation != null

    companion object {
        val EMPTY = CommentsPageResult()
    }
}
