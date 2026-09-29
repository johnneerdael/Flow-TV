package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.data.comments.CommentReplies
import io.github.aedev.flow.data.comments.CommentsPageResult
import io.github.aedev.flow.data.comments.CommentsSource
import io.github.aedev.flow.data.model.Comment
import nl.neerdael.milkbeat.catalog.CommentsRequest

/** A video's comment section from the video plugin: pages and replies continue by the plugin's cursors. */
internal class PluginCommentsSource(
    private val provider: PluginVideo,
) : CommentsSource {
    override suspend fun first(
        videoId: String,
        sortToken: String?,
    ): CommentsPageResult =
        provider
            .comments(CommentsRequest(videoRef(videoId), sortId = sortToken))
            .map { PluginVideoPages.comments(it, sortToken) }
            .getOrThrow()

    override suspend fun next(
        videoId: String,
        page: CommentsPageResult,
    ): CommentsPageResult? {
        val cursor = page.continuation ?: return null
        return provider
            .comments(CommentsRequest(videoRef(videoId), cursor = cursor))
            .map { PluginVideoPages.comments(it, sortId = null).copy(sortOptions = page.sortOptions) }
            .getOrThrow()
    }

    override suspend fun replies(
        videoId: String,
        comment: Comment,
    ): CommentReplies {
        val cursor = comment.continuationToken ?: return CommentReplies(emptyList())
        val page =
            provider
                .comments(CommentsRequest(videoRef(videoId), cursor = cursor))
                .map { PluginVideoPages.comments(it, sortId = null) }
                .getOrThrow()
        return CommentReplies(page.comments, continuation = page.continuation)
    }
}
