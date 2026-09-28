package io.github.aedev.flow.player.audio.visualizer

/**
 * Where playback is, estimated from repeated readings of the player's position.
 *
 * Some audio stacks report the position in steps: on an Amlogic AM6 it stands still for ~250 ms and
 * then jumps ahead, so any one reading can be up to a step behind what is audible. A stale reading
 * is only ever behind, never ahead, so the clock runs from the freshest reading of the last
 * [WINDOW_US] (the one furthest ahead of real time) and forgets the window whenever playback jumps,
 * pauses, changes speed or moves to another stream.
 */
internal class PlaybackClock {
    private class Reading(
        val realtimeUs: Long,
        val aheadUs: Double,
    )

    private val readings = ArrayDeque<Reading>()
    private var stream: Any? = null
    private var speed = 1f
    private var playing = false

    fun read(
        stream: Any?,
        positionUs: Long,
        realtimeUs: Long,
        speed: Float,
        playing: Boolean,
        jumped: Boolean,
    ): PlaybackAnchor {
        if (jumped || stream != this.stream || speed != this.speed || playing != this.playing) readings.clear()
        this.stream = stream
        this.speed = speed
        this.playing = playing
        readings.addLast(Reading(realtimeUs, positionUs - speed.toDouble() * realtimeUs))
        while (readings.first().realtimeUs < realtimeUs - WINDOW_US) readings.removeFirst()
        val ahead = if (playing) readings.maxOf { it.aheadUs } else positionUs - speed.toDouble() * realtimeUs
        return PlaybackAnchor(stream, (ahead + speed.toDouble() * realtimeUs).toLong(), realtimeUs, speed, playing)
    }

    private companion object {
        const val WINDOW_US = 2_000_000L
    }
}
