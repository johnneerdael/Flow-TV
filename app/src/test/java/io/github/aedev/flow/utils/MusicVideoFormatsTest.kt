package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.MusicVideoItems
import org.junit.Test

class MusicVideoFormatsTest {
    @Test
    fun `a music video's picture is cached apart from its sound`() {
        assertThat(MusicVideoItems.videoKey("abc")).isNotEqualTo("abc")
        assertThat(MusicVideoItems.videoIdOfVideoKey(MusicVideoItems.videoKey("abc"))).isEqualTo("abc")
        assertThat(MusicVideoItems.videoIdOfVideoKey("abc")).isNull()
    }
}
