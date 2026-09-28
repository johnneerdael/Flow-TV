package io.github.aedev.flow.data.catalog.youtube

import io.github.aedev.flow.data.catalog.youtube.YouTubeShelfMapper.text
import io.github.aedev.flow.data.catalog.youtube.YouTubeShelfMapper.toEntityRef
import io.github.aedev.flow.innertube.models.MusicResponsiveHeaderRenderer
import io.github.aedev.flow.innertube.models.MusicShelfRenderer
import io.github.aedev.flow.innertube.models.SectionListRenderer
import io.github.aedev.flow.innertube.models.getContinuation
import io.github.aedev.flow.innertube.models.response.BrowseResponse
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.Attribution
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.PageBlock

/**
 * Artist, album and playlist pages as YouTube Music serves them: an artist's portrait above its top
 * songs and shelves; a collection's cover and details beside its tracks, then related shelves.
 */
internal object YouTubePageMapper {
    /** The id of the block holding a collection's tracks, which continuation pages extend. */
    const val TRACKS_BLOCK_ID = "tracks"

    fun artist(
        response: BrowseResponse,
        channelId: String,
    ): MetadataPage {
        val header = response.header?.musicImmersiveHeaderRenderer
        val sections =
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
        val entity = EntityRef(EntityKind.ARTIST, channelId)
        val banner =
            header?.let {
                EntityHeader(
                    id = "$channelId/header",
                    style = HeaderStyle.PORTRAIT,
                    entity = entity,
                    title = it.title.text().orEmpty(),
                    artwork =
                        it.thumbnail
                            ?.musicThumbnailRenderer
                            ?.getThumbnailUrl()
                            ?.let(::Artwork),
                    details = listOfNotNull(it.monthlyListenerCount?.text()),
                    description = it.description?.text(),
                    station =
                        it.startRadioButton
                            ?.buttonRenderer
                            ?.navigationEndpoint
                            ?.let { endpoint -> endpoint.watchPlaylistEndpoint ?: endpoint.watchEndpoint }
                            ?.playlistId
                            ?.let { id -> EntityRef(EntityKind.PLAYLIST, id) },
                )
            }
        return MetadataPage(
            id = "youtube-music/artist/$channelId",
            blocks = listOfNotNull(banner) + sections.orEmpty().mapNotNull(::section),
        )
    }

    /** An album or a playlist: its cover and details beside its tracks. */
    fun collection(
        response: BrowseResponse,
        entity: EntityRef,
    ): MetadataPage {
        val twoColumn = response.contents?.twoColumnBrowseResultsRenderer
        val header =
            twoColumn
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstNotNullOfOrNull { it.musicResponsiveHeaderRenderer }
        val sections =
            twoColumn
                ?.secondaryContents
                ?.sectionListRenderer
                ?.contents
                .orEmpty()
        val tracksShelf = sections.firstNotNullOfOrNull { it.musicPlaylistShelfRenderer }
        val cover = header?.let { cover(it, entity) }
        val isAlbum = entity.kind == EntityKind.ALBUM
        // An album's tracks name no artist of their own: they are the album's.
        val albumArtists = if (isAlbum) listOfNotNull(cover?.attribution?.let { ArtistCredit(it.name, it.entity) }) else emptyList()
        return MetadataPage(
            id = "youtube-music/${entity.kind.name.lowercase()}/${entity.providerId}",
            blocks = listOfNotNull(cover) + sections.mapNotNull { section(it, numbered = isAlbum, defaultArtists = albumArtists) },
            nextCursor = tracksShelf?.contents?.getContinuation(),
        )
    }

    /** More tracks of a long playlist, as a page holding only the extended tracks block. */
    fun tracksContinuation(response: BrowseResponse): MetadataPage {
        val rows =
            response.continuationContents?.musicPlaylistShelfContinuation?.contents
                ?: response.onResponseReceivedActions
                    ?.firstOrNull()
                    ?.appendContinuationItemsAction
                    ?.continuationItems
                    .orEmpty()
        return MetadataPage(
            id = TRACKS_BLOCK_ID,
            blocks = listOfNotNull(tracks(rows, numbered = false)),
            nextCursor = rows.getContinuation(),
        )
    }

