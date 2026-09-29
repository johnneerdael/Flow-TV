package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

/**
 * The queue decides what plays next and what the queue sheet shows, and the two have to agree: an
 * index that drifts from the published list makes the sheet highlight one video and play another.
 * None of this was reachable from a test while the state lived inside `EnhancedPlayerManager`.
 */
class PlaybackQueueControllerTest {
    private fun video(id: String) =
        Video(
            id = id,
            title = "Video $id",
            channelName = "Channel",
            channelId = "UC123",
            thumbnailUrl = "https://example.test/$id.jpg",
            duration = 120,
            viewCount = 10L,
            uploadDate = "today",
        )

    private fun ids(videos: List<Video>) = videos.map(Video::id)

    private fun controllerOf(
        vararg ids: String,
        startIndex: Int = 0,
    ): PlaybackQueueController =
        PlaybackQueueController().apply {
            setQueue(ids.map(::video), startIndex, title = "Playlist")
        }

    @Test
    fun `setQueue starts at the requested index and publishes the queue`() {
        val controller = PlaybackQueueController()

        val start = controller.setQueue(listOf(video("a"), video("b"), video("c")), startIndex = 1, title = "Playlist")

        assertThat(start?.id).isEqualTo("b")
        assertThat(controller.currentIndex).isEqualTo(1)
        assertThat(ids(controller.videos.value)).containsExactly("a", "b", "c").inOrder()
        assertThat(controller.currentIndexState.value).isEqualTo(1)
        assertThat(controller.title).isEqualTo("Playlist")
    }

    @Test
    fun `setQueue on an empty list parks the index instead of pointing at a video`() {
        val controller = PlaybackQueueController()

        val start = controller.setQueue(emptyList(), startIndex = 0, title = null)

        assertThat(start).isNull()
        assertThat(controller.currentIndex).isEqualTo(-1)
        assertThat(controller.isEmpty).isTrue()
    }

    @Test
    fun `moveTo out of range leaves the position alone`() {
        val controller = controllerOf("a", "b", "c", startIndex = 1)

        assertThat(controller.moveTo(5)).isNull()
        assertThat(controller.moveTo(-1)).isNull()
        assertThat(controller.currentIndex).isEqualTo(1)
    }

    @Test
    fun `movePrevious stops at the head of the queue`() {
        val controller = controllerOf("a", "b", startIndex = 1)

        assertThat(controller.movePrevious()?.id).isEqualTo("a")
        assertThat(controller.movePrevious()).isNull()
        assertThat(controller.currentIndex).isEqualTo(0)
    }

    @Test
    fun `append adds to the end without moving the current video`() {
        val controller = controllerOf("a", "b", startIndex = 1)

        val outcome = controller.append(video("x"), currentlyPlaying = null)

        assertThat(outcome).isEqualTo(QueueAddOutcome.Inserted)
        assertThat(ids(controller.videos.value)).containsExactly("a", "b", "x").inOrder()
        assertThat(controller.currentIndex).isEqualTo(1)
    }

    @Test
    fun `queueing with nothing playing and nothing queued reports there is no queue`() {
        val controller = PlaybackQueueController()

        val outcome = controller.append(video("x"), currentlyPlaying = null)

        assertThat(outcome).isEqualTo(QueueAddOutcome.NoActiveQueue)
        assertThat(controller.isEmpty).isTrue()
        assertThat(controller.currentIndex).isEqualTo(-1)
    }

    @Test
    fun `removing an earlier video keeps the same video current`() {
        val controller = controllerOf("a", "b", "c", startIndex = 2)

        assertThat(controller.removeAt(0)).isTrue()
        assertThat(controller.currentIndex).isEqualTo(1)
        assertThat(controller.currentVideo?.id).isEqualTo("c")
        assertThat(controller.currentIndexState.value).isEqualTo(1)
    }

    @Test
    fun `the currently playing video cannot be removed out from under playback`() {
        val controller = controllerOf("a", "b", "c", startIndex = 1)

        assertThat(controller.removeAt(1)).isFalse()
        assertThat(controller.removeAt(9)).isFalse()
        assertThat(ids(controller.videos.value)).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `isCurrent only matches the video at the current position`() {
        val controller = controllerOf("a", "b", "c", startIndex = 1)

        assertThat(controller.isCurrent("b")).isTrue()
        assertThat(controller.isCurrent("a")).isFalse()
        assertThat(controller.isCurrent("missing")).isFalse()
    }

    @Test
    fun `the picked video is not reached by advance, so it can resume`() {
        val controller = controllerOf("a", "b", "c", startIndex = 1)

        assertThat(controller.isReachedByAdvance("b")).isFalse()
    }

    @Test
    fun `a video the queue moves to is reached by advance`() {
        val controller = controllerOf("a", "b", "c", startIndex = 0)

        controller.moveTo(1)

        assertThat(controller.isReachedByAdvance("b")).isTrue()
        assertThat(controller.isReachedByAdvance("a")).isFalse()
    }

    @Test
    fun `moving back to the picked video after advancing counts as an advance`() {
        val controller = controllerOf("a", "b", startIndex = 0)

        controller.moveTo(1)
        controller.movePrevious()

        assertThat(controller.isReachedByAdvance("a")).isTrue()
    }

    @Test
    fun `setting a queue with shuffle off plays it in order even after a shuffled one`() {
        val controller = PlaybackQueueController()
        controller.setQueue(listOf(video("a"), video("b"), video("c")), startIndex = 0, title = null, shuffle = true)

        controller.setQueue(listOf(video("a"), video("b"), video("c")), startIndex = 0, title = null, shuffle = false)

        assertThat(controller.shuffleEnabled).isFalse()
        assertThat(ids(controller.videos.value)).containsExactly("a", "b", "c").inOrder()
    }
}
