package io.github.aedev.flow.data.comments

import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.repository.YouTubeRepository
import org.schabi.newpipe.extractor.Page

/** A comment's replies and what continues them. */
internal data class CommentReplies(
    val comments: List<Comment>,
    val continuation: String? = null,
    val legacyPage: Page? = null,
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

    /** The replies to [comment], continuing from its continuation or legacy page. */
    suspend fun replies(
        videoId: String,
        comment: Comment,
    ): CommentReplies
}

/** The comment section as the YouTube repository reads it: InnerTube pages, with the extractor standing in. */
internal class YouTubeCommentsSource(
    private val repository: YouTubeRepository,
) : CommentsSource {
    override suspend fun first(
        videoId: String,
        sortToken: String?,
    ): CommentsPageResult = repository.getVideoComments(videoId, sortToken)

    override suspend fun next(
        videoId: String,
        page: CommentsPageResult,
    ): CommentsPageResult? {
        page.continuation?.let { return repository.getMoreVideoComments(videoId, it) }
        val legacyPage = page.legacyPage ?: return null
        val (comments, nextLegacy) = repository.getMoreComments(videoId, legacyPage)
        return CommentsPageResult(comments = comments, legacyPage = nextLegacy)
    }

    override suspend fun replies(
        videoId: String,
        comment: Comment,
    ): CommentReplies {
        comment.continuationToken?.let { continuation ->
            val page = repository.getVideoCommentReplies(videoId, continuation)
            return CommentReplies(page.comments, continuation = page.continuation)
        }
        val url = "https://www.youtube.com/watch?v=$videoId"
        val (items, nextPage) = repository.getCommentReplies(url, requireNotNull(comment.repliesPage))
        return CommentReplies(items, legacyPage = nextPage)
    }
}
