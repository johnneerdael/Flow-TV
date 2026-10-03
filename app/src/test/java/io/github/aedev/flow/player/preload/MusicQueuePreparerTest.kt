package io.github.aedev.flow.player.preload

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.QueuePreparationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicQueuePreparerTest {
    private fun target(id: String) = QueuePreparationTarget(id, "music://$id")

    @Test
    fun `prepares at most 100 entries sequentially`() =
        runTest {
            val targets = (1..120).map { target("$it") }
            val calls = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()
            val preparer =
                MusicQueuePreparer(backgroundScope, { targets }, {
                    calls += it.uri
                    if (calls.size == 1) gate.await()
                    QueuePreparationResult.Ready
                }, {})
            preparer.schedule()
            runCurrent()
            assertThat(calls).containsExactly("music://1")
            preparer.schedule()
            runCurrent()
            assertThat(calls).hasSize(1)
            gate.complete(Unit)
            runCurrent()
            assertThat(calls).containsExactlyElementsIn(targets.take(100).map { it.uri }).inOrder()
        }

    @Test
    fun `removes confirmed misses and keeps temporary failures`() =
        runTest {
            var targets = listOf(target("missing"), target("offline"), target("ready"))
            val removed = mutableListOf<QueuePreparationTarget>()
            val calls = mutableListOf<String>()
            val preparer =
                MusicQueuePreparer(backgroundScope, { targets }, {
                    calls += it.uri
                    when (it.uid) {
                        "missing" -> QueuePreparationResult.Unmatched()
                        "offline" -> QueuePreparationResult.Retryable
                        else -> QueuePreparationResult.Ready
                    }
                }, {
                    removed += it
                    targets = targets - it
                })
            preparer.schedule()
            runCurrent()
            assertThat(removed).containsExactly(target("missing"))
            assertThat(targets).containsExactly(target("offline"), target("ready"))
            assertThat(calls).hasSize(3)
        }

    @Test
    fun `a result cannot remove a replaced entry or the playing entry`() =
        runTest {
            var targets = listOf(target("old"), target("next"))
            val gate = CompletableDeferred<Unit>()
            val removed = mutableListOf<QueuePreparationTarget>()
            val calls = mutableListOf<String>()
            val preparer =
                MusicQueuePreparer(backgroundScope, { targets }, {
                    calls += it.uri
                    if (it.uid == "old") gate.await()
                    QueuePreparationResult.Unmatched()
                }, {
                    removed += it
                    targets = targets - it
                })
            preparer.schedule()
            runCurrent()
            targets = listOf(target("replacement"))
            preparer.schedule()
            gate.complete(Unit)
            runCurrent()
            assertThat(removed).containsExactly(target("replacement"))
            assertThat(calls).containsExactly("music://old", "music://replacement").inOrder()
        }

    @Test
    fun `pause drains the active call and resume follows the changed queue order`() =
        runTest {
            var targets = listOf(target("one"), target("two"), target("three"))
            val gate = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val preparer =
                MusicQueuePreparer(backgroundScope, { targets }, {
                    calls += it.uri
                    if (calls.size == 1) gate.await()
                    QueuePreparationResult.Ready
                }, {})
            preparer.schedule()
            runCurrent()
            targets = emptyList()
            preparer.schedule()
            gate.complete(Unit)
            runCurrent()
            assertThat(calls).hasSize(1)
            targets = listOf(target("three"), target("two"))
            preparer.schedule()
            runCurrent()
            assertThat(calls).containsExactly("music://one", "music://three", "music://two").inOrder()
        }

    @Test
    fun `removing misses does not expand a pass beyond 100 requests`() =
        runTest {
            var targets = (1..120).map { target("$it") }
            var calls = 0
            val preparer =
                MusicQueuePreparer(backgroundScope, { targets }, {
                    calls++
                    QueuePreparationResult.Unmatched()
                }, { targets = targets - it })
            preparer.schedule()
            runCurrent()
            assertThat(calls).isEqualTo(100)
            assertThat(targets).hasSize(20)
        }

    @Test
    fun `provider change before removal makes a confirmed miss stale`() =
        runTest {
            val targets = listOf(target("next"))
            var current = true
            val removed = mutableListOf<QueuePreparationTarget>()
            val preparer =
                MusicQueuePreparer(backgroundScope, { targets }, {
                    val result = QueuePreparationResult.Unmatched { current }
                    current = false
                    result
                }, { removed += it })
            preparer.schedule()
            runCurrent()
            assertThat(removed).isEmpty()
        }

    @Test
    fun `queue replacement during the last request starts a new bounded pass`() =
        runTest {
            var targets = (1..100).map { target("$it") }
            val gate = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val preparer =
                MusicQueuePreparer(backgroundScope, { targets }, {
                    calls += it.uri
                    if (it.uid == "100") gate.await()
                    QueuePreparationResult.Ready
                }, {})
            preparer.schedule()
            runCurrent()
            assertThat(calls).hasSize(100)
            targets = listOf(target("new-playlist"))
            preparer.schedule()
            gate.complete(Unit)
            runCurrent()
            assertThat(calls.last()).isEqualTo("music://new-playlist")
            assertThat(calls).hasSize(101)
        }

    @Test
    fun `duplicates have distinct window identities`() =
        runTest {
            var targets = listOf(QueuePreparationTarget("first", "music://same"), QueuePreparationTarget("second", "music://same"))
            val removed = mutableListOf<Any>()
            val preparer =
                MusicQueuePreparer(backgroundScope, { targets }, { QueuePreparationResult.Unmatched() }, {
                    removed += it.uid
                    targets = targets - it
                })
            preparer.schedule()
            runCurrent()
            assertThat(removed).containsExactly("first", "second").inOrder()
        }
}
