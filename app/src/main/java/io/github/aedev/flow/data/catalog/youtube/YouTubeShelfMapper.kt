package io.github.aedev.flow.data.catalog.youtube

import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.BrowseEndpoint
import io.github.aedev.flow.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import io.github.aedev.flow.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import io.github.aedev.flow.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import io.github.aedev.flow.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import io.github.aedev.flow.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import io.github.aedev.flow.innertube.models.MusicCarouselShelfRenderer
import io.github.aedev.flow.innertube.models.MusicResponsiveListItemRenderer
import io.github.aedev.flow.innertube.models.MusicTwoRowItemRenderer
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.Runs
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.innertube.utils.parseTime
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem

/**
 * YouTube Music's shelves as catalog collections, shared by every page: what a shelf says about
 * itself (its context line and avatar, whether it lists tracks, which items are wide videos) is
 * kept. Podcasts are not part of the YouTube catalog, so the item parser drops them.
 */
internal object YouTubeShelfMapper {
    private const val ROW_ARTWORK_SIZE = 256

    fun carousel(shelf: MusicCarouselShelfRenderer): CollectionBlock? {
        val header = shelf.header?.musicCarouselShelfBasicHeaderRenderer ?: return null
        val title = header.title.text() ?: return null
        val context = header.strapline?.text()
        val id = listOfNotNull(context, title).joinToString(" / ")
        val isTrackList = shelf.contents.isNotEmpty() && shelf.contents.all { it.musicResponsiveListItemRenderer != null }
        val items =
            shelf.contents
                .mapNotNull { content ->
                    content.musicTwoRowItemRenderer?.let { card(it, id) }
                        ?: content.musicResponsiveListItemRenderer?.let { row(it, id) }
                }.distinctBy { it.entity }
        if (items.isEmpty()) return null
        return CollectionBlock(
            id = id,
            header =
                CollectionHeader(
                    title = title,
                    context = context,
                    avatar =
                        header.thumbnail
                            ?.musicThumbnailRenderer
                            ?.getThumbnailUrl()
                            ?.let(::Artwork),
                    target =
                        header.title.runs?.firstNotNullOfOrNull { it.navigationEndpoint?.browseEndpoint?.toEntityRef() }
                            ?: header.thumbnail
                                ?.musicThumbnailRenderer
                                ?.onTap
                                ?.browseEndpoint
                                ?.toEntityRef(),
                ),
            layout = if (isTrackList) CollectionLayout.MULTI_COLUMN_LIST else CollectionLayout.HORIZONTAL_SHELF,
            defaultItemView = if (isTrackList) ItemView.TRACK_ROW else ItemView.COVER_CARD,
            items = items,
        )
    }

    /**
     * Rows of one list shelf, numbered when [numbered], in the order they are served. A row that names no
     * artist of its own (an album's tracks) is credited to [defaultArtists].
     */
    fun trackTable(
        id: String,
        title: String?,
        rows: List<MusicResponsiveListItemRenderer>,
        numbered: Boolean,
        showAll: EntityRef? = null,
        defaultArtists: List<ArtistCredit> = emptyList(),
    ): CollectionBlock? {
        val items =
            rows
                .mapIndexedNotNull { index, renderer -> row(renderer, id, ordinal = (index + 1).takeIf { numbered }, defaultArtists) }
                .distinctBy { it.entity }
        if (items.isEmpty()) return null
        return CollectionBlock(
            id = id,
            header = title?.let(::CollectionHeader),
            layout = CollectionLayout.TRACK_TABLE,
            defaultItemView = ItemView.TRACK_ROW,
            items = items,
            showAll = showAll,
        )
    }

    private fun card(
        renderer: MusicTwoRowItemRenderer,
        collectionId: String,
    ): MetadataItem? {
        val item = HomePage.Section.fromMusicTwoRowItemRenderer(renderer) ?: return null
        val entity = item.toEntityRef() ?: return null
        return MetadataItem(
            id = "$collectionId#${entity.providerId}",
            entity = entity,
            title = item.title,
            subtitle = renderer.subtitle?.text(),
            // A card's own thumbnail is already served in the card's shape and size.
            artwork = item.thumbnail?.takeIf { it.isNotBlank() }?.let(::Artwork),
            view =
                when {
                    renderer.isLandscape -> ItemView.LANDSCAPE_CARD
                    entity.kind == EntityKind.ARTIST -> ItemView.ARTIST_PORTRAIT
                    else -> null
                },
            artists = item.credits(),
            durationSeconds = (item as? SongItem)?.duration,
            explicit = item.explicit,
        )
    }

