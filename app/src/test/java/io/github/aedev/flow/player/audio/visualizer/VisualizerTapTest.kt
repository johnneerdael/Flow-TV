package io.github.aedev.flow.player.audio.visualizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VisualizerTapTest {
    private val rate = 48_000

    private fun ramp(
        from: Int,
        count: Int,
    ) = ShortArray(count) { (from + it).toShort() }

    @Test
    fun `reads the samples leading up to a stream position`() {
        val timeline = PcmTimeline(capacity = 4_096)
        timeline.begin(stream = "a", positionUs = 1_000_000, sampleRate = rate)
        timeline.write(ramp(0, 960), 960)

        val out = ShortArray(4)
        // 10 ms into the segment is sample 480, so the window is samples 476..479.
        assertThat(timeline.read("a", 1_010_000, out)).isTrue()
        assertThat(out.toList()).containsExactly(476.toShort(), 477.toShort(), 478.toShort(), 479.toShort()).inOrder()
    }

    @Test
    fun `a stream position is found in its own segment even after the next track has started decoding`() {
        val timeline = PcmTimeline(capacity = 4_096)
        timeline.begin(stream = "a", positionUs = 0, sampleRate = rate)
        timeline.write(ramp(0, 480), 480)
        timeline.begin(stream = "b", positionUs = 0, sampleRate = rate)
        timeline.write(ramp(10_000, 480), 480)

        val out = ShortArray(2)
        assertThat(timeline.read("a", 5_000, out)).isTrue()
        assertThat(out.toList()).containsExactly(238.toShort(), 239.toShort()).inOrder()
        assertThat(timeline.read("b", 5_000, out)).isTrue()
        assertThat(out.toList()).containsExactly(10_238.toShort(), 10_239.toShort()).inOrder()
    }

    @Test
    fun `positions not yet written, unknown streams and overwritten samples read as unavailable`() {
        val timeline = PcmTimeline(capacity = 1_000)
        timeline.begin(stream = "a", positionUs = 0, sampleRate = rate)
        timeline.write(ramp(0, 480), 480)
        val out = ShortArray(8)

        assertThat(timeline.read("a", 20_000, out)).isFalse()
        assertThat(timeline.read("z", 5_000, out)).isFalse()

        timeline.write(ramp(480, 2_000), 2_000)
        assertThat(timeline.read("a", 5_000, out)).isFalse()
        assertThat(timeline.read("a", 50_000, out)).isTrue()
        assertThat(out.last()).isEqualTo(2_399.toShort())
    }

    @Test
    fun `samples skipped while nobody listened keep later positions in place and read as unavailable`() {
        val timeline = PcmTimeline(capacity = 4_096)
        timeline.begin(stream = "a", positionUs = 0, sampleRate = rate)
        timeline.write(ramp(0, 480), 480)
        timeline.skip(960)
        timeline.write(ramp(1_440, 480), 480)

        val out = ShortArray(4)
        // 35 ms is sample 1680, written after the gap: the stream position still maps there.
        assertThat(timeline.read("a", 35_000, out)).isTrue()
        assertThat(out.last()).isEqualTo(1_679.toShort())
        // 20 ms is sample 960, inside the skipped stretch.
        assertThat(timeline.read("a", 20_000, out)).isFalse()
    }

    @Test
    fun `reads across the ring's wrap point`() {
        val timeline = PcmTimeline(capacity = 1_000)
        timeline.begin(stream = "a", positionUs = 0, sampleRate = rate)
        timeline.write(ramp(0, 1_200), 1_200)

        val out = ShortArray(400)
        assertThat(timeline.read("a", 25_000, out)).isTrue()
        assertThat(out.first()).isEqualTo(800.toShort())
        assertThat(out.last()).isEqualTo(1_199.toShort())
    }

    @Test
    fun `the anchor moves with real time only while playing, at playback speed`() {
        val playing = PlaybackAnchor(stream = "a", positionUs = 1_000_000, realtimeUs = 50_000_000, speed = 1.5f, playing = true)
        assertThat(playing.positionAt(52_000_000)).isEqualTo(4_000_000)
        assertThat(playing.copy(playing = false).positionAt(52_000_000)).isEqualTo(1_000_000)
    }

    private fun tapProcessor(
        tap: VisualizerAudioTap,
        encoding: Int,
    ) = VisualizerTapProcessor(tap).apply {
        configure(AudioProcessor.AudioFormat(rate, 2, encoding))
        flush(AudioProcessor.StreamMetadata(0))
    }

    @Test
    fun `the processor passes audio through untouched and records it in mono while listened to`() {
        val tap = VisualizerAudioTap().apply { acquire() }
        val processor = tapProcessor(tap, C.ENCODING_PCM_16BIT)
        val stereo = ShortArray(960) { if (it % 2 == 0) 1_000 else 3_000 }
        val input = ByteBuffer.allocateDirect(stereo.size * 2).order(ByteOrder.nativeOrder())
        stereo.forEach(input::putShort)
        input.flip()

        processor.queueInput(input)
        val output = processor.getOutput()
        assertThat(ShortArray(output.remaining() / 2) { output.getShort() }.toList()).isEqualTo(stereo.toList())

        val mono = ShortArray(4)
        assertThat(tap.timeline.read(null, 5_000, mono)).isTrue()
        assertThat(mono.toSet()).containsExactly(2_000.toShort())
    }

    @Test
    fun `float audio is recorded at 16-bit scale`() {
        val tap = VisualizerAudioTap().apply { acquire() }
        val processor = tapProcessor(tap, C.ENCODING_PCM_FLOAT)
        val input = ByteBuffer.allocateDirect(960 * 4).order(ByteOrder.nativeOrder())
        repeat(480) {
            input.putFloat(0.5f)
            input.putFloat(-0.25f)
        }
        input.flip()

        processor.queueInput(input)
        val mono = ShortArray(2)
        assertThat(tap.timeline.read(null, 5_000, mono)).isTrue()
        assertThat(mono.toSet()).containsExactly((0.125f * Short.MAX_VALUE).toInt().toShort())
    }

    @Test
    fun `nothing is recorded while no one listens`() {
        val tap = VisualizerAudioTap()
        val processor = tapProcessor(tap, C.ENCODING_PCM_16BIT)
        val input = ByteBuffer.allocateDirect(960 * 2).order(ByteOrder.nativeOrder())
        repeat(960) { input.putShort(1_000) }
        input.flip()

        processor.queueInput(input)
        assertThat(tap.timeline.read(null, 5_000, ShortArray(4))).isFalse()
    }
}
