package io.github.aedev.flow.player.audio.visualizer

/**
 * Where the player was at [realtimeUs]: which [stream] and how far into it, and whether it was moving.
 * Taken on every play, pause, seek and transition, so the renderer can tell where playback is on any
 * frame without asking the player, whose position may only be read on the main thread.
 */
internal data class PlaybackAnchor(
    val stream: Any?,
    val positionUs: Long,
    val realtimeUs: Long,
    val speed: Float,
    val playing: Boolean,
) {
    fun positionAt(nowUs: Long): Long = if (playing) positionUs + ((nowUs - realtimeUs) * speed).toLong() else positionUs
}
