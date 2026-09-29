package io.github.aedev.flow.plugin.catalog

import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.PageRequest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperation
import nl.neerdael.milkbeat.plugin.PluginOperations
import javax.inject.Inject
import javax.inject.Singleton

/** No plugin provides music metadata yet; Home offers to add one. */
class NoMetadataPluginException : Exception("No music plugin is selected")

/**
 * The listener's chosen metadata plugin, behind the catalog's [MetadataProvider]: Home and the
 * artist, album and playlist pages come from it. A call that says the sign-in expired marks the
 * account expired, so the pages ask the listener to sign in again.
 */
@Singleton
class PluginMetadataProvider
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
    ) : MetadataProvider,
        CatalogPlayback {
        private val selected: String?
            get() = registry.state.value.selection.metadata

        override val id: String
            get() = selected ?: "none"

        /** Emits again when another plugin is chosen, even if both are signed out, so pages reload. */
        override val account: Flow<ProviderAccount> =
            combine(registry.state.map { it.selection.metadata }.distinctUntilChanged(), accounts.accounts) { plugin, known ->
                plugin to (plugin?.let { known[it] } ?: ProviderAccount.Anonymous)
            }.distinctUntilChanged().map { it.second }

        override suspend fun home(request: HomeRequest): Result<MetadataPage> = call(PluginOperations.home, request)

        override suspend fun page(
            entity: EntityRef,
            cursor: String?,
        ): Result<MetadataPage> = call(PluginOperations.entity, PageRequest(entity, cursor = cursor))

        override fun track(item: MetadataItem): MusicTrack? {
            val plugin = selected ?: return null
            return item.track?.toMusicTrack(plugin)
        }

        /** Calls [operation] on the selected metadata plugin; a failure carries the plugin's reason. */
        suspend fun <Request, Response> call(
            operation: PluginOperation<Request, Response>,
            request: Request,
        ): Result<Response> {
            val plugin = selected ?: return Result.failure(NoMetadataPluginException())
            if (accounts.accounts.value[plugin] == null) runCatching { accounts.refresh(plugin) }
            return try {
                Result.success(host.call(plugin, operation, request))
            } catch (e: PluginCallException) {
                if (e.error.code == PluginErrorCode.SIGN_IN_EXPIRED) accounts.expired(plugin)
                Result.failure(e)
            }
        }
    }

/** What to tell the listener about a failed plugin call: the plugin's own words when it gave some. */
val Throwable.listenerMessage: String?
    get() = (this as? PluginCallException)?.error?.let { it.userMessage ?: it.message } ?: message
