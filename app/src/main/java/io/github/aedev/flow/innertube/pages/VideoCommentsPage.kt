package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Comment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val PINNED_RENDERING_PRIORITY = "RENDERING_PRIORITY_PINNED_COMMENT"

/** One entry of the comment section's sort menu, with the continuation that loads that order. */
data class VideoCommentSort(
    val title: String,
    val token: String,
    val selected: Boolean,
)

data class VideoCommentsPage(
    val comments: List<Comment>,
    val continuation: String?,
    val sortOptions: List<VideoCommentSort> = emptyList(),
    val totalText: String? = null,
    val totalCount: Long? = null,
)

/**
 * A page of a video's comments.
 *
 * [ownVideoId] is what lets a `1:57` in a comment be recognised as a seek into this video rather
 * than a link to another one.
 */
internal fun JsonElement.toVideoCommentsPage(ownVideoId: String?): VideoCommentsPage {
    val mutations = commentMutations()
    val items = continuationItems()
    val header =
        items.firstNotNullOfOrNull { item ->
            item.objectOrNull()?.get("commentsHeaderRenderer").objectOrNull()
        }
    val comments = mutableListOf<Comment>()
    var continuation: String? = null

    items.forEach { item ->
        val entry = item.objectOrNull() ?: return@forEach
        val thread = entry["commentThreadRenderer"].objectOrNull()
        if (thread != null) {
            thread.toVideoComment(mutations, ownVideoId)?.let(comments::add)
            return@forEach
        }
        if (continuation == null) {
            continuation = entry["continuationItemRenderer"].objectOrNull()?.continuationToken()
        }
    }

    return VideoCommentsPage(
        comments = comments.distinctBy(Comment::id),
        continuation = continuation,
        sortOptions = header?.toSortOptions().orEmpty(),
        totalText = header?.get("commentsCount").youtubeText(),
        totalCount =
            header
                ?.get("countText")
                .youtubeText()
                ?.let(::parseYouTubeViewCount)
                ?.takeIf { it > 0L },
    )
}

private fun JsonObject.toVideoComment(
    mutations: Map<String, JsonElement?>,
    ownVideoId: String?,
): Comment? {
    val rawViewModel = this["commentViewModel"].objectOrNull()
    val model =
        rawViewModel
            ?.get("commentViewModel")
            .objectOrNull()
            ?: rawViewModel
    val repliesRenderer =
        this["replies"]
            .objectOrNull()
            ?.get("commentRepliesRenderer")
            .objectOrNull()
    val comment =
        model?.toModernComment(
            mutations = mutations,
            repliesRenderer = repliesRenderer,
            ownVideoId = ownVideoId,
            pinnedText = model["pinnedText"].youtubeText(),
        )
            ?: this["comment"]
                .objectOrNull()
                ?.get("commentRenderer")
                .objectOrNull()
                ?.toLegacyComment(repliesRenderer)
    return if (this["renderingPriority"].stringOrNull() == PINNED_RENDERING_PRIORITY) {
        comment?.copy(isPinned = true)
    } else {
        comment
    }
}

private fun JsonObject.toSortOptions(): List<VideoCommentSort> =
    this["sortMenu"]
        .objectOrNull()
        ?.get("sortFilterSubMenuRenderer")
        .objectOrNull()
        ?.get("subMenuItems")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { item ->
            val entry = item.objectOrNull() ?: return@mapNotNull null
            val token =
                entry["serviceEndpoint"]
                    .objectOrNull()
                    ?.get("continuationCommand")
                    .objectOrNull()
                    ?.get("token")
                    .stringOrNull()
                    ?: return@mapNotNull null
            VideoCommentSort(
                title = entry["title"].youtubeText().orEmpty(),
                token = token,
                selected = entry["selected"].booleanOrFalse(),
            )
        }

/**
 * The items of every reload and append command in the response.
 *
 * A first page splits its header and its threads across two commands; a later page carries one.
 */
private fun JsonElement.continuationItems(): List<JsonElement> =
    objectOrNull()
        ?.get("onResponseReceivedEndpoints")
        .arrayOrNull()
        .orEmpty()
        .flatMap { endpoint ->
            val entry = endpoint.objectOrNull() ?: return@flatMap emptyList<JsonElement>()
            val command =
                entry["reloadContinuationItemsCommand"].objectOrNull()
                    ?: entry["appendContinuationItemsAction"].objectOrNull()
                    ?: return@flatMap emptyList<JsonElement>()
            command["continuationItems"].arrayOrNull() ?: JsonArray(emptyList())
        }
