package io.github.aedev.flow.ui.tv.music

import kotlin.random.Random

/** A horizontal slice of one corner element, drawn from the playing or the next track and shifted sideways. */
internal data class GlitchBand(
    val top: Float,
    val bottom: Float,
    val showsNext: Boolean,
    val shift: Float,
)

/**
 * One frame of the now-playing corner's glitch hand-over. Band edges and shifts are fractions of the
 * element's own height and width; [colorSplit] is the colour fringe offset as a fraction of its width.
 */
internal data class GlitchFrame(
    val cover: List<GlitchBand>,
    val artist: List<GlitchBand>,
    val title: List<GlitchBand>,
    val colorSplit: Float,
)

/**
 * The hand-over from one track to the next, from [LEAD_MS] before the track change to [TAIL_MS] after
 * it: glitch bursts grow more frequent and mix old and new slices, the calm frames between them lean
 * more and more to the new track, and the corner settles on it as the window closes.
 */
internal object CornerGlitch {
    const val LEAD_MS = 7_000L
    const val TAIL_MS = 3_000L
    private const val WINDOW_MS = LEAD_MS + TAIL_MS

    // A glitch image holds for a few frames; bursts start and stop on a coarser grid.
    private const val JITTER_MS = 50L
    private const val BURST_MS = 250L
    private const val SETTLED = 0.97f

    /** How far the hand-over is, given the time since the track change (negative before it). */
    fun progress(msFromChange: Long): Float = ((msFromChange + LEAD_MS).toFloat() / WINDOW_MS).coerceIn(0f, 1f)

    fun frame(
        progress: Float,
        clockMs: Long,
    ): GlitchFrame {
        val p = progress.coerceIn(0f, 1f)
        if (p >= SETTLED) return steady(showsNext = true)
        val slot = Random(clockMs / BURST_MS)
        val nextBias = smoothstep(0.35f, 0.95f, p)
        val calmShowsNext = slot.nextFloat() < nextBias * nextBias
        if (slot.nextFloat() >= burstChance(p)) return steady(calmShowsNext)

        val random = Random(clockMs / JITTER_MS * 7919 + 17)
        val intensity = 0.3f + 0.7f * p
        val mix = 0.35f + 0.5f * nextBias
        return GlitchFrame(
            cover = bands(random, count = 2 + random.nextInt(4), mix = mix, maxShift = 0.06f * intensity),
            artist = bands(random, count = 1 + random.nextInt(2), mix = mix, maxShift = 0.04f * intensity),
            title = bands(random, count = 1 + random.nextInt(2), mix = mix, maxShift = 0.04f * intensity),
            colorSplit = random.nextFloat() * 0.012f * intensity,
        )
    }

    fun steady(showsNext: Boolean): GlitchFrame {
        val whole = listOf(GlitchBand(top = 0f, bottom = 1f, showsNext = showsNext, shift = 0f))
        return GlitchFrame(cover = whole, artist = whole, title = whole, colorSplit = 0f)
    }

    private fun burstChance(p: Float): Float = 0.08f + 0.6f * p * p

    private fun bands(
        random: Random,
        count: Int,
        mix: Float,
        maxShift: Float,
    ): List<GlitchBand> {
        val cuts = (List(count - 1) { random.nextFloat() } + 0f + 1f).sorted()
        return cuts.zipWithNext { top, bottom ->
            GlitchBand(
                top = top,
                bottom = bottom,
                showsNext = random.nextFloat() < mix,
                shift = if (random.nextFloat() < 0.6f) (random.nextFloat() * 2f - 1f) * maxShift else 0f,
            )
        }
    }

    private fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
