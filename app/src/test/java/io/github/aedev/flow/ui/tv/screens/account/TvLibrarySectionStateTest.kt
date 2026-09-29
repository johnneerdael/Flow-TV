package io.github.aedev.flow.ui.tv.screens.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvLibrarySectionStateTest {
    private val loaded = TvLibrarySectionState(isLoading = false, accountKey = "a", loadedAtMs = 1_000)

    @Test
    fun `a fresh read for the same account is kept`() {
        assertThat(loaded.needsLoad("a", nowMs = 5_000, freshForMs = 10_000)).isFalse()
    }

    @Test
    fun `a stale read, another account or a failure reads again`() {
        assertThat(loaded.needsLoad("a", nowMs = 11_000, freshForMs = 10_000)).isTrue()
        assertThat(loaded.needsLoad("b", nowMs = 2_000, freshForMs = 10_000)).isTrue()
        assertThat(loaded.copy(error = "offline").needsLoad("a", nowMs = 2_000, freshForMs = 10_000)).isTrue()
    }

    @Test
    fun `a read in progress is not started twice`() {
        assertThat(TvLibrarySectionState(isLoading = true).needsLoad("b", nowMs = 0, freshForMs = 0)).isFalse()
    }
}
