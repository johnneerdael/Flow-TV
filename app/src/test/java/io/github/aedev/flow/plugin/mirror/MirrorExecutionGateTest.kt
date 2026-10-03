package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MirrorExecutionGateTest {
    @Test
    fun `foreground prioritizes its key while all matches remain serial`() =
        runTest {
            val gate = MirrorExecutionGate()
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val visible =
                async {
                    gate.foreground("visible") {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            val background = async { gate.match("other", true) { calls += "other" } }
            val promoted = async { gate.match("visible", true) { calls += "visible" } }
            promoted.await()
            runCurrent()
            assertThat(calls).containsExactly("visible")
            release.complete(Unit)
            visible.await()
            background.await()
            assertThat(calls).containsExactly("visible", "other").inOrder()
        }
}
