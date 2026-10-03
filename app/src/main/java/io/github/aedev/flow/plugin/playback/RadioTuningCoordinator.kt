package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioTuningCoordinator
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
    ) {
        private val _state = MutableStateFlow(RadioTuningState())
        val state: StateFlow<RadioTuningState> = _state
        private val _requests = MutableSharedFlow<RadioFilterSelection>(extraBufferCapacity = 1)
        val requests: SharedFlow<RadioFilterSelection> = _requests
        private var page: RadioPage? = null
        private var generation = 0L
        private var epoch: Any? = null

        private fun identity(page: RadioPage): Any = registry.state.value.plugin(page.pluginId) to accounts.accounts.value[page.pluginId]

        fun reset(generation: Long) {
            this.generation = generation
            page = null
            epoch = null
            _state.value = RadioTuningState()
        }

        fun station(
            page: RadioPage?,
            generation: Long,
        ) {
            if (generation != this.generation) return
            this.page = page
            epoch = page?.let(::identity)
            _state.value =
                RadioTuningState(
                    page
                        ?.tracks
                        ?.filters
                        ?.options
                        .orEmpty(),
                    page?.tracks?.selectedFilterId,
                )
        }

        fun valid(selection: RadioFilterSelection): Boolean {
            if (selection.generation != generation) return false
            return selection.epoch == identity(selection.page)
        }

        fun select(id: String) {
            val current = page ?: return
            if (_state.value.loading) return
            if (id == _state.value.selectedId) return
            if (_state.value.choices.none { it.id == id }) return
            if (epoch != identity(current)) {
                reset(generation)
                return
            }
            val request = RadioFilterSelection(id, current, generation, checkNotNull(epoch))
            if (_requests.tryEmit(request)) {
                _state.value = _state.value.copy(loading = true)
            }
        }

        fun failed(selection: RadioFilterSelection) {
            if (valid(selection)) _state.value = _state.value.copy(loading = false)
        }
    }
