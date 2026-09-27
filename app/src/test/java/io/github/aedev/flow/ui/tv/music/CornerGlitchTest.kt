package io.github.aedev.flow.ui.tv.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RepeatMode
import org.junit.Test

class CornerGlitchTest {
    private val clocks = (0L until 60_000L step 16L)

    private fun frames(progress: Float) = clocks.map { CornerGlitch.frame(progress, it) }

    private fun GlitchFrame.allBands() = cover + artist + title

    @Test
    fun `progress runs from seven seconds before the track change to three after it`() {
        assertThat(CornerGlitch.progress(-7_000L)).isEqualTo(0f)
        assertThat(CornerGlitch.progress(-2_000L)).isEqualTo(0.5f)
        assertThat(CornerGlitch.progress(0L)).isEqualTo(0.7f)
        assertThat(CornerGlitch.progress(3_000L)).isEqualTo(1f)
        assertThat(CornerGlitch.progress(-30_000L)).isEqualTo(0f)
        assertThat(CornerGlitch.progress(30_000L)).isEqualTo(1f)
    }

    @Test
    fun `the corner has settled on the next track as it starts`() {
        assertThat(CornerGlitch.frame(1f, 1234L)).isEqualTo(CornerGlitch.steady(showsNext = true))
    }

    @Test
    fun `bands always cover each element top to bottom without gaps`() {
        (0..9).map { it / 10f }.flatMap(::frames).forEach { frame ->
            listOf(frame.cover, frame.artist, frame.title).forEach { bands ->
                assertThat(bands.first().top).isEqualTo(0f)
                assertThat(bands.last().bottom).isEqualTo(1f)
                bands.zipWithNext { upper, lower -> assertThat(lower.top).isEqualTo(upper.bottom) }
            }
        }
    }

    @Test
    fun `glitches grow more frequent and lean to the next track as it nears`() {
        fun glitchShare(progress: Float) = frames(progress).count { it != CornerGlitch.steady(false) && it != CornerGlitch.steady(true) }

        fun nextShare(progress: Float) =
            frames(progress).sumOf { f -> f.allBands().count { it.showsNext } } / frames(progress).sumOf { it.allBands().size }.toDouble()

        assertThat(glitchShare(0.9f)).isGreaterThan(glitchShare(0.1f) * 3)
        assertThat(nextShare(0.1f)).isLessThan(0.1)
        assertThat(nextShare(0.9f)).isGreaterThan(0.5)
    }

    @Test
    fun `the same moment always renders the same glitch`() {
        assertThat(CornerGlitch.frame(0.7f, 4_321L)).isEqualTo(CornerGlitch.frame(0.7f, 4_321L))
    }

    private fun track(id: String) =
        MusicTrack(videoId = id, title = id, artist = "a", thumbnailUrl = "", duration = 0, channelId = "", views = 0L)

    @Test
    fun `the upcoming track follows the queue, wraps on repeat-all and falls back to the radio`() {
        val queue = listOf(track("a"), track("b"), track("c"))
        val radio = listOf(track("r"))
        assertThat(upcomingTrack(queue, 0, radio, RepeatMode.OFF)?.videoId).isEqualTo("b")
        assertThat(upcomingTrack(queue, 2, radio, RepeatMode.OFF)?.videoId).isEqualTo("r")
        assertThat(upcomingTrack(queue, 2, radio, RepeatMode.ALL)?.videoId).isEqualTo("a")
        assertThat(upcomingTrack(queue, 0, radio, RepeatMode.ONE)).isNull()
        assertThat(upcomingTrack(queue, 2, emptyList(), RepeatMode.OFF)).isNull()
    }

    @Test
    fun `the lead runs only in the last seven seconds of a playing track with a successor`() {
        assertThat(isHandingOver(isPlaying = true, hasUpcoming = true, durationMs = 200_000, positionMs = 195_000)).isTrue()
        assertThat(isHandingOver(isPlaying = true, hasUpcoming = true, durationMs = 200_000, positionMs = 192_000)).isFalse()
        assertThat(isHandingOver(isPlaying = false, hasUpcoming = true, durationMs = 200_000, positionMs = 195_000)).isFalse()
        assertThat(isHandingOver(isPlaying = true, hasUpcoming = false, durationMs = 200_000, positionMs = 195_000)).isFalse()
        assertThat(isHandingOver(isPlaying = true, hasUpcoming = true, durationMs = 0, positionMs = 0)).isFalse()
    }

    @Test
    fun `the tail runs only in the first three seconds of a playing track the corner handed over to`() {
        assertThat(isSettlingIn(isPlaying = true, hasOutgoing = true, positionMs = 0)).isTrue()
        assertThat(isSettlingIn(isPlaying = true, hasOutgoing = true, positionMs = 2_999)).isTrue()
        assertThat(isSettlingIn(isPlaying = true, hasOutgoing = true, positionMs = 3_000)).isFalse()
        assertThat(isSettlingIn(isPlaying = false, hasOutgoing = true, positionMs = 1_000)).isFalse()
        assertThat(isSettlingIn(isPlaying = true, hasOutgoing = false, positionMs = 1_000)).isFalse()
    }
}
