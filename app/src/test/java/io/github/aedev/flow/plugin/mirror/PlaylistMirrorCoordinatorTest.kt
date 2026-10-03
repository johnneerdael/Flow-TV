package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
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
}
