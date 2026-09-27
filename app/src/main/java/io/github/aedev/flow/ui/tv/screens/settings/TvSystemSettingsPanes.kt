package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.AppUiModePreferences
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.platform.AppUiMode
import io.github.aedev.flow.player.DeepFlowManager
import io.github.aedev.flow.ui.tv.components.TvSelectionRow
import io.github.aedev.flow.ui.tv.components.TvToggleRow
import kotlinx.coroutines.launch

@Composable
fun TvFlowEngineSettingsPane(
    playerPreferences: PlayerPreferences,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deepFlowActive by playerPreferences.deepFlowActive.collectAsStateWithLifecycle(initialValue = false)
    val saveToHistory by playerPreferences.deepFlowSaveToHistory.collectAsStateWithLifecycle(initialValue = true)

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvToggleRow(
            label = stringResource(R.string.deep_flow_mode_title),
            supportingText = stringResource(R.string.deep_flow_mode_subtitle),
            checked = deepFlowActive,
            onCheckedChange = { enabled ->
                scope.launch { DeepFlowManager.setEnabled(context.applicationContext, enabled) }
            },
        )
        TvToggleRow(
            label = stringResource(R.string.deep_flow_save_history_title),
            supportingText = stringResource(R.string.deep_flow_save_history_subtitle),
            checked = saveToHistory,
            onCheckedChange = { enabled ->
                scope.launch { playerPreferences.setDeepFlowSaveToHistory(enabled) }
            },
        )
    }
}
