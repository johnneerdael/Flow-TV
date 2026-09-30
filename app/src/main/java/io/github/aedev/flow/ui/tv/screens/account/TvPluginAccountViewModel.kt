package io.github.aedev.flow.ui.tv.screens.account

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import javax.inject.Inject

/** The music plugin's account for the Library: its status. Signing in and out happens in Settings, Plugins. */
@HiltViewModel
class TvPluginAccountViewModel
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
    ) : ViewModel() {
        val status: StateFlow<TvAccountStatus> =
            combine(registry.state, accounts.accounts, ::statusOf)
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                    statusOf(registry.state.value, accounts.accounts.value),
                )

        /** Asks the plugin whose account it holds, once, when nothing has asked it yet; no network. */
        fun refresh() {
            val plugin = accountPlugin() ?: return
            if (accounts.accounts.value[plugin] != null) return
            viewModelScope.launch {
                try {
                    accounts.refresh(plugin)
                } catch (e: PluginCallException) {
                    Log.w(TAG, "account of $plugin unknown: ${e.error.message}")
                }
            }
        }

        private fun accountPlugin(): String? =
            registry.state.value
                .let { it.plugin(it.selection.metadata) }
                ?.takeIf { it.manifest.signIn.isNotEmpty() }
                ?.id

        private fun statusOf(
            registryState: PluginRegistryState,
            known: Map<String, ProviderAccount>,
        ): TvAccountStatus {
            val plugin = registryState.plugin(registryState.selection.metadata)
            return tvAccountStatus(plugin?.manifest?.signIn?.isNotEmpty() == true, plugin?.let { known[it.id] })
        }

        private companion object {
            const val TAG = "TvPluginAccount"
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
