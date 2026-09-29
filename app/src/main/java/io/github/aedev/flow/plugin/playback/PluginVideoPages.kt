package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.data.comments.CommentsPageResult
import io.github.aedev.flow.data.model.LiveChatMessageType
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CommentsPage
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.LiveChatMessage
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import io.github.aedev.flow.data.model.Comment as AppComment
import io.github.aedev.flow.data.model.LiveChatMessage as AppLiveChatMessage
import nl.neerdael.milkbeat.catalog.Comment as PluginComment

/** The video plugin's related page, comments and live chat in the player's own models. Pure. */
internal object PluginVideoPages {
    /** The videos of a related page, without [videoId] itself and without repeats; its header is skipped. */
    fun relatedVideos(
        page: MetadataPage,
        videoId: String,
    ): List<Video> =
        page.blocks
            .filterIsInstance<CollectionBlock>()
            .flatMap { it.items }
            .mapNotNull { it.toVideo() }
            .filter { it.id != videoId }
            .distinctBy { it.id }

    fun MetadataItem.toVideo(): Video? {
        if (entity.kind != EntityKind.VIDEO) return null
        val channel = artists.firstOrNull()
        return Video(
            id = entity.providerId,
            title = title,
            channelName = subtitle ?: channel?.name.orEmpty(),
            channelId = channel?.entity?.providerId.orEmpty(),
            thumbnailUrl = artwork?.url.orEmpty(),
            duration = durationSeconds ?: 0,
            viewCount = 0L,
            uploadDate = details.joinToString(" • "),
            isLive = live,
            isUpcoming = upcoming,
        )
    }

    /** One page of comments; [sortId] is the order asked for, the plugin's first when none was. */
    fun comments(
        page: CommentsPage,
        sortId: String?,
    ): CommentsPageResult {
        val selected = sortId ?: page.sorts.firstOrNull()?.id
        return CommentsPageResult(
            comments = page.comments.map { it.toAppComment() },
            continuation = page.next,
            sortOptions = page.sorts.map { VideoCommentSort(title = it.label, token = it.id, selected = it.id == selected) },
            totalText = page.totalLabel,
        )
    }

    fun PluginComment.toAppComment(): AppComment =
        AppComment(
            id = id,
            author = author,
            authorThumbnail = authorAvatar?.url.orEmpty(),
            text = text,
            likeCount = likesLabel?.let(::plainCount) ?: 0,
            likeCountText = likesLabel.orEmpty(),
            publishedTime = publishedLabel.orEmpty(),
            replyCount = replyCount,
            continuationToken = repliesCursor,
            isPinned = pinned,
            isCreator = byCreator,
        )

    /** A like count written out in full, such as "1,234"; an abbreviated one ("1.2K") stays a label only. */
    private fun plainCount(label: String): Int? =
        label
            .takeIf { text -> text.isNotBlank() && text.all { it.isDigit() || it == ',' || it == '.' || it.isWhitespace() } }
            ?.filter { it.isDigit() }
            ?.toIntOrNull()

    fun LiveChatMessage.toAppMessage(): AppLiveChatMessage =
        AppLiveChatMessage(
            id = id,
            author = author,
            authorPhotoUrl = authorAvatar?.url,
            message = text,
            timestamp = null,
            type = if (highlight != null) LiveChatMessageType.SUPER_CHAT else LiveChatMessageType.TEXT,
            superChatAmount = highlight,
        )
}
