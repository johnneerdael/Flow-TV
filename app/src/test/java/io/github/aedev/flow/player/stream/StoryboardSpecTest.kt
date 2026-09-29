package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The spec below is the real `playerStoryboardSpecRenderer.spec` for a 583 s video, and the
 * expectations were checked against the live sheets: every URL built this way returned a JPEG, and
 * the crops at 0/60/180/400/580 s were the right frames.
 */
private const val BASE =
    "https://i.ytimg.com/sb/3xngArcFpek/storyboard3_L\$L/\$N.jpg?sqp=-oaymwGhAUg48quKqQOYAYgBAZUB"

private const val SPEC =
    BASE +
        "|48#27#100#10#10#0#default#rs\$AOn4CLDHlKZRJY7QaV07_G4IfgGnNPp5Gw" +
        "|80#45#118#10#10#5000#M\$M#rs\$AOn4CLDPw68sp1OS6-dzM4d6CF9-5XmwgQ" +
        "|160#90#118#5#5#5000#M\$M#rs\$AOn4CLBlGUGTrWBNsfOLXoVoVXTtJQdCVw"

private const val DURATION_MS = 583_000L

class StoryboardSpecTest {
    private fun levels() = StoryboardSpec.parse(SPEC, DURATION_MS)

    @Test
    fun `every level of the spec is parsed`() {
        val levels = levels()

        assertThat(levels).hasSize(3)
        assertThat(levels.map { it.thumbnailWidth }).containsExactly(48, 80, 160).inOrder()
        assertThat(levels.map { it.frameCount }).containsExactly(100, 118, 118).inOrder()
        assertThat(levels.map { it.columns to it.rows })
            .containsExactly(10 to 10, 10 to 10, 5 to 5)
            .inOrder()
    }

    @Test
    fun `the coarsest level spreads its frames across the whole video`() {
        // It declares an interval of 0; 583s over 100 frames is 5830ms each.
        assertThat(levels().first().intervalMs).isEqualTo(5_830L)
    }

    @Test
    fun `a video with no storyboard yields no levels`() {
        assertThat(StoryboardSpec.parse(null, DURATION_MS)).isEmpty()
        assertThat(StoryboardSpec.parse("", DURATION_MS)).isEmpty()
        assertThat(StoryboardSpec.parse("https://example.test/sb.jpg", DURATION_MS)).isEmpty()
    }

    @Test
    fun `a spec whose base carries no tokens is rejected`() {
        val malformed = "https://example.test/sb.jpg|160#90#118#5#5#5000#M\$M#rs\$sig"

        assertThat(StoryboardSpec.parse(malformed, DURATION_MS)).isEmpty()
    }

    @Test
    fun `a zero-interval level with no duration is unusable`() {
        val spec = BASE + "|48#27#100#10#10#0#default#rs\$sig"

        assertThat(StoryboardSpec.parse(spec, durationMs = 0L)).isEmpty()
    }
}
