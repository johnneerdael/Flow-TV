package io.github.aedev.flow.data.comments

import io.github.aedev.flow.data.model.Comment

/** A comment's replies and what continues them. */
internal data class CommentReplies(
    val comments: List<Comment>,
    val continuation: String? = null,
)

/** Where a video's comment section comes from: its first page in an order, the pages after it and replies. */
internal interface CommentsSource {
    suspend fun first(
        videoId: String,
        sortToken: String?,
    ): CommentsPageResult

    /** The page after [page], or null when it has none. */
    suspend fun next(
        videoId: String,
        page: CommentsPageResult,
    ): CommentsPageResult?

    /** The replies to [comment], continuing from its continuation. */
    suspend fun replies(
        videoId: String,
        comment: Comment,
    ): CommentReplies
}
