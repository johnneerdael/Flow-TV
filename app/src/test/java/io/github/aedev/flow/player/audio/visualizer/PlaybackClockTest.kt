package io.github.aedev.flow.player.audio.visualizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackClockTest {
    // The AM6 reports its position in ~250 ms steps: it stands still, then jumps to the true position.
    private fun steppedPositionUs(realtimeUs: Long) = realtimeUs / 250_000 * 250_000

    @Test
    fun `a stepped position is tracked from its fresh readings, not its stale ones`() {
        val clock = PlaybackClock()
        var anchor: PlaybackAnchor? = null
        for (realtimeUs in 0L..3_000_000L step 150_000) {
            anchor = clock.read("a", steppedPositionUs(realtimeUs), realtimeUs, 1f, playing = true, jumped = false)
        }
        val now = 3_100_000L
        assertThat(anchor!!.positionAt(now)).isIn(
            com.google.common.collect.Range
                .closed(now - 50_000, now),
        )
    }

    @Test
    fun `a seek forgets the readings from before it`() {
        val clock = PlaybackClock()
        for (realtimeUs in 0L..1_000_000L step 150_000) clock.read("a", 60_000_000 + realtimeUs, realtimeUs, 1f, true, false)
        val anchor = clock.read("a", 10_000_000, 1_100_000, 1f, playing = true, jumped = true)
        assertThat(anchor.positionAt(1_100_000)).isEqualTo(10_000_000)
    }

    @Test
    fun `a new stream and a pause each start the clock afresh`() {
        val clock = PlaybackClock()
        for (realtimeUs in 0L..1_000_000L step 150_000) clock.read("a", 90_000_000 + realtimeUs, realtimeUs, 1f, true, false)
        assertThat(clock.read("b", 0, 1_100_000, 1f, playing = true, jumped = false).positionAt(1_100_000)).isEqualTo(0)
        val paused = clock.read("b", 400_000, 1_500_000, 1f, playing = false, jumped = false)
        assertThat(paused.positionAt(9_000_000)).isEqualTo(400_000)
    }
}
