package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClientGateRegistryTest {
    private var nowMs = 0L
    private val registry = ClientGateRegistry(ttlMs = 1_000L, clockMs = { nowMs })

    @Test
    fun `an unnamed client is never demoted by a refusal`() {
        assertThat(registry.reportRefused(null)).isFalse()
        assertThat(registry.reportRefused("")).isFalse()
    }

    @Test
    fun `clearing resets refusal strikes too`() {
        registry.reportRefused("MWEB")
        registry.clear()

        assertThat(registry.reportRefused("MWEB")).isFalse()
    }
}
