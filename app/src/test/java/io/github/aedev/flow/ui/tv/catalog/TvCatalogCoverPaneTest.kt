package io.github.aedev.flow.ui.tv.catalog

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.catalog.Attribution
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvCatalogCoverPaneTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `collection cover omits provider branding and keeps full play controls in the available height`() {
        val header =
            EntityHeader(
                "header",
                HeaderStyle.COVER,
                EntityRef(EntityKind.PLAYLIST, "playlist"),
                "An extended playlist title that spans two lines",
                attribution = Attribution("Spotify"),
                details = listOf("100 songs"),
                description =
                    "A long description with artists and recordings across the collection " +
                        "that should not squeeze away the play buttons.",
            )
        compose.setContent {
            TvTheme {
                Row {
                    Box(Modifier.width(400.dp).height(390.dp)) {
                        TvCatalogCoverPane(header, {}) {
                            TvButton("Play", {})
                            TvButton("Shuffle", {})
                        }
                    }
                    TvButton("Reference", {})
                }
            }
        }
        compose.onNodeWithText("Spotify").assertDoesNotExist()
        val play = compose.onNodeWithText("Play").fetchSemanticsNode().boundsInRoot
        val shuffle = compose.onNodeWithText("Shuffle").fetchSemanticsNode().boundsInRoot
        val fullHeight =
            compose
                .onNodeWithText("Reference")
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertThat(play.height).isWithin(0.1f).of(fullHeight)
        assertThat(shuffle.height).isWithin(0.1f).of(fullHeight)
        assertThat(play.bottom).isAtMost(390f)
        assertThat(shuffle.bottom).isAtMost(390f)
    }

    @Test
    fun `artist attribution remains available and opens the artist`() {
        val artist = EntityRef(EntityKind.ARTIST, "artist")
        var opened: EntityRef? = null
        val header =
            EntityHeader(
                "header",
                HeaderStyle.COVER,
                EntityRef(EntityKind.ALBUM, "album"),
                "Album",
                attribution = Attribution("Artist", entity = artist),
            )
        compose.setContent {
            TvTheme {
                Box(Modifier.width(400.dp).height(500.dp)) {
                    TvCatalogCoverPane(header, { opened = it }) { TvButton("Play", {}) }
                }
            }
        }
        compose.onNodeWithText("Artist").performClick()
        assertThat(opened).isEqualTo(artist)
    }
}
