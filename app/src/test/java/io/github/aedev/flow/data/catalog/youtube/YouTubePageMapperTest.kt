package io.github.aedev.flow.data.catalog.youtube

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.BrowseResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.ItemView
import org.junit.Test

/** Against anonymous YouTube Music artist, album and playlist pages, trimmed. */
@OptIn(ExperimentalSerializationApi::class)
class YouTubePageMapperTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun response(name: String): BrowseResponse =
        json.decodeFromString(BrowseResponse.serializer(), checkNotNull(javaClass.getResource("/catalog/$name")).readText())

    private val artist = YouTubePageMapper.artist(response("youtube_music_artist.json"), "UCQB_EBQnT6ImE-niLXQCTOQ")
    private val album = YouTubePageMapper.collection(response("youtube_music_album.json"), EntityRef(EntityKind.ALBUM, "MPREb_2IyzsjcMyu2"))
    private val playlist =
        YouTubePageMapper.collection(
            response("youtube_music_playlist.json"),
            EntityRef(EntityKind.PLAYLIST, "PLEvb08V8d8f8"),
        )

    private fun collections(blocks: List<Any>) = blocks.filterIsInstance<CollectionBlock>()

    @Test
    fun `an artist opens with its portrait, its audience and its mix`() {
        val header = artist.blocks.first() as EntityHeader

        assertThat(header.style).isEqualTo(HeaderStyle.PORTRAIT)
        assertThat(header.title).isEqualTo("Massano")
        assertThat(header.details).containsExactly("759K monthly audience")
        assertThat(header.artwork).isNotNull()
        assertThat(header.station).isEqualTo(EntityRef(EntityKind.PLAYLIST, "RDEMrKT7MiRTXV0lBmFLcMBxPg"))
    }

    @Test
    fun `an artist's top songs are a table with plays and album, and all songs one step away`() {
        val top = collections(artist.blocks).first()

        assertThat(top.header?.title).isEqualTo("Top songs")
        assertThat(top.layout).isEqualTo(CollectionLayout.TRACK_TABLE)
        val first = top.items.first()
        assertThat(first.title).isEqualTo("The Feeling (2022 Remaster)")
        assertThat(first.subtitle).isEqualTo("Massano • 14M plays")
        assertThat(first.album).isEqualTo("In My System EP")
        assertThat(first.ordinal).isNull()
        assertThat(top.showAll).isEqualTo(EntityRef(EntityKind.PLAYLIST, "OLAK5uy_mrZZBKDBYhlaDLazNecBFv5fFHcpDHjPw"))
    }

    @Test
    fun `an artist's shelves follow in order, videos wide and similar artists round`() {
        val shelves = collections(artist.blocks).drop(1)

        assertThat(shelves.map { it.header?.title })
            .containsExactly(
                "Albums",
                "Singles & EPs",
                "Videos",
                "Live performances",
                "Featured on",
                "Playlists by Massano",
                "Fans might also like",
            ).inOrder()
        assertThat(
            shelves
                .single { it.header?.title == "Videos" }
                .items
                .map { it.view }
                .toSet(),
        ).containsExactly(ItemView.LANDSCAPE_CARD)
        assertThat(
            shelves
                .single { it.header?.title == "Fans might also like" }
                .items
                .map { it.view }
                .toSet(),
        ).containsExactly(ItemView.ARTIST_PORTRAIT)
    }

    @Test
    fun `an album shows its cover, facts and artist, and numbers its tracks`() {
        val header = album.blocks.first() as EntityHeader

        assertThat(header.style).isEqualTo(HeaderStyle.COVER)
        assertThat(header.title).isEqualTo("NEWORLD II")
        assertThat(header.details).containsExactly("Album • 2026", "15 songs • 44 minutes").inOrder()
        assertThat(header.attribution?.name).isEqualTo("Argy")
        assertThat(header.attribution?.entity).isEqualTo(EntityRef(EntityKind.ARTIST, "UCEIFXVEf5DULGLCKSRJbabQ"))
        assertThat(header.tracks).isEqualTo(EntityRef(EntityKind.PLAYLIST, "OLAK5uy_mjsgo4gplxlaTC9QFVbdV3l8YOYeDsO9k"))

        val tracks = collections(album.blocks).first()
        assertThat(tracks.layout).isEqualTo(CollectionLayout.TRACK_TABLE)
        assertThat(tracks.items).hasSize(15)
        assertThat(tracks.items.map { it.ordinal }).isEqualTo((1..15).toList())
        with(tracks.items.first()) {
            assertThat(title).isEqualTo("DONA")
            assertThat(subtitle).isEqualTo("Argy & Omiki • 2M plays")
            assertThat(durationSeconds).isEqualTo(170)
            assertThat(artists.map { it.name }).containsExactly("Argy", "Omiki").inOrder()
        }
    }

    @Test
    fun `an album is followed by releases like it`() {
        assertThat(collections(album.blocks).map { it.header?.title }).contains("Releases for you")
    }

    @Test
    fun `a playlist describes itself and lists its tracks with their albums, unnumbered`() {
        val header = playlist.blocks.first() as EntityHeader

        assertThat(header.title).isEqualTo("Melodic Selections")
        assertThat(header.attribution?.name).isEqualTo("Massano")
        assertThat(header.attribution?.avatar).isNotNull()
        assertThat(header.attribution?.entity).isEqualTo(EntityRef(EntityKind.ARTIST, "UC80nHYohajSIsAXGipxVwyA"))
        assertThat(header.details.first()).isEqualTo("Playlist • 2026")
        assertThat(header.description).isNotEmpty()
        assertThat(header.tracks).isEqualTo(EntityRef(EntityKind.PLAYLIST, "PLEvb08V8d8f8"))

        val tracks = collections(playlist.blocks).first()
        assertThat(tracks.items).hasSize(12)
        with(tracks.items.first()) {
            assertThat(title).isEqualTo("Beyond Today")
            assertThat(subtitle).isEqualTo("Massano")
            assertThat(album).isEqualTo("Beyond Today")
            assertThat(ordinal).isNull()
            assertThat(durationSeconds).isEqualTo(216)
        }
    }

    @Test
    fun `a long playlist's further tracks extend its tracks block and name the next page`() {
        val rows =
            json
                .parseToJsonElement(checkNotNull(javaClass.getResource("/catalog/youtube_music_playlist.json")).readText())
                .jsonObject["contents"]!!
                .jsonObject["twoColumnBrowseResultsRenderer"]!!
                .jsonObject["secondaryContents"]!!
                .jsonObject["sectionListRenderer"]!!
                .jsonObject["contents"]!!
                .jsonArray[0]
                .jsonObject["musicPlaylistShelfRenderer"]!!
                .jsonObject["contents"]!!
                .jsonArray
                .take(3)
        val continuation = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}"""
        val response =
            json.decodeFromString(
                BrowseResponse.serializer(),
                """{"responseContext":{},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${rows.joinToString(
                    ",",
                )},$continuation]}}]}""",
            )

        val page = YouTubePageMapper.tracksContinuation(response)

        val tracks = page.blocks.single() as CollectionBlock
        assertThat(tracks.id).isEqualTo(YouTubePageMapper.TRACKS_BLOCK_ID)
        assertThat(tracks.items.map { it.title }.first()).isEqualTo("Beyond Today")
        assertThat(tracks.items).hasSize(3)
        assertThat(page.nextCursor).isEqualTo("next-page")
    }
}
