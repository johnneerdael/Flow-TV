package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisualizerPreferencesTest {
    @Test
    fun `visualizations start off below 2 GB of memory and on from a 2 GB box up`() {
        assertThat(visualizerOnByDefault(totalRamMb = 1_024)).isFalse()
        assertThat(visualizerOnByDefault(totalRamMb = 1_536)).isFalse()
        assertThat(visualizerOnByDefault(totalRamMb = 1_850)).isTrue()
        assertThat(visualizerOnByDefault(totalRamMb = 3_900)).isTrue()
    }
}
