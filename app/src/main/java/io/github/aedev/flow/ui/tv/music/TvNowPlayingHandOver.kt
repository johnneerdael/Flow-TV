package io.github.aedev.flow.ui.tv.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RepeatMode

/** The track the player moves to when the playing one ends: the queue's next entry, else the radio continuation. */
internal fun upcomingTrack(
    queue: List<MusicTrack>,
    index: Int,
    automix: List<MusicTrack>,
    repeatMode: RepeatMode,
): MusicTrack? =
    when {
        repeatMode == RepeatMode.ONE -> null
        index in queue.indices && index < queue.lastIndex -> queue[index + 1]
        repeatMode == RepeatMode.ALL && queue.size > 1 && index == queue.lastIndex -> queue.first()
        else -> automix.firstOrNull()
    }

/** Whether the corner should be handing over: the last [CornerGlitch.WINDOW_MS] of a playing track with a known successor. */
internal fun isHandingOver(
    isPlaying: Boolean,
    hasUpcoming: Boolean,
    durationMs: Long,
    positionMs: Long,
): Boolean = isPlaying && hasUpcoming && durationMs > 0 && (durationMs - positionMs) in 1..CornerGlitch.WINDOW_MS

/**
 * Glitch frames for the corner while [handingOver], one per display frame, positioned by the
 * player's own clock. Nothing runs outside the window, while paused, or when this screen is gone.
 */
@Composable
internal fun rememberCornerGlitch(
    handingOver: Boolean,
    trackKey: String?,
    remainingMs: () -> Long,
): State<GlitchFrame?> {
    val frame: MutableState<GlitchFrame?> = remember { mutableStateOf(null) }
    LaunchedEffect(handingOver, trackKey) {
        frame.value = null
        if (!handingOver) return@LaunchedEffect
        while (true) {
            withFrameMillis { clockMs ->
                frame.value = CornerGlitch.frame(CornerGlitch.progress(remainingMs()), clockMs)
            }
        }
    }
    return frame
}
