package nl.neerdael.milkbeat.catalog

/**
 * One occurrence of an entity on a page. [id] identifies the occurrence, [entity] what it points
 * at: the same album can appear in two shelves under two ids.
 */
data class MetadataItem(
    val id: String,
    val entity: EntityRef,
    val title: String,
    val subtitle: String? = null,
    val artwork: Artwork? = null,
    val view: ItemView? = null,
    val artists: List<ArtistCredit> = emptyList(),
    val durationSeconds: Int? = null,
    val explicit: Boolean = false,
    val ordinal: Int? = null,
    val album: String? = null,
)

data class EntityRef(
    val kind: EntityKind,
    val providerId: String,
)

enum class EntityKind {
    TRACK,
    MUSIC_VIDEO,
    ALBUM,
    PLAYLIST,
    ARTIST,

    /** A listener's or creator's own profile. */
    PROFILE,
    ;

    val isPlayable: Boolean
        get() = this == TRACK || this == MUSIC_VIDEO

    /** People are drawn round, everything else square. */
    val isPerson: Boolean
        get() = this == ARTIST || this == PROFILE
}

data class ArtistCredit(
    val name: String,
    val entity: EntityRef? = null,
)

data class Artwork(
    val url: String,
)
