package io.github.aedev.flow.ui.tv.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.install.PendingInstall
import io.github.aedev.flow.plugin.install.PluginInstallException
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.install.PluginLinks
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import javax.inject.Inject

/** Where adding a plugin stands: nothing, fetching it, waiting for consent, or failed. */
sealed interface AddPluginState {
    data object Idle : AddPluginState

    data object Fetching : AddPluginState

    data class Consent(
        val pending: PendingInstall,
    ) : AddPluginState

    data class Failed(
        val message: String,
    ) : AddPluginState
}

data class TvPluginsState(
    val plugins: List<InstalledPlugin> = emptyList(),
    val selection: ProviderSelection = ProviderSelection(),
    val accounts: Map<String, ProviderAccount> = emptyMap(),
    val adding: AddPluginState = AddPluginState.Idle,
)

/** Settings, Plugins: what is installed, which plugin provides what, adding, signing in and removing. */
@HiltViewModel
class TvPluginsViewModel
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val installer: PluginInstaller,
        private val accounts: PluginAccounts,
        links: PluginLinks,
    ) : ViewModel() {
        private val adding = MutableStateFlow<AddPluginState>(AddPluginState.Idle)

        val state: StateFlow<TvPluginsState> =
            combine(registry.state, accounts.accounts, adding) { registryState, known, add ->
                TvPluginsState(registryState.plugins, registryState.selection, known, add)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvPluginsState())

        init {
            viewModelScope.launch { links.pending.filterNotNull().collect { url -> fetch(url, links) } }
            viewModelScope.launch {
                registry.state.value.plugins
                    .filter { it.manifest.signIn.isNotEmpty() }
                    .forEach { runCatching { accounts.refresh(it.id) } }
            }
        }

        fun fetch(url: String) = fetch(url, links = null)

        private fun fetch(
            url: String,
            links: PluginLinks?,
        ) {
            links?.consume()
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return
            adding.value = AddPluginState.Fetching
            viewModelScope.launch {
                adding.value =
                    try {
                        AddPluginState.Consent(installer.fetch(trimmed))
                    } catch (e: PluginInstallException) {
                        AddPluginState.Failed(e.message ?: "Could not get the plugin")
                    }
            }
        }

        fun install() {
            val consent = adding.value as? AddPluginState.Consent ?: return
            viewModelScope.launch {
                adding.value =
                    try {
                        val installed = installer.install(consent.pending)
                        if (installed.manifest.signIn.isNotEmpty()) runCatching { accounts.refresh(installed.id) }
                        AddPluginState.Idle
                    } catch (e: IllegalStateException) {
                        AddPluginState.Failed(e.message ?: "Could not install the plugin")
                    }
            }
        }

        fun cancelAdd() {
            adding.value = AddPluginState.Idle
        }

        fun remove(id: String) {
            viewModelScope.launch { registry.remove(id) }
        }

        fun select(selection: ProviderSelection) {
            viewModelScope.launch { registry.select(selection) }
        }

        fun signOut(id: String) {
            viewModelScope.launch {
                try {
                    accounts.signOut(id)
                } catch (e: PluginCallException) {
                    adding.value = AddPluginState.Failed(e.error.userMessage ?: e.error.message)
                }
            }
        }
    }
