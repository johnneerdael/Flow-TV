package nl.neerdael.milkbeat.catalog

import kotlinx.serialization.Serializable

@Serializable
data class PersonalCollectionsRequest(
    val cursor: String? = null,
    val expectedAccountKey: String? = null,
)

@Serializable
enum class PersonalCollectionKind { OWNED_PLAYLIST, LIKED_SONGS }

@Serializable
data class PersonalCollection(
    val ref: EntityRef,
    val title: String,
    val kind: PersonalCollectionKind,
    val revision: String? = null,
    val trackCount: Int? = null,
)

@Serializable
data class PersonalCollectionsPage(
    val collections: List<PersonalCollection>,
    val next: String? = null,
)

@Serializable
data class PrivatePlaylistImportRequest(
    val sourceKey: String,
    val title: String,
    val tracks: List<EntityRef>,
    val target: EntityRef? = null,
    val expectedAccountKey: String? = null,
)

@Serializable
data class PrivatePlaylistImportResult(
    val ref: EntityRef,
)
