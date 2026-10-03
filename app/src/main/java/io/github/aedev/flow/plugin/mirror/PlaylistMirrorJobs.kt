package io.github.aedev.flow.plugin.mirror

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistMirrorJobs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: PlaylistMirrorStore,
        private val coordinator: PlaylistMirrorCoordinator,
        private val accounts: PluginAccounts,
        private val registry: PluginRegistry,
    ) {
        fun start(scope: CoroutineScope) {
            scope.launch {
                var previous = emptySet<String>()
                combine(store.enabledPairs, accounts.accounts, registry.state) { pairs, _, _ ->
                    pairs.mapNotNull { pair ->
                        val parts = pair.split('|')
                        if (parts.size != 2) return@mapNotNull null
                        coordinator.key(
                            parts[0],
                            parts[1],
                            nl.neerdael.milkbeat.catalog
                                .EntityRef(nl.neerdael.milkbeat.catalog.EntityKind.PLAYLIST, "owned"),
                        )
                    }
                }.distinctUntilChanged().collect { keys ->
                    coordinator.cancelObsolete()
                    val work = WorkManager.getInstance(context)
                    val current = keys.mapTo(mutableSetOf()) { it.id }
                    (previous - current).forEach { work.cancelAllWorkByTag("mirror:$it") }
                    (current - previous).forEach { id ->
                        val key = keys.first { it.id == id }
                        val input =
                            workDataOf(
                                "source" to key.sourcePlugin,
                                "sourceAccount" to key.sourceAccount,
                                "target" to key.targetPlugin,
                                "targetAccount" to key.targetAccount,
                            )
                        val constraints =
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(
                                    NetworkType.CONNECTED,
                                ).setRequiresBatteryNotLow(true)
                                .build()
                        work.enqueueUniqueWork(
                            "mirror-now:$id",
                            ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<PlaylistMirrorWorker>()
                                .setInputData(input)
                                .setConstraints(constraints)
                                .addTag("mirror:$id")
                                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                                .build(),
                        )
                        work.enqueueUniquePeriodicWork(
                            "mirror-refresh:$id",
                            ExistingPeriodicWorkPolicy.KEEP,
                            PeriodicWorkRequestBuilder<PlaylistMirrorWorker>(6, TimeUnit.HOURS)
                                .setInitialDelay(6, TimeUnit.HOURS)
                                .setInputData(input)
                                .setConstraints(constraints)
                                .addTag("mirror:$id")
                                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                                .build(),
                        )
                    }
                    previous = current
                }
            }
            scope.launch {
                combine(store.enabledPairs, registry.state) { pairs, registry ->
                    pairs to
                        registry.plugins.map { it.id to it.manifest.versionCode }
                }.distinctUntilChanged()
                    .collect { (pairs, _) ->
                        pairs.flatMap { it.split('|') }.distinct().forEach { id ->
                            if (registry.state.value.plugin(id) != null) runCatching { accounts.refresh(id) }
                        }
                    }
            }
        }
    }
