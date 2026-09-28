package io.github.aedev.flow.player.audio.visualizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WaveformLevelerTest {
    private val frame = 1f / 60f

    private fun tone(amplitude: Float) = ShortArray(576) { (amplitude * 32_767 * sin(2 * PI * 440 * it / 48_000)).toInt().toShort() }

    private fun WaveformLeveler.swing(samples: ShortArray): Int {
        val out = ByteArray(samples.size)
        level(samples, out, frame)
        return out.maxOf { abs((it.toInt() and 0xFF) - 128) }
    }

    @Test
    fun `a quiet moment after a loud one stays quiet instead of being lifted to full range`() {
        val leveler = WaveformLeveler()
        val loud = leveler.swing(tone(0.8f))
        val quiet = leveler.swing(tone(0.1f))
        assertThat(loud).isAtLeast(120)
        assertThat(quiet).isAtMost(loud / 6)
    }

    @Test
    fun `a quiet recording is lifted to use the 8 bits, but never by more than eight times`() {
        val leveler = WaveformLeveler()
        repeat(1_000) { leveler.swing(tone(0.25f)) }
        assertThat(leveler.swing(tone(0.25f))).isAtLeast(120)

        val hiss = WaveformLeveler()
        assertThat(hiss.swing(tone(0.01f))).isAtMost(11)
    }

    @Test
    fun `silence stays at the midpoint`() {
        val out = ByteArray(576)
        WaveformLeveler().level(ShortArray(576), out, frame)
        assertThat(out.toSet()).containsExactly(128.toByte())
    }
}
