package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.pages.InnerTubeJson.ARTIST
import io.github.aedev.flow.innertube.pages.InnerTubeJson.ATV
import io.github.aedev.flow.innertube.pages.InnerTubeJson.PLAYLIST
import io.github.aedev.flow.innertube.pages.InnerTubeJson.UGC
import io.github.aedev.flow.innertube.pages.InnerTubeJson.browseEndpoint
import io.github.aedev.flow.innertube.pages.InnerTubeJson.carousel
import io.github.aedev.flow.innertube.pages.InnerTubeJson.run
import io.github.aedev.flow.innertube.pages.InnerTubeJson.separator
import io.github.aedev.flow.innertube.pages.InnerTubeJson.songRow
import io.github.aedev.flow.innertube.pages.InnerTubeJson.twoRow
import org.junit.Test

class HomePageTest {
    private val artist = run("TH;EN Official", browseEndpoint("UCthen", ARTIST))

    private fun section(shelf: String): HomePage.Section? =
        InnerTubeJson
            .browse(listOf(shelf))
            .contents
            ?.sectionListRenderer
            ?.contents
            ?.single()
            ?.musicCarouselShelfRenderer
            ?.let(HomePage.Section::fromMusicCarouselShelfRenderer)

    @Test
    fun `list shelves such as Quick picks are kept as songs`() {
        val section =
            section(
                carousel(
                    "Quick picks",
                    listOf(songRow("q1", "The Feeling", ATV, listOf(artist, separator(), run("1.1M plays")))),
                ),
            )

        val song = section?.items?.single() as SongItem
        assertThat(section.title).isEqualTo("Quick picks")
        assertThat(song.id).isEqualTo("q1")
        assertThat(song.artists.map { it.name }).containsExactly("TH;EN Official")
        assertThat(song.viewCountText).isEqualTo("1.1M plays")
        assertThat(song.duration).isNull()
    }

    @Test
    fun `a long-listens row reads its duration from the fixed column, not as a view count`() {
        val section =
            section(
                carousel(
                    "Long listens",
                    listOf(songRow("l1", "Live set", UGC, listOf(artist, separator(), run("2:59:15")), duration = "2:59:15")),
                ),
            )

        val song = section?.items?.single() as SongItem
        assertThat(song.duration).isEqualTo(2 * 3600 + 59 * 60 + 15)
        assertThat(song.viewCountText).isNull()
    }

    @Test
    fun `card and list items keep the shelf's order`() {
        val section =
            section(
                carousel(
                    "Mixed",
                    listOf(
                        twoRow("VLPLmix", PLAYLIST, "Mix", listOf(run("Flow")), playlistId = "PLmix"),
                        songRow("s1", "Song", ATV, listOf(artist)),
                    ),
                ),
            )

        assertThat(section?.items?.map { it::class }).containsExactly(PlaylistItem::class, SongItem::class).inOrder()
    }
}
