package io.github.aedev.flow.player.audio.visualizer

/**
 * Mono PCM that the player has handed to its audio output, remembered with where in the stream each
 * sample belongs. Decoded audio runs ahead of what is audible by the output's latency; reading by
 * stream position instead of "newest samples" keeps a visualizer on the beat that is actually heard.
 *
 * One writer (the audio thread) and one reader (the renderer); each holds the lock only to copy.
 */
internal class PcmTimeline(
    private val capacity: Int,
) {
    private class Segment(
        val stream: Any?,
        val positionUs: Long,
        val sampleRate: Int,
        val firstSample: Long,
    )

    private val ring = ShortArray(capacity)
    private val segments = ArrayDeque<Segment>()
    private var written = 0L

    // Samples before this index went past while nobody listened and were never copied.
    private var validFrom = 0L

    /** Samples written from now on start at [positionUs] of [stream]; called whenever the output flushes. */
    @Synchronized
    fun begin(
        stream: Any?,
        positionUs: Long,
        sampleRate: Int,
    ) {
        segments.addLast(Segment(stream, positionUs, sampleRate, written))
        trim()
    }

    @Synchronized
    fun write(
        samples: ShortArray,
        count: Int,
    ) {
        if (segments.isEmpty()) return
        var offset = 0
        while (offset < count) {
            val at = (written % capacity).toInt()
            val chunk = minOf(count - offset, capacity - at)
            System.arraycopy(samples, offset, ring, at, chunk)
            offset += chunk
            written += chunk
        }
        trim()
    }

    /**
     * Counts [count] samples that went past without being kept, so positions after them still map to
     * the right place; the skipped stretch reads as unavailable.
     */
    @Synchronized
    fun skip(count: Int) {
        if (segments.isEmpty()) return
        written += count
        validFrom = written
        trim()
    }

    /**
     * Copies the [out].size samples leading up to [positionUs] of [stream] into [out]. False when they
     * were never written or have already been overwritten.
     */
    @Synchronized
    fun read(
        stream: Any?,
        positionUs: Long,
        out: ShortArray,
    ): Boolean {
        val end = sampleAt(stream, positionUs) ?: return false
        val start = end - out.size
        if (start < 0 || start < validFrom || start < written - capacity) return false
        var offset = 0
        while (offset < out.size) {
            val at = ((start + offset) % capacity).toInt()
            val chunk = minOf(out.size - offset, capacity - at)
            System.arraycopy(ring, at, out, offset, chunk)
            offset += chunk
        }
        return true
    }

    private fun sampleAt(
        stream: Any?,
        positionUs: Long,
    ): Long? {
        for (index in segments.indices.reversed()) {
            val segment = segments[index]
            if (segment.stream != stream || positionUs < segment.positionUs) continue
            val limit = segments.getOrNull(index + 1)?.firstSample ?: written
            val sample = segment.firstSample + (positionUs - segment.positionUs) * segment.sampleRate / MICROS_PER_SECOND
            return sample.takeIf { it <= limit }
        }
        return null
    }

    private fun trim() {
        while (segments.size > 1 && (segments[1].firstSample <= written - capacity || segments.size > MAX_SEGMENTS)) {
            segments.removeFirst()
        }
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val MAX_SEGMENTS = 64
    }
}
