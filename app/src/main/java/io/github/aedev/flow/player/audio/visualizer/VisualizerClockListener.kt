package io.github.aedev.flow.player.audio.visualizer

import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Keeps [tap]'s playback clock anchored to the player it listens to. */
class VisualizerClockListener(
    private val tap: VisualizerAudioTap,
) : Player.Listener {
    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(*ANCHOR_EVENTS)) {
            tap.anchor(player, jumped = events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION))
        }
    }

    private companion object {
        val ANCHOR_EVENTS =
            intArrayOf(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
    }
}

// Out of step with the ~250 ms position steps some devices report, so the readings land at every
// phase of a step and the window always holds a fresh one.
private const val CLOCK_READING_MS = 150L

/**
 * Reads [player]'s position into [tap]'s clock every [CLOCK_READING_MS] while a visualizer listens and
 * music plays. A position that only moves in steps (as on an Amlogic AM6) is stale for most of each
 * step; enough readings let the clock find the fresh ones. Nothing runs while paused or unwatched.
 */
suspend fun followPlayerClock(
    tap: VisualizerAudioTap,
    player: Player,
) {
    tap.needsClock.collectLatest { following ->
        while (following) {
            delay(CLOCK_READING_MS)
            tap.anchor(player, jumped = false)
        }
    }
}
