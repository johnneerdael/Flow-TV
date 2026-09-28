package io.github.aedev.flow.data.catalog.youtube

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.BrowseResponse
import io.github.aedev.flow.innertube.pages.HomePage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import org.junit.Test

/** Against a signed-in YouTube Music home captured from the web client, trimmed and anonymised. */
@OptIn(ExperimentalSerializationApi::class)
class YouTubeHomeMapperTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun response(name: String): BrowseResponse =
        json.decodeFromString(BrowseResponse.serializer(), checkNotNull(javaClass.getResource("/catalog/$name")).readText())

    private val home = HomePage.fromBrowseResponse(response("youtube_music_home.json"))
    private val page = YouTubeHomeMapper.page(home)
    private val playback = YouTubeCatalogPlayback()

    private fun collection(title: String): CollectionBlock =
        page.blocks.filterIsInstance<CollectionBlock>().single {
            it.header?.title ==
                title
        }

    @Test
    fun `every shelf becomes a collection in the order it is served`() {
        assertThat(page.blocks.filterIsInstance<CollectionBlock>().map { it.header?.title })
            .containsExactly("New releases", "Quick picks", "Massano", "Listen again", "Music videos for you", "Live performances")
            .inOrder()
        assertThat(page.nextCursor).isEqualTo("fixture-continuation")
    }

    @Test
    fun `a list shelf reads as playable track rows in columns`() {
        val quickPicks = collection("Quick picks")

        assertThat(quickPicks.layout).isEqualTo(CollectionLayout.MULTI_COLUMN_LIST)
        assertThat(quickPicks.defaultItemView).isEqualTo(ItemView.TRACK_ROW)
        assertThat(quickPicks.items.all { playback.track(it) != null }).isTrue()
        assertThat(
            quickPicks.items.map {
                it.title
            },
        ).containsExactly("Patient Zero", "Остави ме на мира", "new trick", "BADDIES IN BELGICA", "Joseph")
        assertThat(quickPicks.items.first().subtitle).isEqualTo("Taylor Swift • 3.9M plays")
        assertThat(quickPicks.items.all { it.artists.isNotEmpty() }).isTrue()
    }

    @Test
    fun `a similar-to shelf keeps its context line, avatar and the artist it names`() {
        val similar = collection("Massano")
        val header = checkNotNull(similar.header)

        assertThat(header.context).isEqualTo("SIMILAR TO")
        assertThat(header.avatar).isNotNull()
        assertThat(header.target).isEqualTo(EntityRef(EntityKind.ARTIST, "UCQB_EBQnT6ImE-niLXQCTOQ"))
        assertThat(similar.layout).isEqualTo(CollectionLayout.HORIZONTAL_SHELF)
    }

    @Test
    fun `one shelf mixes round artists, square covers and wide videos`() {
        val similar = collection("Massano")
        assertThat(
            similar.items
                .first()
                .entity.kind,
        ).isEqualTo(EntityKind.ARTIST)
        assertThat(similar.items.first().view).isEqualTo(ItemView.ARTIST_PORTRAIT)
        assertThat(
            similar.items
                .drop(1)
                .map { it.view }
                .toSet(),
        ).containsExactly(null)

        val listenAgain = collection("Listen again")
        assertThat(listenAgain.header?.target?.kind).isEqualTo(EntityKind.PROFILE)
        assertThat(
            listenAgain.items.map {
                it.view
            },
        ).containsExactly(null, ItemView.LANDSCAPE_CARD, ItemView.LANDSCAPE_CARD, ItemView.LANDSCAPE_CARD).inOrder()
    }

    @Test
    fun `music videos are wide and playable`() {
        val videos = collection("Music videos for you")

        assertThat(videos.items.map { it.view }.toSet()).containsExactly(ItemView.LANDSCAPE_CARD)
        assertThat(videos.items.map { it.entity.kind }.toSet()).containsExactly(EntityKind.MUSIC_VIDEO)
        assertThat(videos.items.mapNotNull { playback.track(it)?.isVideoSong }.toSet()).containsExactly(true)
    }

    @Test
    fun `podcast episodes are left out of the YouTube catalog`() {
        val live = collection("Live performances")

        assertThat(live.items.map { it.title }).doesNotContain("Above & Beyond Live at Ziggo Dome, Amsterdam")
        assertThat(live.items).hasSize(4)
    }

    @Test
    fun `albums and playlists open by their browse ids`() {
        val releases = collection("New releases")

        assertThat(releases.items.map { it.entity.kind })
            .containsExactly(
                EntityKind.ALBUM,
                EntityKind.ALBUM,
                EntityKind.ALBUM,
                EntityKind.PLAYLIST,
                EntityKind.ALBUM,
            ).inOrder()
        assertThat(
            releases.items
                .first()
                .entity.providerId,
        ).startsWith("MPRE")
        assertThat(releases.items.all { playback.track(it) == null }).isTrue()
    }

    @Test
    fun `the home's chips become filters`() {
        assertThat(checkNotNull(page.filters).options.map { it.label }).containsAtLeast("Relax", "Workout", "Energize")
    }

    @Test
    fun `a continuation page maps the same way, and a playlist header opens the playlist itself`() {
        val next = YouTubeHomeMapper.page(HomePage.fromContinuationResponse(response("youtube_music_home_continuation.json")))
        val similar = next.blocks.filterIsInstance<CollectionBlock>().single { it.header?.context == "SIMILAR TO" }

        assertThat(similar.header?.target).isEqualTo(EntityRef(EntityKind.PLAYLIST, "PLfixtureplaylist"))
        assertThat(next.filters).isNull()
        assertThat(next.nextCursor).isEqualTo("fixture-continuation-2")
    }
}
