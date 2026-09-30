package io.github.aedev.flow.data.audio.eq

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EqStateOpsTest {
    private val rock = "builtin:rock"
    private val mine = EqPreset("user:a", "Mine", EqCurve(bands = listOf(EqBand(500.0, 3.0))))

    @Test
    fun `off and compare both produce an unprocessed spec`() {
        val state = EqState().selectPreset(rock)
        assertThat(state.copy(enabled = false).processingSpec(bypass = false)).isEqualTo(EqProcessingSpec.OFF)
        assertThat(state.processingSpec(bypass = true)).isEqualTo(EqProcessingSpec.OFF)
    }

    @Test
    fun `a preview replaces the playing curve without touching the state`() {
        val preview = EqCurve(bands = listOf(EqBand(2_000.0, 5.0)))
        val spec = EqState().selectPreset(rock).processingSpec(bypass = false, preview = preview)
        assertThat(spec.bands).containsExactly(EqBand(2_000.0, 5.0))
    }

    @Test
    fun `sanitising repairs a damaged state`() {
        val damaged =
            EqState(
                graphic = EqWorkingCopy(null, EqCurve(bands = listOf(EqBand(1.0, 99.0)))),
                bassBoost = Double.NaN,
                userPresets = listOf(mine, mine, EqPreset("", "x", EqCurve())),
            )
        val clean = damaged.sanitized()
        assertThat(clean.graphic.curve).isEqualTo(GraphicEq.flatCurve())
        assertThat(clean.bassBoost).isEqualTo(0.0)
        assertThat(clean.userPresets).containsExactly(mine)
    }

    @Test
    fun `fitting flat gives flat and never writes a negative zero`() {
        assertThat(GraphicEq.fit(EqCurve())).isEqualTo(GraphicEq.flatCurve())
    }
}
