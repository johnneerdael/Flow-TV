package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.innertube.pages.accessibilityLabel
import io.github.aedev.flow.innertube.pages.bestThumbnailUrl
import io.github.aedev.flow.innertube.pages.countTextFromAccessibilityLabel
import io.github.aedev.flow.innertube.pages.normalizeImageUrl
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonObject

data class CommunityPost(
    val id: String,
    val authorName: String,
    val authorAvatarUrl: String,
    val text: String,
    val attachment: PostAttachment?,
    val likeCountText: String,
    val commentCountText: String,
    val commentEndpointParams: String?,
    val publishedTimeText: String,
)

internal fun JsonObject.toCommunityPost(
    fallbackAuthorName: String,
    fallbackAuthorAvatarUrl: String,
    owner: FeedItemOwner,
): CommunityPost? {
    val id = this["postId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val replyButton =
        this["actionButtons"]
            .objectOrNull()
            ?.get("commentActionButtonsRenderer")
            .objectOrNull()
            ?.get("replyButton")
            .objectOrNull()
            ?.get("buttonRenderer")
            .objectOrNull()
    val navigationEndpoint = replyButton?.get("navigationEndpoint").objectOrNull()
    val authorAvatar =
        this["authorThumbnail"].bestThumbnailUrl()
            ?: fallbackAuthorAvatarUrl
    return CommunityPost(
        id = id,
        authorName =
            this["authorText"]
                .youtubeText()
                ?.takeIf(String::isNotBlank)
                ?: fallbackAuthorName,
        authorAvatarUrl = normalizeImageUrl(authorAvatar),
        text = this["contentText"].youtubeText().orEmpty(),
        attachment = this["backstageAttachment"].toPostAttachment(owner),
        likeCountText = this["voteCount"].youtubeText().orEmpty(),
        commentCountText =
            replyButton?.get("text").youtubeText()
                ?: replyButton?.accessibilityLabel()?.countTextFromAccessibilityLabel()
                ?: "",
        commentEndpointParams =
            navigationEndpoint
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("params")
                .stringOrNull()
                ?: navigationEndpoint
                    ?.get("signInEndpoint")
                    .objectOrNull()
                    ?.get("nextEndpoint")
                    .objectOrNull()
                    ?.get("browseEndpoint")
                    .objectOrNull()
                    ?.get("params")
                    .stringOrNull(),
        publishedTimeText = this["publishedTimeText"].youtubeText().orEmpty(),
    )
}
