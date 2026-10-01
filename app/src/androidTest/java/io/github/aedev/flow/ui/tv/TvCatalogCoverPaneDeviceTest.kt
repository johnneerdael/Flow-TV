package io.github.aedev.flow.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.ui.tv.catalog.TvCatalogCoverPane
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.catalog.Attribution
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvCatalogCoverPaneDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun collectionCoverOmitsProviderBrandingAndKeepsFullPlayControlsInTheAvailableHeight() {
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
                    Box(Modifier.width(400.dp).height(390.dp).testTag("cover-bounds")) {
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
        assertEquals(fullHeight, play.height, 0.1f)
        assertEquals(fullHeight, shuffle.height, 0.1f)
        assertTrue(
            play.bottom <=
                compose
                    .onNodeWithTag("cover-bounds")
                    .fetchSemanticsNode()
                    .boundsInRoot.bottom,
        )
    }
}
