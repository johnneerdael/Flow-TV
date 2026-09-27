package io.github.aedev.flow.player.audio.visualizer

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The music player's audio as a visualizer hears it: the player's sink writes into it through
 * [VisualizerTapProcessor], and a renderer reads the samples that are audible right now.
 *
 * Nothing is recorded until a renderer [acquire]s the tap, and recording stops at the last [release].
 */
@Singleton
class VisualizerAudioTap
    @Inject
    constructor() {
        internal val timeline = PcmTimeline(CAPACITY_SAMPLES)
        private val listeners = AtomicInteger()
        private val period = Timeline.Period()

        @Volatile
        private var anchor: PlaybackAnchor? = null

        val isListening: Boolean
            get() = listeners.get() > 0

        fun acquire() {
            listeners.incrementAndGet()
        }

        fun release() {
            listeners.updateAndGet { (it - 1).coerceAtLeast(0) }
        }

        /** Re-anchors the playback clock; call on the player's thread whenever playback jumps or changes pace. */
        fun anchor(player: Player) {
            val playerTimeline = player.currentTimeline
            anchor =
                PlaybackAnchor(
                    stream = if (playerTimeline.isEmpty) null else playerTimeline.getPeriod(player.currentPeriodIndex, period, true).uid,
                    positionUs = player.currentPosition * 1_000L,
                    realtimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
                    speed = player.playbackParameters.speed,
                    playing = player.isPlaying,
                )
        }

        /**
         * Fills [out] with the mono samples leading up to what is audible now. False while nothing is
         * playing or the samples are not at hand; the caller then treats the moment as silence.
         */
        fun readAudible(out: ShortArray): Boolean {
            val current = anchor?.takeIf { it.playing } ?: return false
            return timeline.read(current.stream, current.positionAt(SystemClock.elapsedRealtimeNanos() / 1_000L), out)
        }

        private companion object {
            // About 2.7 s at 48 kHz: several times any output latency, small enough to stay in cache.
            const val CAPACITY_SAMPLES = 1 shl 17
        }
    }
