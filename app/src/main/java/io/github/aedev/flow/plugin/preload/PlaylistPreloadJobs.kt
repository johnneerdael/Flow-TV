package io.github.aedev.flow.plugin.preload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.catalog.ProviderAccount
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class PlaylistPreloadJobs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
    ) {
        private val work: WorkManager get() = WorkManager.getInstance(context)

        fun observe(pluginId: String): Flow<WorkInfo?> =
            work.getWorkInfosForUniqueWorkFlow(preloadWorkName(pluginId)).map { values -> values.lastOrNull() }

        fun start(pluginId: String) {
            val account = accounts.accounts.value[pluginId] as? ProviderAccount.SignedIn ?: return
            val request =
                OneTimeWorkRequestBuilder<PlaylistPreloadWorker>()
                    .setInputData(
                        workDataOf(
                            PRELOAD_PLUGIN to pluginId,
                            PRELOAD_ACCOUNT to account.key,
                            PRELOAD_AUDIO to
                                registry.state.value.selection.audio
                                    .toTypedArray(),
                        ),
                    ).setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            work.enqueueUniqueWork(preloadWorkName(pluginId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(pluginId: String) {
            work.cancelUniqueWork(preloadWorkName(pluginId))
        }
    }
