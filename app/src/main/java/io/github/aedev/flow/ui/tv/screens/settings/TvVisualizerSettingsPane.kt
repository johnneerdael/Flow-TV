package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.tv.components.TvToggleRow

@Composable
fun TvVisualizerSettingsPane(
    modifier: Modifier = Modifier,
    viewModel: TvVisualizerSettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

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
        }
    }
}