    private fun cover(
        header: MusicResponsiveHeaderRenderer,
        entity: EntityRef,
    ): EntityHeader {
        val playlistId =
            header.buttons.firstNotNullOfOrNull { button ->
                button.musicPlayButtonRenderer
                    ?.playNavigationEndpoint
                    ?.let { it.watchPlaylistEndpoint ?: it.watchEndpoint }
                    ?.playlistId
            } ?: entity.providerId.takeIf { entity.kind == EntityKind.PLAYLIST }
        val artistRun = header.straplineTextOne?.runs?.firstOrNull()
        return EntityHeader(
            id = "${entity.providerId}/header",
            style = HeaderStyle.COVER,
            entity = entity,
            title = header.title.text().orEmpty(),
            artwork =
                header.thumbnail
                    ?.musicThumbnailRenderer
                    ?.getThumbnailUrl()
                    ?.let(::Artwork),
            details = listOfNotNull(header.subtitle.text(), header.secondSubtitle?.text()),
            attribution =
                artistRun?.text?.takeIf { it.isNotBlank() }?.let { name ->
                    Attribution(
                        name = name,
                        avatar =
                            header.straplineThumbnail
                                ?.musicThumbnailRenderer
                                ?.getThumbnailUrl()
                                ?.let(::Artwork),
                        entity = artistRun.navigationEndpoint?.browseEndpoint?.toEntityRef(),
                    )
                } ?: header.facepile?.avatarStackViewModel?.let(::owner),
            description =
                header.description
                    ?.musicDescriptionShelfRenderer
                    ?.description
                    ?.text(),
            tracks = playlistId?.let { EntityRef(EntityKind.PLAYLIST, it) },
        )
    }

    private fun owner(stack: MusicResponsiveHeaderRenderer.Facepile.AvatarStack): Attribution? {
        val name = stack.text?.content?.takeIf { it.isNotBlank() } ?: return null
        return Attribution(
            name = name,
            avatar =
                stack.avatars
                    .firstOrNull()
                    ?.avatarViewModel
                    ?.image
                    ?.sources
                    ?.lastOrNull()
                    ?.url
                    ?.let(::Artwork),
            entity =
                stack.rendererContext
                    ?.commandContext
                    ?.onTap
                    ?.innertubeCommand
                    ?.browseEndpoint
                    ?.toEntityRef(),
        )
    }

    private fun section(
        section: SectionListRenderer.Content,
        numbered: Boolean = false,
        defaultArtists: List<ArtistCredit> = emptyList(),
    ): PageBlock? {
        section.musicCarouselShelfRenderer?.let { return YouTubeShelfMapper.carousel(it) }
        section.musicPlaylistShelfRenderer?.let { return tracks(it.contents.orEmpty(), numbered) }
        val shelf = section.musicShelfRenderer ?: return null
        val title = shelf.title?.text()
        return YouTubeShelfMapper.trackTable(
            id = title ?: TRACKS_BLOCK_ID,
            title = title,
            rows = shelf.contents.orEmpty().mapNotNull { it.musicResponsiveListItemRenderer },
            numbered = numbered,
            defaultArtists = defaultArtists,
            showAll =
                shelf.bottomEndpoint
                    ?.browseEndpoint
                    ?.toEntityRef()
                    ?.takeIf { it.kind == EntityKind.PLAYLIST },
        )
    }

    private fun tracks(
        rows: List<MusicShelfRenderer.Content>,
        numbered: Boolean,
    ) = YouTubeShelfMapper.trackTable(
        id = TRACKS_BLOCK_ID,
        title = null,
        rows = rows.mapNotNull { it.musicResponsiveListItemRenderer },
        numbered = numbered,
    )
}
