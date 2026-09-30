package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.error.VideoErrorMapper
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.player.stream.VideoQualityOptions
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

/*
 * Every state transition the player screen makes while a load resolves, as pure functions over
 * VideoPlayerUiState.
 *
 * The ViewModel keeps the ordering: which side effect runs before which write, and which write is
 * skipped because the load that produced it is no longer current. Nothing here reads the clock, the
 * network, the player or the preferences — the values a transition needs are arguments, so the
 * fields each outcome writes can be asserted without a ViewModel.
 */

/** A downloaded copy is about to play: the local path replaces whatever the load had reached. */
internal fun VideoPlayerUiState.applyLocalCopyReady(
    videoId: String,
    step: ResolvedPlayback.LocalCopyReady,
): VideoPlayerUiState =
    copy(
        localFilePath = step.localFilePath,
        localFileVideoId = videoId,
        offlineSponsorBlockSegments = step.offlineSegments,
        error = null,
        errorHint = null,
        isLoading = false,
        isUpcoming = false,
        upcomingReleaseTimeMs = null,
    )

/**
 * The streams a load resolved: what plays, at what qualities, and the formats behind them.
 *
 * `chapters`, `offlineSponsorBlockSegments`, `localFilePath` and `localFileVideoId` are deliberately
 * left as the load left them — the caller sets chapters, and the local-copy fields belong to the
 * download step.
 */
internal fun VideoPlayerUiState.applyVodStreams(
    cachedVideo: Video,
    isArchivedLivestream: Boolean,
    relatedVideos: List<Video>,
    videoStream: VideoStream?,
    audioStream: AudioStream?,
    availableQualities: List<VideoQuality>,
    savedPositionMs: Long,
    isAdaptiveMode: Boolean,
    autoplayEnabled: Boolean,
): VideoPlayerUiState =
    copy(
        cachedVideo = cachedVideo,
        isArchivedLivestream = isArchivedLivestream,
        relatedVideos = relatedVideos,
        videoStream = videoStream,
        audioStream = audioStream,
        availableQualities = availableQualities,
        selectedQuality = VideoQualityOptions.qualityOf(videoStream),
        isLoading = false,
        error = null,
        errorHint = null,
        savedPosition = savedPositionMs,
        isAdaptiveMode = isAdaptiveMode,
        autoplayEnabled = autoplayEnabled,
        isLive = false,
        isUpcoming = false,
        upcomingReleaseTimeMs = null,
    )

/** A live stream, played from its manifest. */
internal fun VideoPlayerUiState.applyLiveStreams(
    relatedVideos: List<Video>,
    hlsUrl: String?,
): VideoPlayerUiState =
    copy(
        relatedVideos = relatedVideos,
        isLoading = false,
        error = null,
        errorHint = null,
        hlsUrl = hlsUrl,
        isLive = true,
        isUpcoming = false,
        upcomingReleaseTimeMs = null,
    )

/** Nothing resolved. A null [relatedVideos] leaves the lane the screen is already showing alone. */
internal fun VideoPlayerUiState.applyPlaybackFailure(
    relatedVideos: List<Video>?,
    videoError: VideoErrorMapper.VideoError,
): VideoPlayerUiState =
    copy(
        isLoading = false,
        relatedVideos = relatedVideos ?: this.relatedVideos,
        error = videoError.message,
        errorHint = videoError.hint,
    )

/** The related lane, dropped when the screen has already moved to another video. */
internal fun VideoPlayerUiState.applyRelatedVideos(
    videoId: String,
    videos: List<Video>,
): VideoPlayerUiState =
    if (cachedVideo?.id != videoId) {
        this
    } else {
        copy(relatedVideos = videos)
    }

/**
 * The richest [Video] the screen holds for [videoId], or null when it holds none.
 *
 * Engine signals are fed from this rather than from the title-only stub a card hands over, so a
 * like or a watch recorded here carries the tags, description and duration the load resolved.
 */
internal fun VideoPlayerUiState.richVideoFor(videoId: String): Video? = cachedVideo?.takeIf { it.id == videoId }

/** The identity a load enriches when the screen holds nothing for the video yet. */
internal fun blankVideo(
    videoId: String,
    cached: Video?,
): Video =
    cached ?: Video(
        id = videoId,
        title = "",
        channelName = "",
        channelId = "",
        thumbnailUrl = "",
        duration = 0,
        viewCount = 0L,
        uploadDate = "",
    )
