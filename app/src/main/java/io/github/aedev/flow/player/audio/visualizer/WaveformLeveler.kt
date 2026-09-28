package io.github.aedev.flow.player.audio.visualizer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns the audible samples into the engine's 8-bit waveform (128 = silence) without flattening the
 * music. projectM hears beats as the energy of a moment against its own few-second average, so scaling
 * every short window to full range, as the platform Visualizer does, makes a quiet bar and a kick drum
 * look alike and the presets stop reacting. Here one gain follows the loudness of the last several
 * seconds instead: a quiet recording still fills the 8 bits, a break stays quiet, and near-silence is
 * never lifted more than [MAX_GAIN] times.
 */
internal class WaveformLeveler {
    private var envelope = 0f

    /** Levels [samples] (mono, 16-bit) into [out]; [seconds] is the time since the previous call. */
    fun level(
        samples: ShortArray,
        out: ByteArray,
        seconds: Float,
    ) {
        var peak = 0
        for (sample in samples) peak = max(peak, abs(sample.toInt()))
        val windowPeak = peak / FULL_SCALE
        envelope = if (windowPeak >= envelope) windowPeak else max(windowPeak, envelope * exp(-seconds / RELEASE_SECONDS))
        val gain = HEADROOM / max(envelope, HEADROOM / MAX_GAIN)
        for (index in samples.indices) {
            out[index] = (SILENCE + samples[index] / FULL_SCALE * gain * SILENCE).roundToInt().coerceIn(0, U8_MAX).toByte()
        }
    }

    private companion object {
        const val FULL_SCALE = 32_768f
        const val SILENCE = 128f
        const val U8_MAX = 255
        const val HEADROOM = 0.99f
        const val MAX_GAIN = 8f

        // Slower than projectM's own ~4 s average, so the gain never evens out what it listens for.
        const val RELEASE_SECONDS = 10f
    }
}
