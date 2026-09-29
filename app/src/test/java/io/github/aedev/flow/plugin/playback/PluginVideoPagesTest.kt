package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.LiveChatMessageType
import io.github.aedev.flow.plugin.playback.PluginVideoPages.toAppMessage
import io.github.aedev.flow.plugin.playback.PluginVideoPages.toVideo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.Comment
import nl.neerdael.milkbeat.catalog.CommentsPage
import nl.neerdael.milkbeat.catalog.CommentsRequest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.LiveChatMessage
import nl.neerdael.milkbeat.catalog.MetadataItem
import org.junit.Test
import io.github.aedev.flow.data.model.Comment as AppComment

/** Pins how the video plugin's related items, comments and chat read in the player's own models. */
class PluginVideoPagesTest {
    private val topComment =
        Comment(
            id = "c1",
            author = "@someone",
            authorAvatar = Artwork("https://yt.invalid/a.jpg"),
            text = "Great",
            publishedLabel = "2 days ago",
            likesLabel = "1.2K",
            replyCount = 3,
            repliesCursor = "replies-1",
            pinned = true,
            byCreator = true,
        )

    @Test
    fun `a related item keeps its channel, length and details`() {
        val video =
            MetadataItem(
                id = "video:v1",
                entity = EntityRef(EntityKind.VIDEO, "v1"),
                title = "A video",
                subtitle = "A channel",
                artwork = Artwork("https://i.invalid/v1.jpg"),
                artists = listOf(ArtistCredit("A channel", EntityRef(EntityKind.CHANNEL, "UC1"))),
                durationSeconds = 95,
                details = listOf("1K views", "2 days ago"),
                live = true,
            ).toVideo()!!

        assertThat(video.id).isEqualTo("v1")
        assertThat(video.channelName).isEqualTo("A channel")
        assertThat(video.channelId).isEqualTo("UC1")
        assertThat(video.thumbnailUrl).isEqualTo("https://i.invalid/v1.jpg")
        assertThat(video.duration).isEqualTo(95)
        assertThat(video.uploadDate).isEqualTo("1K views • 2 days ago")
        assertThat(video.isLive).isTrue()
    }

    @Test
    fun `a comments page keeps its cursor, total and sorts with the asked one selected`() {
        val page =
            PluginVideoPages.comments(
                CommentsPage(
                    comments = listOf(topComment),
                    next = "page-2",
                    sorts = listOf(FilterOption("top", "Top"), FilterOption("newest", "Newest")),
                    totalLabel = "1,204",
                ),
                sortId = "newest",
            )

        assertThat(page.continuation).isEqualTo("page-2")
        assertThat(page.hasMore).isTrue()
        assertThat(page.totalText).isEqualTo("1,204")
        assertThat(page.sortOptions.map { it.token to it.selected }).containsExactly("top" to false, "newest" to true).inOrder()
        val comment = page.comments.single()
        assertThat(comment.authorThumbnail).isEqualTo("https://yt.invalid/a.jpg")
        assertThat(comment.likeCountText).isEqualTo("1.2K")
        assertThat(comment.likeCount).isEqualTo(0)
        assertThat(comment.replyCount).isEqualTo(3)
        assertThat(comment.continuationToken).isEqualTo("replies-1")
        assertThat(comment.isPinned).isTrue()
        assertThat(comment.isCreator).isTrue()
    }

    @Test
    fun `without an asked sort the plugin's first is selected`() {
        val page =
            PluginVideoPages.comments(
                CommentsPage(emptyList(), sorts = listOf(FilterOption("top", "Top"), FilterOption("newest", "Newest"))),
                sortId = null,
            )

        assertThat(page.sortOptions.map { it.selected }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `a like count written out in full is read as a number`() {
        val page = PluginVideoPages.comments(CommentsPage(listOf(topComment.copy(likesLabel = "1,234"))), sortId = null)

        assertThat(page.comments.single().likeCount).isEqualTo(1234)
    }

    @Test
    fun `a highlighted chat message reads as a super chat`() {
        val message = LiveChatMessage(id = "m1", author = "fan", text = "hi", highlight = "€5.00").toAppMessage()

        assertThat(message.type).isEqualTo(LiveChatMessageType.SUPER_CHAT)
        assertThat(message.superChatAmount).isEqualTo("€5.00")
        assertThat(LiveChatMessage(id = "m2", author = "fan", text = "hi").toAppMessage().type).isEqualTo(LiveChatMessageType.TEXT)
    }

    @Test
    fun `the comments source pages and loads replies by the plugin's cursors`() =
        runTest {
            val pluginVideo: PluginVideo = mockk()
            val requests = mutableListOf<CommentsRequest>()
            coEvery { pluginVideo.comments(capture(requests)) } answers {
                val request = firstArg<CommentsRequest>()
                Result.success(
                    when (request.cursor) {
                        null -> CommentsPage(listOf(topComment), next = "page-2", sorts = listOf(FilterOption("top", "Top")))
                        "page-2" -> CommentsPage(listOf(topComment.copy(id = "c2")))
                        else -> CommentsPage(listOf(topComment.copy(id = "r1")), next = "replies-2")
                    },
                )
            }
            val source = PluginCommentsSource(pluginVideo)

            val first = source.first(PluginVideoStreamsTest.VIDEO_ID, sortToken = "top")
            val second = source.next(PluginVideoStreamsTest.VIDEO_ID, first)!!
            val replies = source.replies(PluginVideoStreamsTest.VIDEO_ID, first.comments.single())
            val none = source.next(PluginVideoStreamsTest.VIDEO_ID, second)

            assertThat(requests.map { it.sortId to it.cursor })
                .containsExactly("top" to null, null to "page-2", null to "replies-1")
                .inOrder()
            assertThat(second.comments.map(AppComment::id)).containsExactly("c2")
            assertThat(second.sortOptions).isEqualTo(first.sortOptions)
            assertThat(replies.comments.map(AppComment::id)).containsExactly("r1")
            assertThat(replies.continuation).isEqualTo("replies-2")
            assertThat(none).isNull()
        }
}
