package io.github.aedev.flow.player.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioOffloadPolicyTest {
    @Test
    fun `a tv never offloads`() {
        assertThat(shouldOffloadAudio(isTv = true, needsProcessors = false)).isFalse()
    }

    @Test
    fun `a phone offloads while no processor needs the audio`() {
        assertThat(shouldOffloadAudio(isTv = false, needsProcessors = false)).isTrue()
    }

    @Test
    fun `a phone keeps offload off while a processor needs the audio`() {
        assertThat(shouldOffloadAudio(isTv = false, needsProcessors = true)).isFalse()
    }
}
