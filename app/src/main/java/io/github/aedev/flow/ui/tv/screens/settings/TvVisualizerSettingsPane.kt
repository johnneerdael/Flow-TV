package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.components.TvSelectionRow
import io.github.aedev.flow.ui.tv.components.TvToggleRow

// Ahead of the player's own timing for audio stacks that report it late, behind it for the rare early one.
private val VISUALIZER_TIMING_OPTIONS_MS = listOf(-100, -50, -25, 0, 25, 50, 75, 100, 150, 200)

@Composable
fun TvVisualizerSettingsPane(
    modifier: Modifier = Modifier,
    viewModel: TvVisualizerSettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val timingOffsetMs by viewModel.timingOffsetMs.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "visualizer-enabled") {
            TvToggleRow(
                label = stringResource(R.string.visualizer_enabled),
                supportingText =
                    stringResource(
                        if (viewModel.supported) R.string.visualizer_enabled_subtitle else R.string.visualizer_unavailable,
                    ),
                checked = enabled && viewModel.supported,
                onCheckedChange = { if (viewModel.supported) viewModel.setEnabled(it) },
            )
        }
        if (enabled && viewModel.supported) {
            item(key = "visualizer-diagnostics") {
                TvToggleRow(
                    label = stringResource(R.string.visualizer_diagnostics),
                    supportingText = stringResource(R.string.visualizer_diagnostics_subtitle),
                    checked = diagnostics,
                    onCheckedChange = viewModel::setDiagnostics,
                )
            }
            item(key = "visualizer-timing-header") {
                TvSectionHeader(
                    title = stringResource(R.string.visualizer_timing),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            item(key = "visualizer-timing-hint") {
                Text(
                    text = stringResource(R.string.visualizer_timing_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(VISUALIZER_TIMING_OPTIONS_MS, key = { "visualizer-timing-$it" }) { offsetMs ->
                TvSelectionRow(
                    label = stringResource(R.string.visualizer_timing_value, offsetMs),
                    selected = offsetMs == timingOffsetMs,
                    onClick = { viewModel.setTimingOffsetMs(offsetMs) },
                )
            }
        }
    }
}
