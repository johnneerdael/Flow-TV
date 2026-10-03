package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistMirrorCoordinatorTest {
    @Test
    fun `expired destination sign-in updates the visible account state`() =
        runTest {
            val runner = mockk<PlaylistMirrorRunner>()
            val accounts = mockk<PluginAccounts>(relaxed = true)
            coEvery { runner.prepare(any(), any(), any(), any(), any()) } throws
                PluginCallException("target", PluginError(PluginErrorCode.SIGN_IN_EXPIRED, "Expired"))
            val coordinator =
                PlaylistMirrorCoordinator(runner, mockk(), mockk(), accounts, MirrorExecutionGate())
            val key = MirrorKey("source", "a", "target", "b", EntityRef(EntityKind.PLAYLIST, "playlist"))
            val failure = runCatching { coordinator.prepare(key, "Playlist") }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            verify(exactly = 1) { accounts.expired("target") }
        }

    @Test
    fun `stopping background consumer retains preparation shared by foreground`() =
        runTest {
            val runner = mockk<PlaylistMirrorRunner>()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
            }
            val coordinator =
                PlaylistMirrorCoordinator(
                    runner,
                    mockk<PlaylistMirrorStore>(),
                    mockk<PluginRegistry>(),
                    mockk<PluginAccounts>(),
                    MirrorExecutionGate(),
                )
            val key = MirrorKey("source", "a", "target", "b", EntityRef(EntityKind.PLAYLIST, "playlist"))
            val background = async { coordinator.prepare(key, "Playlist", true) }
            started.await()
            val foreground = async { coordinator.prepare(key, "Playlist") }
            runCurrent()
            background.cancelAndJoin()
            finish.complete(MirrorRecord(key, "Playlist", "r1", emptyList(), ready = true))
            assertThat(foreground.await().ready).isTrue()
            coVerify(exactly = 1) { runner.prepare(any(), any(), any(), any(), any()) }
        }

    private fun plugin(
        id: String,
        source: Boolean = false,
    ) = InstalledPlugin(
        manifest =
            PluginManifest(
                format = 1,
                api = ApiRange(3, 3),
                id = id,
                name = id,
                version = "1",
                versionCode = 1,
                roles =
                    Roles(
                        metadata =
                            MetadataRole(
                                emptySet(),
                                setOf(EntityKind.PLAYLIST),
                                id,
                                personalCollections = source,
                                privatePlaylistImport = !source,
                            ),
                        audio = if (source) null else AudioRole(setOf(id)),
                    ),
            ),
        signerFingerprint = "",
        sourceUrl = "",
        installedAtMs = 0,
        grantedNetwork = emptyList(),
        grantedBrowser = emptyList(),
    )

    @Test
    fun `mirror selection reacts to pairs accounts and compatible installed targets without preparing`() =
        runTest {
            val source = plugin("source", source = true)
            val target = plugin("target")
            val installed = MutableStateFlow(PluginRegistryState(listOf(source, target)))
            val accountStates =
                MutableStateFlow<Map<String, ProviderAccount>>(
                    mapOf("source" to ProviderAccount.SignedIn("a"), "target" to ProviderAccount.SignedIn("b")),
                )
            val pairs = MutableStateFlow(setOf(PlaylistMirrorStore.pairId("source", "target")))
            val runner = mockk<PlaylistMirrorRunner>()
            val coordinator =
                PlaylistMirrorCoordinator(
                    runner,
                    mockk { every { enabledPairs } returns pairs },
                    mockk { every { state } returns installed },
                    mockk { every { accounts } returns accountStates },
                    MirrorExecutionGate(),
                )
            val playlist = EntityRef(EntityKind.PLAYLIST, "playlist")
            val key = MirrorKey("source", "a", "target", "b", playlist)
            val emitted = mutableListOf<MirrorKey?>()
            backgroundScope.launch { coordinator.observeSelectedKey("source", playlist).collect { emitted += it } }
            runCurrent()
            assertThat(emitted).containsExactly(key)
            assertThat(coordinator.selectedKey("source", playlist)).isEqualTo(key)

            pairs.value = emptySet()
            runCurrent()
            assertThat(emitted.last()).isNull()
            assertThat(coordinator.selectedKey("source", playlist)).isNull()
            pairs.value = setOf(PlaylistMirrorStore.pairId("source", "target"))
            runCurrent()
            assertThat(emitted.last()).isEqualTo(key)

            accountStates.value = accountStates.value + ("source" to ProviderAccount.Anonymous)
            runCurrent()
            assertThat(emitted.last()).isNull()
            accountStates.value = accountStates.value + ("source" to ProviderAccount.SignedIn("new-source"))
            runCurrent()
            val changedSource = key.copy(sourceAccount = "new-source")
            assertThat(emitted.last()).isEqualTo(changedSource)
            accountStates.value = accountStates.value + ("target" to ProviderAccount.Expired)
            runCurrent()
            assertThat(emitted.last()).isNull()
            accountStates.value = accountStates.value + ("target" to ProviderAccount.SignedIn("new-target"))
            runCurrent()
            val changedAccounts = changedSource.copy(targetAccount = "new-target")
            assertThat(emitted.last()).isEqualTo(changedAccounts)

            installed.value = PluginRegistryState(listOf(source))
            runCurrent()
            assertThat(emitted.last()).isNull()
            installed.value = PluginRegistryState(listOf(source, target.copy(enabled = false)))
            runCurrent()
            assertThat(emitted.last()).isNull()
            assertThat(coordinator.selectedKey("source", playlist)).isNull()
            installed.value = PluginRegistryState(listOf(source, target))
            runCurrent()
            assertThat(emitted.last()).isEqualTo(changedAccounts)
            accountStates.value = accountStates.value + ("unrelated" to ProviderAccount.Anonymous)
            runCurrent()

            assertThat(emitted).containsExactly(key, null, key, null, changedSource, null, changedAccounts, null, changedAccounts).inOrder()
            coVerify(exactly = 0) { runner.prepare(any(), any(), any(), any(), any()) }
        }
}
