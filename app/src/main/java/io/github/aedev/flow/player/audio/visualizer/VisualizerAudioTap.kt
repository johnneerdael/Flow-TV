package io.github.aedev.flow.player.audio.visualizer

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
        private val listening = MutableStateFlow(false)
        private val playing = MutableStateFlow(false)
        private val period = Timeline.Period()
        private val clock = PlaybackClock()

        @Volatile
        private var anchor: PlaybackAnchor? = null

        val isListening: Boolean
            get() = listeners.get() > 0

        /** True while a renderer listens and the player plays: the stretch in which the clock must follow the player. */
        val needsClock: Flow<Boolean> = combine(listening, playing) { listened, played -> listened && played }.distinctUntilChanged()

        fun acquire() {
            listening.value = listeners.incrementAndGet() > 0
        }

        fun release() {
            listening.value = listeners.updateAndGet { (it - 1).coerceAtLeast(0) } > 0
        }

        /**
         * Feeds the playback clock a reading of [player]; call on the player's thread. [jumped] marks a
         * seek or transition, after which earlier readings no longer describe the same timeline.
         */
        fun anchor(
            player: Player,
            jumped: Boolean,
        ) {
            val playerTimeline = player.currentTimeline
            anchor =
                clock.read(
                    stream = if (playerTimeline.isEmpty) null else playerTimeline.getPeriod(player.currentPeriodIndex, period, true).uid,
                    positionUs = player.currentPosition * 1_000L,
                    realtimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
                    speed = player.playbackParameters.speed,
                    playing = player.isPlaying,
                    jumped = jumped,
                )
            playing.value = player.isPlaying
        }

        /**
         * Fills [out] with the mono samples leading up to what is audible [leadUs] from now, the moment
         * the frame being drawn reaches the screen. False while nothing is playing or the samples are not
         * at hand; the caller then treats the moment as silence.
         */
        fun readAudible(
            out: ShortArray,
            leadUs: Long,
        ): Boolean {
            val current = anchor?.takeIf { it.playing } ?: return false
            return timeline.read(current.stream, current.positionAt(SystemClock.elapsedRealtimeNanos() / 1_000L) + leadUs, out)
        }

        private companion object {
            // About 2.7 s at 48 kHz: several times any output latency, small enough to stay in cache.
            const val CAPACITY_SAMPLES = 1 shl 17
        }
    }
