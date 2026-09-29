package nl.neerdael.milkbeat.catalog

import kotlinx.serialization.Serializable

/**
 * One occurrence of an entity on a page. [id] identifies the occurrence, [entity] what it points
 * at: the same album can appear in two shelves under two ids. A playable item carries its [track],
 * so the host can queue it without asking the plugin again.
 */
@Serializable
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
    /** Further short lines under the subtitle, such as a video's views and age. */
    val details: List<String> = emptyList(),
    val live: Boolean = false,
    val track: TrackDescriptor? = null,
)

/** An entity in the plugin that produced the page; the host keeps which plugin that was. */
@Serializable
data class EntityRef(
    val kind: EntityKind,
    val providerId: String,
)

@Serializable
enum class EntityKind {
    TRACK,
    MUSIC_VIDEO,
    ALBUM,
    PLAYLIST,
    ARTIST,

    /** A listener's or creator's own profile. */
    PROFILE,

    /** A generated collection that can change, such as a daily mix. */
    MIX,

    /** An endless station seeded from something. */
    RADIO,

    /** A video outside music: a set, a concert, a talk. Played by the video role. */
    VIDEO,

    /** Who publishes videos. */
    CHANNEL,
    ;

    val isPlayable: Boolean
        get() = this == TRACK || this == MUSIC_VIDEO || this == VIDEO

    /** People are drawn round, everything else square. */
    val isPerson: Boolean
        get() = this == ARTIST || this == PROFILE || this == CHANNEL
}

@Serializable
data class ArtistCredit(
    val name: String,
    val entity: EntityRef? = null,
)

@Serializable
data class Artwork(
    val url: String,
)
