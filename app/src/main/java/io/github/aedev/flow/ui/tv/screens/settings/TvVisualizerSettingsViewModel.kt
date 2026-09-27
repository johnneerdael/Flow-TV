package io.github.aedev.flow.ui.tv.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The TV settings' Visualizations category. */
@HiltViewModel
class TvVisualizerSettingsViewModel
    @Inject
    constructor(
        private val preferences: VisualizerPreferences,
        engine: VisualizerEngine,
    ) : ViewModel() {
        val supported = engine.isSupported
        val enabled: StateFlow<Boolean> =
            preferences.enabled.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                preferences.enabledByDefault,
            )

        fun setEnabled(enabled: Boolean) {
            viewModelScope.launch { preferences.setEnabled(enabled) }
        }
    }
