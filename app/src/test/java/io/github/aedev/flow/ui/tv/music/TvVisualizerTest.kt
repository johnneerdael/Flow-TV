package io.github.aedev.flow.ui.tv.music

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvVisualizerTest {
    @Test
    fun `right steps forward, left steps back, other keys are not preset steps`() {
        assertThat(presetStepFor(KeyEvent.KEYCODE_DPAD_RIGHT)).isTrue()
        assertThat(presetStepFor(KeyEvent.KEYCODE_DPAD_LEFT)).isFalse()
        assertThat(presetStepFor(KeyEvent.KEYCODE_DPAD_UP)).isNull()
        assertThat(presetStepFor(KeyEvent.KEYCODE_DPAD_CENTER)).isNull()
    }

    @Test
    fun `the frame divisor lands closest to the cap without going below 24 fps`() {
        assertThat(frameDivisor(refreshRate = 60f, cap = 60)).isEqualTo(1)
        assertThat(frameDivisor(refreshRate = 60f, cap = 30)).isEqualTo(2)
        assertThat(frameDivisor(refreshRate = 120f, cap = 60)).isEqualTo(2)
        assertThat(frameDivisor(refreshRate = 120f, cap = 30)).isEqualTo(4)
        assertThat(frameDivisor(refreshRate = 50f, cap = 20)).isEqualTo(2)
    }
}
