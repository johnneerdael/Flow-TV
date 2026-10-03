package io.github.aedev.flow.plugin.mirror

import android.util.Log
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistMirrorCoordinator
    @Inject
    constructor(
        private val runner: PlaylistMirrorRunner,
        val store: PlaylistMirrorStore,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val gate: MirrorExecutionGate,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO)

        private class Preparation(
            val job: Deferred<MirrorRecord>,
            var consumers: Int = 0,
        )

        private val tasks = mutableMapOf<String, Preparation>()
        private val states = ConcurrentHashMap<String, MutableStateFlow<PlaylistMirrorState>>()

        fun state(key: MirrorKey): StateFlow<PlaylistMirrorState> = states.getOrPut(key.id) { MutableStateFlow(PlaylistMirrorState()) }

        fun available(
            source: String,
            target: String,
        ): Boolean =
            source != target && registry.state.value
                .plugin(source)
                ?.manifest
                ?.roles
                ?.metadata
                ?.personalCollections == true &&
                registry.state.value
                    .plugin(target)
                    ?.manifest
                    ?.roles
                    ?.metadata
                    ?.privatePlaylistImport == true &&
                registry.state.value
                    .plugin(target)
                    ?.manifest
                    ?.roles
                    ?.audio != null &&
                accounts.accounts.value[source] is ProviderAccount.SignedIn && accounts.accounts.value[target] is ProviderAccount.SignedIn

        fun key(
            source: String,
            target: String,
            entity: EntityRef,
        ): MirrorKey? {
            if (!available(source, target) || entity.kind != EntityKind.PLAYLIST) return null
            return MirrorKey(
                source,
                (accounts.accounts.value[source] as ProviderAccount.SignedIn).key,
                target,
                (accounts.accounts.value[target] as ProviderAccount.SignedIn).key,
                entity,
            )
        }

        suspend fun selectedKey(
            source: String,
            entity: EntityRef,
        ): MirrorKey? =
            registry.state.value.plugins.firstNotNullOfOrNull { target ->
                if (PlaylistMirrorStore.pairId(source, target.id) in store.enabledPairs.first()) key(source, target.id, entity) else null
            }

        private suspend fun awaitPreparation(
            key: MirrorKey,
            title: String,
            background: Boolean,
            artwork: Artwork?,
        ): MirrorRecord {
            val preparation =
                synchronized(tasks) {
                    val active =
                        tasks[key.id]?.takeIf { it.job.isActive } ?: Preparation(
                            scope.async {
                                val flow = states.getOrPut(key.id) { MutableStateFlow(PlaylistMirrorState()) }
                                var latest = PlaylistMirrorState(isPreparing = true)
                                var lastPublishedMs = 0L
                                flow.value = latest
                                try {
                                    runner.prepare(key, title, { progress ->
                                        latest = progress.copy(isPreparing = !progress.ready)
                                        val now =
                                            java.util.concurrent.TimeUnit.NANOSECONDS
                                                .toMillis(System.nanoTime())
                                        if (latest.ready || now - lastPublishedMs >= 1_000L) {
                                            flow.value = latest
                                            lastPublishedMs = now
                                        }
                                    }, background, artwork)
                                } catch (e: Exception) {
                                    if (e is PluginCallException && e.error.code == PluginErrorCode.SIGN_IN_EXPIRED) {
                                        accounts.expired(e.pluginId)
                                    }
                                    if (e !is CancellationException) Log.w("PlaylistMirror", "Preparation failed", e)
                                    flow.value =
                                        latest.copy(isPreparing = false, error = e.message.takeUnless { e is CancellationException })
                                    throw e
                                }
                            },
                        ).also { tasks[key.id] = it }
                    active.consumers++
                    active
                }
            return try {
                preparation.job.await()
            } finally {
                synchronized(tasks) {
                    preparation.consumers--
                    if (preparation.consumers == 0) {
                        if (preparation.job.isActive) preparation.job.cancel()
                        if (tasks[key.id] === preparation) tasks.remove(key.id)
                    }
                }
            }
        }

        suspend fun prepare(
            key: MirrorKey,
            title: String,
            background: Boolean = false,
            artwork: Artwork? = null,
        ): MirrorRecord =
            if (background) {
                awaitPreparation(key, title, true, artwork)
            } else {
                gate.foreground(key.id) { awaitPreparation(key, title, false, artwork) }
            }

        fun open(
            source: String,
            entity: EntityRef,
            title: String,
            artwork: Artwork? = null,
        ) {
            scope.async { selectedKey(source, entity)?.let { prepare(it, title, artwork = artwork) } }
        }

        fun cancelObsolete() =
            synchronized(tasks) {
                tasks.values.filter { it.job.isActive }.forEach { it.job.cancel() }
            }
    }
