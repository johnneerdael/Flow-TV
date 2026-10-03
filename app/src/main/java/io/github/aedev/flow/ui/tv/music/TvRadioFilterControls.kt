package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.plugin.playback.RadioTuningState
import io.github.aedev.flow.ui.tv.components.TvFilterChip

@Composable
internal fun TvRadioFilterControls(
    state: RadioTuningState,
    onSelect: (String) -> Unit,
) {
    if (state.choices.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.choices.forEach { option ->
            TvFilterChip(option.label, option.id == state.selectedId, { if (!state.loading) onSelect(option.id) }, compact = true)
        }
    }
}
