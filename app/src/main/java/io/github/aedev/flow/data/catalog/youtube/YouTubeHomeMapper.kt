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
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage

/**
 * Turns YouTube Music's home feed into a catalog page, keeping what each shelf says about itself:
 * its context line and avatar, whether it is a list of tracks, and which items are wide videos.
 * Podcasts are not part of the YouTube catalog, so the item parser drops them.
 */
internal object YouTubeHomeMapper {
    const val HOME_PAGE_ID = "youtube-music/home"
    private const val ROW_ARTWORK_SIZE = 256

    fun page(home: HomePage): MetadataPage =
        MetadataPage(
            id = HOME_PAGE_ID,
            blocks = home.shelves.mapNotNull(::collection),
            filters = home.chips?.let(::filters),
            nextCursor = home.continuation,
        )

    private fun filters(chips: List<HomePage.Chip>): FilterControl? =
        chips
            .mapNotNull { chip ->
                val id = chip.endpoint?.params ?: return@mapNotNull null
                FilterOption(id = id, label = chip.title)
            }.takeIf { it.isNotEmpty() }
            ?.let(::FilterControl)

    fun collection(shelf: MusicCarouselShelfRenderer): CollectionBlock? {
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

    private fun row(
        renderer: MusicResponsiveListItemRenderer,
        collectionId: String,
    ): MetadataItem? {
        val song = HomePage.Section.fromMusicResponsiveListItemRenderer(renderer) ?: return null
        val entity = song.toEntityRef()
        return MetadataItem(
            id = "$collectionId#${entity.providerId}",
            entity = entity,
            title = song.title,
            subtitle =
                listOfNotNull(song.artists.joinToString(", ") { it.name }.ifBlank { null }, song.viewCountText)
                    .joinToString(" • ")
                    .ifBlank { null },
            artwork =
                song.thumbnail
                    .takeIf {
                        it.isNotBlank()
                    }?.let { Artwork(ThumbnailUrlResolver.resolveMusicThumbnail(song.id, it, ROW_ARTWORK_SIZE)) },
            artists = song.credits(),
            durationSeconds = song.duration,
            explicit = song.explicit,
        )
    }

    private fun YTItem.toEntityRef(): EntityRef? =
        when (this) {
            is SongItem -> toEntityRef()
            is AlbumItem -> EntityRef(EntityKind.ALBUM, browseId)
            is PlaylistItem -> EntityRef(EntityKind.PLAYLIST, id)
            is ArtistItem -> EntityRef(EntityKind.ARTIST, id)
            else -> null
        }

    private fun SongItem.toEntityRef(): EntityRef = EntityRef(if (isVideoSong) EntityKind.MUSIC_VIDEO else EntityKind.TRACK, id)

    private fun BrowseEndpoint.toEntityRef(): EntityRef? {
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

    private fun Runs.text(): String? = runs?.joinToString("") { it.text }?.takeIf { it.isNotBlank() }
}
