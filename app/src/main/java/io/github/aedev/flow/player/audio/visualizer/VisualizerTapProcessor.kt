package io.github.aedev.flow.player.audio.visualizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * A pass-through stage in the music player's audio sink that copies the audio, mixed down to mono,
 * into [tap] while a visualizer listens. The sound itself is never changed.
 *
 * One instance per player, like the equalizer beside it.
 */
@UnstableApi
class VisualizerTapProcessor(
    private val tap: VisualizerAudioTap,
) : BaseAudioProcessor() {
    private var mono = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        tap.timeline.begin(streamMetadata.periodUid, streamMetadata.positionOffsetUs, inputAudioFormat.sampleRate)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (tap.isListening) capture(inputBuffer)
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    private fun capture(input: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val sampleBytes = if (float) Float.SIZE_BYTES else Short.SIZE_BYTES
        val frameBytes = sampleBytes * channels
        val frames = input.remaining() / frameBytes
        if (mono.size < frames) mono = ShortArray(frames)
        var at = input.position()
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += if (float) input.getFloat(at) * Short.MAX_VALUE else input.getShort(at).toFloat()
                at += sampleBytes
            }
            mono[frame] = (sum / channels).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
        tap.timeline.write(mono, frames)
    }
}
