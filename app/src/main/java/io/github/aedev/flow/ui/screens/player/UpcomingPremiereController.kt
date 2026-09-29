package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.stream.UpcomingDetails
import io.github.aedev.flow.ui.screens.player.state.UpcomingPremierePolicy
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.applyCachedUpcoming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Everything the player screen does with a video that has not started yet: deciding whether it is a
 * premiere, showing the countdown instead of playback, and the reminder the user can arm on it.
 *
 * The decisions themselves live in [UpcomingPremierePolicy]; this class owns the countdown's place
 * in a load and the reminder state. It writes the same state flow the ViewModel constructs, and asks
 * it whether a load is still current before any countdown replaces the screen.
 */
internal class UpcomingPremiereController(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val playerPreferences: PlayerPreferences,
    private val scope: CoroutineScope,
    private val isLoadCurrent: (Long) -> Boolean,
    private val armMetadata: (videoId: String) -> Unit,
) {
    /** Mirrors the stored reminder ids onto the video the screen currently holds. */
    fun collectReminderState() {
        combine(
            playerPreferences.upcomingVideoReminderIds,
            uiState.map { it.cachedVideo?.id }.distinctUntilChanged(),
        ) { reminderIds, videoId ->
            videoId != null && videoId in reminderIds
        }.onEach { isReminderSet ->
            uiState.update { it.copy(isUpcomingReminderSet = isReminderSet) }
        }.launchIn(scope)
    }

    /** The countdown for a video whose own metadata already announces a release still ahead. */
    fun applyCountdown(
        video: Video,
        preserveQueueTitle: String? = uiState.value.queueTitle,
    ): Boolean {
        val releaseTimeMs = UpcomingPremierePolicy.releaseTimeFor(video) ?: return false
        uiState.update { UpcomingPremierePolicy.applyTo(it, video, releaseTimeMs, preserveQueueTitle) }
        armMetadata(video.id)
        return true
    }

    /** The countdown a load can skip straight to, because the screen already holds the premiere. */
    fun applyCachedCountdown(videoId: String): Boolean {
        val releaseTimeMs =
            uiState.value.cachedVideo
                ?.takeIf { it.id == videoId && it.isUpcoming }
                ?.let(UpcomingPremierePolicy::releaseTimeFor) ?: return false
        uiState.update { it.applyCachedUpcoming(releaseTimeMs) }
        armMetadata(videoId)
        return true
    }

    /** The countdown a finished load lands on, keeping what that load already gathered. */
    fun enterCountdown(
        videoId: String,
        releaseMs: Long?,
        relatedVideos: List<Video>,
        loadToken: Long,
        details: UpcomingDetails? = null,
    ): Boolean {
        if (!isLoadCurrent(loadToken)) return true
        val cached = uiState.value.cachedVideo?.takeIf { it.id == videoId }
        val upcomingVideo = UpcomingPremierePolicy.upcomingVideo(videoId, cached, releaseMs, details)
        uiState.update { UpcomingPremierePolicy.enterFrom(it, upcomingVideo, relatedVideos, releaseMs) }
        GlobalPlayerState.setCurrentVideo(upcomingVideo)
        return true
    }
}
