package nl.neerdael.milkbeat.catalog

import kotlinx.serialization.Serializable

/**
 * An entity's page: an artist, album, playlist, mix, channel or profile. [filterId] selects one of the
 * page's filter options (a channel's tabs, for instance); [cursor] continues the page.
 */
@Serializable
data class PageRequest(
    val entity: EntityRef,
    val filterId: String? = null,
    val cursor: String? = null,
)

/**
 * A search. [filterId] is one of the filter options the plugin returned with the unfiltered results
 * (songs, albums, channels, live …); without it the plugin answers with its mixed sections.
 */
@Serializable
data class SearchRequest(
    val query: String,
    val filterId: String? = null,
    val cursor: String? = null,
)

@Serializable
data class SuggestRequest(
    val query: String,
)

@Serializable
data class Suggestions(
    val queries: List<String>,
)

/** A section of the signed-in listener's library, such as history or liked songs. */
@Serializable
data class LibraryRequest(
    val section: String? = null,
    val cursor: String? = null,
)

/** Every playable track of a collection, in order, for the queue. */
@Serializable
data class TracksRequest(
    val entity: EntityRef,
    val cursor: String? = null,
)

/**
 * What a radio continues from: a track (its mix), a collection (its similar content) or a station
 * the plugin named, such as an entity header's `station`. Anything the plugin needs to build the radio
 * (a collection's automix, say) it looks up from [seed] itself. [cursor] continues a radio already
 * started.
 */
@Serializable
data class RadioRequest(
    val seed: EntityRef,
    val cursor: String? = null,
    val filterId: String? = null,
)
