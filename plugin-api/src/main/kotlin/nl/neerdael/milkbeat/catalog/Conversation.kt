package nl.neerdael.milkbeat.catalog

import kotlinx.serialization.Serializable

/** Comments on a video: its top-level comments in [sortId] order, or, with [cursor], the next batch or a comment's replies. */
@Serializable
data class CommentsRequest(
    val entity: EntityRef,
    val sortId: String? = null,
    val cursor: String? = null,
)

@Serializable
data class CommentsPage(
    val comments: List<Comment>,
    val next: String? = null,
    /** The orders the plugin offers, such as top and newest; the first is the default. */
    val sorts: List<FilterOption> = emptyList(),
    val totalLabel: String? = null,
)

@Serializable
data class Comment(
    val id: String,
    val author: String,
    val authorAvatar: Artwork? = null,
    val text: String,
    val publishedLabel: String? = null,
    val likesLabel: String? = null,
    val replyCount: Int = 0,
    /** Continues into this comment's replies through [CommentsRequest.cursor]. */
    val repliesCursor: String? = null,
    val pinned: Boolean = false,
    val byCreator: Boolean = false,
)

/** The next batch of a live video's chat; start with no cursor and poll with [LiveChatBatch.next]. */
@Serializable
data class LiveChatRequest(
    val entity: EntityRef,
    val cursor: String? = null,
)

@Serializable
data class LiveChatBatch(
    val messages: List<LiveChatMessage>,
    val next: String? = null,
    /** How long the host waits before asking again; the plugin passes on what its source asks. */
    val pollAfterMs: Long = 5_000,
)

@Serializable
data class LiveChatMessage(
    val id: String,
    val author: String,
    val authorAvatar: Artwork? = null,
    val text: String,
    val highlight: String? = null,
)
