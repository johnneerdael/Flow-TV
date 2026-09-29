package io.github.aedev.flow.plugin.catalog

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.WebLoginResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whose account each plugin is serving, as the plugin reports it. Pages cached for another account
 * are stale, so the catalog reloads when an entry changes; a call answering that the sign-in expired
 * marks the account expired here too.
 */
@Singleton
class PluginAccounts
    @Inject
    constructor(
        private val host: PluginHost,
    ) {
        private val _accounts = MutableStateFlow<Map<String, ProviderAccount>>(emptyMap())
        val accounts: StateFlow<Map<String, ProviderAccount>> = _accounts.asStateFlow()

        suspend fun refresh(pluginId: String): ProviderAccount {
            val account =
                try {
                    host.call(pluginId, PluginOperations.account, Unit)
                } catch (e: PluginCallException) {
                    if (e.error.code == PluginErrorCode.UNSUPPORTED) ProviderAccount.Anonymous else throw e
                }
            _accounts.update { it + (pluginId to account) }
            return account
        }

        suspend fun complete(
            pluginId: String,
            result: WebLoginResult,
        ): ProviderAccount {
            val account = host.call(pluginId, PluginOperations.completeSignIn, result)
            _accounts.update { it + (pluginId to account) }
            return account
        }

        suspend fun signOut(pluginId: String) {
            host.call(pluginId, PluginOperations.signOut, Unit)
            _accounts.update { it + (pluginId to ProviderAccount.Anonymous) }
        }

        /** A call said the plugin's sign-in expired; the listener is asked to sign in again. */
        fun expired(pluginId: String) {
            _accounts.update { it + (pluginId to ProviderAccount.Expired) }
        }
    }
