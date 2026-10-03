package io.github.aedev.flow.plugin.mirror

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistMirrorJobsTest {
    @Test
    fun `pair removal leaves other schedules intact and plugin replacement immediately refreshes its own pair`() =
        runTest {
            val installed =
                MutableStateFlow(PluginRegistryState(listOf(plugin("source-a", true), plugin("source-b", true), plugin("target"))))
            val known =
                MutableStateFlow<Map<String, ProviderAccount>>(
                    mapOf(
                        "source-a" to ProviderAccount.SignedIn("a"),
                        "source-b" to ProviderAccount.SignedIn("b"),
                        "target" to ProviderAccount.SignedIn("t"),
                    ),
                )
            val enabled =
                MutableStateFlow(setOf(PlaylistMirrorStore.pairId("source-a", "target"), PlaylistMirrorStore.pairId("source-b", "target")))
            val store = mockk<PlaylistMirrorStore> { every { enabledPairs } returns enabled }
            val registry = mockk<PluginRegistry> { every { state } returns installed }
            val accounts = mockk<PluginAccounts> { every { this@mockk.accounts } returns known }
            coEvery { accounts.refresh(any()) } coAnswers { known.value.getValue(firstArg()) }
            val coordinator = PlaylistMirrorCoordinator(mockk(), store, registry, accounts, MirrorExecutionGate())
            val a = coordinator.key("source-a", "target", EntityRef(EntityKind.PLAYLIST, "owned"))!!
            val b = coordinator.key("source-b", "target", EntityRef(EntityKind.PLAYLIST, "owned"))!!
            val work = mockk<WorkManager>(relaxed = true)
            mockkObject(WorkManager.Companion)
            try {
                every { WorkManager.getInstance(any<Context>()) } returns work
                PlaylistMirrorJobs(mockk(), store, coordinator, accounts, registry).start(backgroundScope)
                runCurrent()
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${a.id}", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${b.id}", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }

                installed.value =
                    installed.value.copy(
                        plugins =
                            installed.value.plugins.map {
                                if (it.id == "source-a") plugin("source-a", true) else it
                            },
                    )
                runCurrent()
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${a.id}", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${b.id}", any(), any<OneTimeWorkRequest>()) }
                verify(exactly = 1) { work.enqueueUniquePeriodicWork("mirror-refresh:${b.id}", ExistingPeriodicWorkPolicy.KEEP, any()) }

                enabled.value = setOf(PlaylistMirrorStore.pairId("source-b", "target"))
                runCurrent()
                verify(exactly = 1) { work.cancelAllWorkByTag("mirror:${a.id}") }
                verify(exactly = 0) { work.cancelAllWorkByTag("mirror:${b.id}") }
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${b.id}", any(), any<OneTimeWorkRequest>()) }
                verify(exactly = 1) { work.enqueueUniquePeriodicWork("mirror-refresh:${b.id}", ExistingPeriodicWorkPolicy.KEEP, any()) }
            } finally {
                unmockkObject(WorkManager.Companion)
            }
        }

    private fun plugin(
        id: String,
        source: Boolean = false,
    ): InstalledPlugin =
        mockk<InstalledPlugin>(relaxed = true) {
            every { this@mockk.id } returns id
            every { enabled } returns true
            every { manifest.roles.metadata } returns
                MetadataRole(
                    emptySet(),
                    setOf(EntityKind.PLAYLIST),
                    id,
                    personalCollections = source,
                    privatePlaylistImport = !source,
                )
            every { manifest.roles.audio } returns if (source) null else AudioRole(setOf(id))
        }
}
