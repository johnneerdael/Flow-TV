package io.github.aedev.flow.ui.tv.music

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import kotlinx.coroutines.delay

private val MeterWidth = 96.dp
private val MeterHeight = 4.dp
private val MeterIconSize = 16.dp

// A meter needs about ten readings a second to follow the music; the diagnostics once a second, like
// the engine's own frame-rate sample.
private const val METER_INTERVAL_MS = 100L
private const val DIAGNOSTICS_EVERY = 10

/**
 * The visualizer's line in the controls bar: an audio meter that shows whether it hears the music,
 * and with diagnostics on, its frame rate, render size, transition, audio level and preset. It only
 * polls the engine while composed, which is while the controls are on screen.
 */
@Composable
internal fun TvVisualizerStatus(
    viewModel: TvVisualizerViewModel,
    modifier: Modifier = Modifier,
) {
    val diagnosticsShown by viewModel.diagnosticsShown.collectAsStateWithLifecycle()
    var fill by remember { mutableFloatStateOf(0f) }
    var audio by remember { mutableStateOf(VisualizerAudioState.NO_SOUND) }
    var diagnostics by remember { mutableStateOf<VisualizerDiagnostics?>(null) }
    LaunchedEffect(diagnosticsShown) {
        var tick = 0
        while (true) {
            val level = viewModel.audioLevel()
            fill = meterFill(level, fill)
            audio = visualizerAudioState(level)
            if (diagnosticsShown && tick++ % DIAGNOSTICS_EVERY == 0) diagnostics = viewModel.diagnostics()
            delay(METER_INTERVAL_MS)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(MeterIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { fill },
                modifier = Modifier.width(MeterWidth).height(MeterHeight),
                drawStopIndicator = {},
            )
            Text(
                text = stringResource(audio.label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (diagnosticsShown) diagnostics?.let { DiagnosticsLines(it) }
    }
}

@get:StringRes
private val VisualizerAudioState.label: Int
    get() =
        when (this) {
            VisualizerAudioState.NO_SOUND -> R.string.visualizer_audio_none
            VisualizerAudioState.VERY_QUIET -> R.string.visualizer_audio_quiet
            VisualizerAudioState.LISTENING -> R.string.visualizer_audio_listening
        }

@Composable
private fun DiagnosticsLines(reading: VisualizerDiagnostics) {
    val transition =
        when {
            reading.lightweightTransition -> stringResource(R.string.visualizer_transition_lightweight)
            reading.blendPercent > 0 -> stringResource(R.string.visualizer_transition_blend_at, reading.blendPercent)
            else -> stringResource(R.string.visualizer_transition_blend)
        }
    val resolution =
        stringResource(if (reading.autoResolution) R.string.visualizer_resolution_auto else R.string.visualizer_resolution_fixed)
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text =
            stringResource(
                R.string.visualizer_diagnostics_line,
                reading.fps,
                reading.targetFps,
                reading.width,
                reading.height,
                resolution,
                transition,
                reading.audioLevel,
            ),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(text = reading.preset, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
