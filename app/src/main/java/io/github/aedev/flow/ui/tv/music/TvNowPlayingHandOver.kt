package io.github.aedev.flow.ui.tv.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

/** The lead of the hand-over: the last [CornerGlitch.LEAD_MS] of a playing track with a known successor. */
internal fun isHandingOver(
    isPlaying: Boolean,
    hasUpcoming: Boolean,
    durationMs: Long,
    positionMs: Long,
): Boolean = isPlaying && hasUpcoming && durationMs > 0 && (durationMs - positionMs) in 1..CornerGlitch.LEAD_MS

/** The tail of the hand-over: the first [CornerGlitch.TAIL_MS] of a track the corner was handing over to. */
internal fun isSettlingIn(
    isPlaying: Boolean,
    hasOutgoing: Boolean,
    positionMs: Long,
): Boolean = isPlaying && hasOutgoing && positionMs in 0 until CornerGlitch.TAIL_MS

/** What the corner shows: [shown], glitching over to [incoming] while a hand-over runs. */
internal class CornerHandOver(
    val shown: CornerTrack,
    val incoming: CornerTrack?,
    val glitch: State<GlitchFrame?>,
)

private class LastCorner {
    var key: String? = null
    var track: CornerTrack? = null
    var handingOver = false
}

/**
 * Runs the corner's hand-over across a track change: the lead glitches from the playing track to the
 * upcoming one, and when the player moves on mid-lead, the tail keeps glitching from the track that
 * just ended to the new one. [positionMs] and [durationMs] gate the phases at the player's 1 Hz
 * cadence; [positionNow] and [durationNow] position each frame precisely.
 */
@Composable
internal fun rememberCornerHandOver(
    current: CornerTrack,
    trackKey: String?,
    upcoming: CornerTrack?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    positionNow: () -> Long,
    durationNow: () -> Long,
): CornerHandOver {
    val handingOver = isHandingOver(isPlaying, upcoming != null, durationMs, positionMs)
    val last = remember { LastCorner() }
    val outgoing = remember(trackKey) { last.track.takeIf { last.handingOver && last.key != trackKey } }
    SideEffect {
        last.key = trackKey
        last.track = current
        last.handingOver = handingOver
    }
    val settlingIn = isSettlingIn(isPlaying, outgoing != null, positionMs)
    val glitch =
        rememberCornerGlitch(handingOver || settlingIn) {
            if (settlingIn) positionNow() else positionNow() - durationNow()
        }
    return when {
        settlingIn && outgoing != null -> CornerHandOver(outgoing, current, glitch)
        handingOver -> CornerHandOver(current, upcoming, glitch)
        else -> CornerHandOver(current, null, glitch)
    }
}

/**
 * Glitch frames for the corner while [active], one per display frame, positioned by the player's own
 * clock through [msFromChange]. Nothing runs outside the hand-over, while paused, or when this screen
 * is gone; the loop survives the track change so the lead flows straight into the tail.
 */
@Composable
private fun rememberCornerGlitch(
    active: Boolean,
    msFromChange: () -> Long,
): State<GlitchFrame?> {
    val frame: MutableState<GlitchFrame?> = remember { mutableStateOf(null) }
    val clock by rememberUpdatedState(msFromChange)
    LaunchedEffect(active) {
        frame.value = null
        if (!active) return@LaunchedEffect
        while (true) {
            withFrameMillis { clockMs ->
                frame.value = CornerGlitch.frame(CornerGlitch.progress(clock()), clockMs)
            }
        }
    }
    return frame
}
