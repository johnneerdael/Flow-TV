package io.github.aedev.flow.ui.tv.music

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the visualizer hears, in the words ProjectM-TV's meter uses. */
internal enum class VisualizerAudioState { NO_SOUND, VERY_QUIET, LISTENING }

private const val QUIET_LEVEL = 0.02f
private const val METER_GAIN = 1.6f
private const val METER_DECAY = 0.85f

internal fun visualizerAudioState(level: Float): VisualizerAudioState =
    when {
        level <= 0f -> VisualizerAudioState.NO_SOUND
        level < QUIET_LEVEL -> VisualizerAudioState.VERY_QUIET
        else -> VisualizerAudioState.LISTENING
    }

/**
 * The meter's next fill for an RMS [level], like a VU meter: it rises at once and falls back gently.
 * Music usually sits at 0.05-0.3 RMS; the square root spreads the quiet end over the bar.
 */
internal fun meterFill(
    level: Float,
    shown: Float,
): Float {
    val target = min(1f, sqrt(max(0f, level)) * METER_GAIN)
    return if (target >= shown) target else max(target, shown * METER_DECAY)
}

/** One reading of the engine for the diagnostics line, as ProjectM-TV's diagnostics panel shows it. */
internal data class VisualizerDiagnostics(
    val fps: Float,
    val targetFps: Int,
    val width: Int,
    val height: Int,
    val autoResolution: Boolean,
    val lightweightTransition: Boolean,
    val blendPercent: Int,
    val preset: String,
    val audioLevel: Float,
)

/** The preset's file name without its folder and ".milk". */
internal fun presetDisplayName(path: String): String = path.substringAfterLast('/').removeSuffix(".milk")
