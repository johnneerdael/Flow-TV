package nl.neerdael.milkbeat.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One page of a provider's catalog, as an ordered list of typed blocks. The provider chooses the
 * blocks, their order, layouts and item views; Milkbeat chooses how they look on screen.
 */
@Serializable
data class MetadataPage(
    val id: String,
    @Serializable(with = LenientListSerializer::class)
    val blocks: List<PageBlock>,
    val filters: FilterControl? = null,
    val nextCursor: String? = null,
)

@Serializable
sealed interface PageBlock {
    val id: String
}

/**
 * A titled or untitled run of items in one [layout]. Items use [defaultItemView] unless they name
 * their own, so one shelf can mix round artists, square covers and wide videos.
 */
@Serializable
@SerialName("collection")
data class CollectionBlock(
    override val id: String,
    val header: CollectionHeader?,
    val layout: CollectionLayout,
    val defaultItemView: ItemView,
    @Serializable(with = LenientListSerializer::class)
    val items: List<MetadataItem>,
    val showAll: EntityRef? = null,
    /** "Show all" re-runs the same page or search with this filter, as in a search's songs section. */
    val showAllFilterId: String? = null,
) : PageBlock

/**
 * What a page is about: an artist's portrait, or a collection's cover, with its details. [tracks] is
 * the collection that holds the page's tracks in order, and [station] a radio built from the entity.
 */
@Serializable
@SerialName("header")
data class EntityHeader(
    override val id: String,
    val style: HeaderStyle,
    val entity: EntityRef,
    val title: String,
    val artwork: Artwork? = null,
    val details: List<String> = emptyList(),
    val attribution: Attribution? = null,
    val description: String? = null,
    val tracks: EntityRef? = null,
    val station: EntityRef? = null,
) : PageBlock

@Serializable
enum class HeaderStyle {
    PORTRAIT,
    COVER,
}

/** Who made the entity, as in the artist above an album's title. */
@Serializable
data class Attribution(
    val name: String,
    val avatar: Artwork? = null,
    val entity: EntityRef? = null,
)

/**
 * A collection's title, with an optional context line and avatar, as in "SIMILAR TO / Massano".
 * [target] is what the title names, such as that artist.
 */
@Serializable
data class CollectionHeader(
    val title: String,
    val context: String? = null,
    val avatar: Artwork? = null,
    val target: EntityRef? = null,
)

@Serializable
enum class CollectionLayout {
    HORIZONTAL_SHELF,

    /** Compact rows read down each column, then across, as in Quick picks. */
    MULTI_COLUMN_LIST,

    /** One track per row with its number or artwork, credits, album and duration. */
    TRACK_TABLE,
}

@Serializable
enum class ItemView {
    COVER_CARD,
    LANDSCAPE_CARD,
    ARTIST_PORTRAIT,
    TRACK_ROW,
}

/** Page-level filter chips, such as a home feed's moods. Option ids are opaque to the host. */
@Serializable
data class FilterControl(
    val options: List<FilterOption>,
)

@Serializable
data class FilterOption(
    val id: String,
    val label: String,
)