    /**
     * A track row as YouTube Music lays it out: the title, then columns of credits and play counts,
     * one of which may name the album; the duration sits in a fixed column.
     */
    private fun row(
        renderer: MusicResponsiveListItemRenderer,
        collectionId: String,
        ordinal: Int? = null,
        defaultArtists: List<ArtistCredit> = emptyList(),
    ): MetadataItem? {
        val columns =
            renderer.flexColumns.map {
                it.musicResponsiveListItemFlexColumnRenderer.text
                    ?.runs
                    .orEmpty()
            }
        val titleRuns = columns.firstOrNull().orEmpty()
        val title = titleRuns.joinToString("") { it.text }.takeIf { it.isNotBlank() } ?: return null
        val videoId =
            renderer.playlistItemData?.videoId
                ?: titleRuns.firstNotNullOfOrNull { it.navigationEndpoint?.watchEndpoint?.videoId }
                ?: return null
        val videoType = renderer.musicVideoType ?: titleRuns.firstNotNullOfOrNull { it.navigationEndpoint?.musicVideoType }
        val (albumColumns, detailColumns) =
            columns
                .drop(1)
                .filter { runs -> runs.any { it.text.isNotBlank() } }
                .partition { runs ->
                    runs.any {
                        it.navigationEndpoint
                            ?.browseEndpoint
                            ?.toEntityRef()
                            ?.kind == EntityKind.ALBUM
                    }
                }
        val artistRuns =
            detailColumns.flatten().filter {
                it.navigationEndpoint
                    ?.browseEndpoint
                    ?.toEntityRef()
                    ?.kind == EntityKind.ARTIST
            }
        val entity =
            EntityRef(
                if (videoType != null &&
                    videoType != MUSIC_VIDEO_TYPE_ATV
                ) {
                    EntityKind.MUSIC_VIDEO
                } else {
                    EntityKind.TRACK
                },
                videoId,
            )
        return MetadataItem(
            id = "$collectionId#$videoId",
            entity = entity,
            title = title,
            subtitle = detailColumns.joinToString(" • ") { runs -> runs.joinToString("") { it.text } }.ifBlank { null },
            artwork =
                renderer.thumbnail
                    ?.musicThumbnailRenderer
                    ?.getThumbnailUrl()
                    ?.let { Artwork(ThumbnailUrlResolver.resolveMusicThumbnail(videoId, it, ROW_ARTWORK_SIZE)) },
            artists =
                artistRuns
                    .map { run -> ArtistCredit(name = run.text, entity = run.navigationEndpoint?.browseEndpoint?.toEntityRef()) }
                    .ifEmpty { defaultArtists },
            durationSeconds =
                renderer.fixedColumns
                    ?.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.text()
                    ?.parseTime(),
            explicit = renderer.badges?.any { it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" } == true,
            ordinal = ordinal,
            album = albumColumns.firstOrNull()?.joinToString("") { it.text },
        )
    }

    private fun YTItem.toEntityRef(): EntityRef? =
        when (this) {
            is SongItem -> EntityRef(if (isVideoSong) EntityKind.MUSIC_VIDEO else EntityKind.TRACK, id)
            is AlbumItem -> EntityRef(EntityKind.ALBUM, browseId)
            is PlaylistItem -> EntityRef(EntityKind.PLAYLIST, id)
            is ArtistItem -> EntityRef(EntityKind.ARTIST, id)
            else -> null
        }

    fun BrowseEndpoint.toEntityRef(): EntityRef? {
        val kind =
            when (browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType) {
                MUSIC_PAGE_TYPE_ARTIST -> EntityKind.ARTIST
                MUSIC_PAGE_TYPE_USER_CHANNEL -> EntityKind.PROFILE
                MUSIC_PAGE_TYPE_ALBUM, MUSIC_PAGE_TYPE_AUDIOBOOK -> EntityKind.ALBUM
                MUSIC_PAGE_TYPE_PLAYLIST -> EntityKind.PLAYLIST
                else -> return null
            }
        // Playlist pages browse as VL<playlist id>; a playlist is known by its bare id everywhere else.
        return EntityRef(kind, if (kind == EntityKind.PLAYLIST) browseId.removePrefix("VL") else browseId)
    }

    private fun YTItem.credits(): List<ArtistCredit> =
        when (this) {
            is SongItem -> artists.map { it.toCredit() }
            is AlbumItem -> artists.orEmpty().map { it.toCredit() }
            is PlaylistItem -> listOfNotNull(author?.toCredit())
            else -> emptyList()
        }

    private fun Artist.toCredit(): ArtistCredit = ArtistCredit(name = name, entity = id?.let { EntityRef(EntityKind.ARTIST, it) })

    fun Runs.text(): String? = runs?.joinToString("") { it.text }?.takeIf { it.isNotBlank() }
}
