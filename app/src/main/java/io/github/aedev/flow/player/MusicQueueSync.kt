package io.github.aedev.flow.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.syncCurrentTrackFromMediaItem(
    controller: Player,
    mediaItem: MediaItem,
) {
    val trackId = mediaItem.mediaId
    val currentQ = queueState.value
    val queueIndex =
        MusicQueuePlanner.currentQueueIndex(
            queueIds = currentQ.map { it.videoId },
            playerIndex = controller.currentMediaItemIndex,
            currentTrackId = trackId,
        )
    val track = currentQ.getOrNull(queueIndex) ?: currentQ.find { it.videoId == trackId }
    if (track != null) {
        val resolvedIndex =
            if (queueIndex != MusicQueuePlanner.INDEX_UNSET) {
                queueIndex
            } else {
                currentQ.indexOf(track)
            }
        currentTrackState.value = track
        currentQueueIndexState.value = resolvedIndex.coerceAtLeast(0)
    }
    if (
        pendingPlayNextMediaId == trackId &&
        (
            pendingPlayNextMediaIndex == MusicQueuePlanner.INDEX_UNSET ||
                pendingPlayNextMediaIndex == controller.currentMediaItemIndex
        )
    ) {
        clearPendingPlayNext()
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.enforcePendingPlayNext(
    controller: Player,
    mediaItem: MediaItem,
    isAutomaticTransition: Boolean,
): Boolean {
    val expectedMediaId = pendingPlayNextMediaId
    val actualMediaId = mediaItem.mediaId
    if (!MusicQueuePlanner.shouldForcePendingPlayNext(
            isAutomaticTransition = isAutomaticTransition,
            pendingMediaId = expectedMediaId,
            pendingPlayerIndex = pendingPlayNextMediaIndex,
            actualMediaId = actualMediaId,
            actualPlayerIndex = controller.currentMediaItemIndex,
        )
    ) {
        return false
    }

    val targetIndex =
        findPlayerMediaItemIndex(
            controller = controller,
            mediaId = expectedMediaId ?: return false,
            preferredIndex = pendingPlayNextMediaIndex,
        )
    if (targetIndex == MusicQueuePlanner.INDEX_UNSET) {
        Log.w("EnhancedMusicPlayer", "Pending play-next item $expectedMediaId is missing from player queue")
        clearPendingPlayNext()
        return false
    }

    Log.w(
        "EnhancedMusicPlayer",
        "Auto transition landed on $actualMediaId; forcing queued play-next item $expectedMediaId",
    )
    controller.seekTo(targetIndex, 0L)
    controller.play()
    return true
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.findPlayerMediaItemIndex(
    controller: Player,
    mediaId: String,
    preferredIndex: Int = MusicQueuePlanner.INDEX_UNSET,
): Int {
    if (
        preferredIndex in 0 until controller.mediaItemCount &&
        controller.getMediaItemAt(preferredIndex).mediaId == mediaId
    ) {
        return preferredIndex
    }

    for (index in 0 until controller.mediaItemCount) {
        if (controller.getMediaItemAt(index).mediaId == mediaId) {
            return index
        }
    }
    return MusicQueuePlanner.INDEX_UNSET
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.currentPlaybackQueueIndex(): Int {
    val queue = queueState.value
    return MusicQueuePlanner.currentQueueIndex(
        queueIds = queue.map { it.videoId },
        playerIndex = player?.currentMediaItemIndex ?: MusicQueuePlanner.INDEX_UNSET,
        currentTrackId = currentTrackState.value?.videoId,
    )
}
