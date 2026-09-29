package io.github.aedev.flow.ui.tv.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.update.AutoUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Automatic updates on TV, shared by the app shell (checks and the ready notice) and Settings > About. */
@HiltViewModel
class TvUpdatesViewModel
    @Inject
    constructor(
        private val autoUpdater: AutoUpdater,
    ) : ViewModel() {
        val isAvailable: Boolean = autoUpdater.isAvailable

        val automatic: StateFlow<Boolean> = autoUpdater.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        /** The version whose download just became ready, once per release. */
        val readyToInstall: Flow<String> = autoUpdater.readyToAnnounce.map { it.version }

        fun markAnnounced(version: String) {
            viewModelScope.launch { autoUpdater.markAnnounced(version) }
        }

        fun setAutomatic(enabled: Boolean) {
            viewModelScope.launch { autoUpdater.setEnabled(enabled) }
        }

        suspend fun checkWhileForeground() = autoUpdater.checkWhileForeground()
    }
