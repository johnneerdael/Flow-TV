package io.github.aedev.flow.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.player.preload.MusicQueuePreparer
import io.github.aedev.flow.player.preload.QueuePreparationTarget
import io.github.aedev.flow.plugin.playback.QueuePreparationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val QUEUE_PREPARATION_URI = "milkbeat.queuePreparationUri"

private data class PreparationWindow(
    val uid: Any,
    val resolve: suspend (Uri) -> QueuePreparationResult,
)

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.createQueuePreparer() =
    MusicQueuePreparer(
        scope,
        { preparationTargets() },
        { target ->
            val result =
                withContext(Dispatchers.IO) {
                    (target.uid as PreparationWindow).resolve(Uri.parse(target.uri))
                }
            Log.d("MusicQueuePreparation", "Resolved queue entry: $result")
            result
        },
        { removePreparedQueueItem(it) },
    )

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.preparationTargets(): List<QueuePreparationTarget> {
    val resolve = prefetcher ?: return emptyList()
    val controller = player?.takeIf { it.isPlaying } ?: return emptyList()
    val timeline = controller.currentTimeline
    val current = controller.currentMediaItemIndex
    val tracks = queue.value
    if (timeline.isEmpty || current !in 0 until timeline.windowCount || tracks.size != controller.mediaItemCount) return emptyList()
    val window = Timeline.Window()
    return buildList {
        var index = timeline.getNextWindowIndex(current, Player.REPEAT_MODE_ALL, controller.shuffleModeEnabled)
        while (index != current && index != -1) {
            val track = tracks.getOrNull(index) ?: break
            val item = controller.getMediaItemAt(index)
            val uri = item.mediaMetadata.extras?.getString(QUEUE_PREPARATION_URI)
            val actual = item.localConfiguration?.uri
            val pluginUri =
                uri != null && (uri.startsWith("${MusicVideoItems.SONG_SCHEME}://") || uri.startsWith("${MusicVideoItems.SCHEME}://"))
            if (pluginUri && (actual == null || actual.toString() == uri) && item.mediaId == track.videoId) {
                add(QueuePreparationTarget(PreparationWindow(timeline.getWindow(index, window).uid, resolve), requireNotNull(uri)))
            }
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_ALL, controller.shuffleModeEnabled)
        }
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.removePreparedQueueItem(target: QueuePreparationTarget) {
    if (target !in preparationTargets()) return
    val controller = player ?: return
    val timeline = controller.currentTimeline
    val window = Timeline.Window()
    val uid = (target.uid as PreparationWindow).uid
    val index = (0 until timeline.windowCount).firstOrNull { timeline.getWindow(it, window).uid == uid } ?: return
    if (index == controller.currentMediaItemIndex) return
    val tracks = queueState.value.toMutableList()
    val track = tracks.getOrNull(index) ?: return
    if (track.videoId != controller.getMediaItemAt(index).mediaId ||
        controller
            .getMediaItemAt(index)
            .mediaMetadata.extras
            ?.getString(QUEUE_PREPARATION_URI) != target.uri
    ) {
        return
    }
    tracks.removeAt(index)
    queueState.value = tracks
    when {
        pendingPlayNextMediaIndex == index -> clearPendingPlayNext()
        pendingPlayNextMediaIndex > index -> pendingPlayNextMediaIndex--
    }
    controller.removeMediaItem(index)
    controller.currentMediaItem?.let { syncCurrentTrackFromMediaItem(controller, it) }
    triggerQueueSave()
    Log.d("MusicQueuePreparation", "Removed unmatched queue entry; ${tracks.size} entries remain")
}
