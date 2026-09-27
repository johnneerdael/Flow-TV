package io.github.aedev.flow.player.audio.visualizer

import androidx.media3.common.Player

/** Keeps [tap]'s playback clock anchored to the player it listens to. */
class VisualizerClockListener(
    private val tap: VisualizerAudioTap,
) : Player.Listener {
    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(*ANCHOR_EVENTS)) tap.anchor(player)
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
