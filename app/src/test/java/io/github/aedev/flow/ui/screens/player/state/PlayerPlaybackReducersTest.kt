package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.error.VideoErrorMapper
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Pins the fields every playback outcome writes onto [VideoPlayerUiState]. These are the reducers
 * the ViewModel drives from `_uiState.update`, so a change here is a change to what the player
 * screen shows, whatever the ordering around it.
 */
class PlayerPlaybackReducersTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a ready local copy replaces the path and clears the load`() {
        val segments = listOf(segment())
        val state = VideoPlayerUiState(isLoading = true, error = "boom", errorHint = "hint", isUpcoming = true)

        val next =
            state.applyLocalCopyReady(
                videoId = "vid_a",
                step = ResolvedPlayback.LocalCopyReady(localFilePath = "/tmp/a.mp4", offlineSegments = segments),
            )

        assertThat(next.localFilePath).isEqualTo("/tmp/a.mp4")
        assertThat(next.localFileVideoId).isEqualTo("vid_a")
        assertThat(next.offlineSponsorBlockSegments).isEqualTo(segments)
        assertThat(next.isLoading).isFalse()
        assertThat(next.error).isNull()
        assertThat(next.errorHint).isNull()
        assertThat(next.isUpcoming).isFalse()
        assertThat(next.upcomingReleaseTimeMs).isNull()
    }

    @Test
    fun `a VOD writes its own field set and leaves the load's other fields alone`() {
        val videoStream = videoStream("720p")
        val chapters = emptyList<org.schabi.newpipe.extractor.stream.StreamSegment>()
        val before =
            VideoPlayerUiState(
                isLoading = true,
                error = "boom",
                chapters = chapters,
                offlineSponsorBlockSegments = listOf(segment()),
                localFilePath = "/tmp/kept.mp4",
                localFileVideoId = "vid_a",
            )

        val next =
            before.applyVodStreams(
                cachedVideo = video("vid_a"),
                isArchivedLivestream = false,
                relatedVideos = listOf(video("rel_1")),
                videoStream = videoStream,
                audioStream = null,
                availableQualities = listOf(VideoQuality.Q_720P),
                savedPositionMs = 9_000L,
                isAdaptiveMode = true,
                autoplayEnabled = false,
            )

        assertThat(next.videoStream).isSameInstanceAs(videoStream)
        assertThat(next.selectedQuality).isEqualTo(VideoQuality.Q_720P)
        assertThat(next.savedPosition).isEqualTo(9_000L)
        assertThat(next.isAdaptiveMode).isTrue()
        assertThat(next.autoplayEnabled).isFalse()
        assertThat(next.isLoading).isFalse()
        assertThat(next.error).isNull()
        assertThat(next.isLive).isFalse()
        assertThat(next.isUpcoming).isFalse()
        // The second-writer gap this path has always had: these stay as the load left them.
        assertThat(next.chapters).isSameInstanceAs(chapters)
        assertThat(next.offlineSponsorBlockSegments).isEqualTo(before.offlineSponsorBlockSegments)
        assertThat(next.localFilePath).isEqualTo("/tmp/kept.mp4")
        assertThat(next.localFileVideoId).isEqualTo("vid_a")
    }

    @Test
    fun `a live stream publishes its manifest and clears the load`() {
        val before =
            VideoPlayerUiState(
                isLoading = true,
                error = "boom",
                errorHint = "hint",
                isUpcoming = true,
                upcomingReleaseTimeMs = 12L,
            )

        val next = before.applyLiveStreams(listOf(video("rel_1")), "https://example.invalid/live.m3u8")

        assertThat(next.hlsUrl).isEqualTo("https://example.invalid/live.m3u8")
        assertThat(next.isLive).isTrue()
        assertThat(next.isLoading).isFalse()
        assertThat(next.error).isNull()
        assertThat(next.errorHint).isNull()
        assertThat(next.isUpcoming).isFalse()
        assertThat(next.upcomingReleaseTimeMs).isNull()
    }

    @Test
    fun `a playback failure with no lane of its own leaves the one on screen alone`() {
        val onScreen = listOf(video("rel_1"))
        val error = VideoErrorMapper.VideoError(message = "no", hint = null)

        val kept = VideoPlayerUiState(relatedVideos = onScreen).applyPlaybackFailure(null, error)
        val replaced = VideoPlayerUiState(relatedVideos = onScreen).applyPlaybackFailure(listOf(video("rel_2")), error)

        assertThat(kept.relatedVideos).isEqualTo(onScreen)
        assertThat(kept.error).isEqualTo("no")
        assertThat(kept.errorHint).isNull()
        assertThat(replaced.relatedVideos.map { it.id }).containsExactly("rel_2")
    }

    @Test
    fun `the related lane is published only for the video the screen is showing`() {
        val videos = listOf(video("rel_1"))
        val cached = VideoPlayerUiState(cachedVideo = video("vid_a"))

        assertThat(cached.applyRelatedVideos("vid_a", videos).relatedVideos).isEqualTo(videos)
        assertThat(cached.applyRelatedVideos("vid_b", videos)).isSameInstanceAs(cached)
    }

    @Test
    fun `a blank video is only built when the screen holds nothing for the id`() {
        val cached = video("vid_a")

        assertThat(blankVideo("vid_a", cached)).isSameInstanceAs(cached)
        assertThat(blankVideo("vid_a", null).id).isEqualTo("vid_a")
        assertThat(blankVideo("vid_a", null).title).isEmpty()
    }

    private fun video(id: String): Video =
        Video(
            id = id,
            title = "Title $id",
            channelName = "Channel $id",
            channelId = "channel_$id",
            thumbnailUrl = "https://example.invalid/$id.jpg",
            duration = 120,
            viewCount = 1L,
            uploadDate = "2026-01-01",
        )

    private fun segment(): SponsorBlockSegment =
        SponsorBlockSegment(category = "sponsor", segment = listOf(0f, 1f), uuid = "uuid_1", actionType = "skip")

    private fun videoStream(resolution: String): VideoStream =
        VideoStream
            .Builder()
            .setId(resolution)
            .setContent("https://example.invalid/$resolution.mp4", true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setResolution(resolution)
            .setIsVideoOnly(true)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .build()
}
