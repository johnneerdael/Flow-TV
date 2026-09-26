package io.github.aedev.flow.innertube.models

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class MusicResponsiveHeaderRendererTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `a signed-in empty playlist header without buttons still decodes`() {
        // Liked Music ("LM") on a fresh account: YouTube omits the play/menu buttons entirely.
        val header =
            json.decodeFromString(
                MusicResponsiveHeaderRenderer.serializer(),
                """{ "title": { "runs": [ { "text": "Liked Music" } ] }, "subtitle": { "runs": [ { "text": "Auto playlist" } ] } }""",
            )
        assertThat(header.buttons).isEmpty()
    }
}
