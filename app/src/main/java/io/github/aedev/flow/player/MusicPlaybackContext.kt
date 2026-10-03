package io.github.aedev.flow.player

import kotlinx.serialization.Serializable

@Serializable
data class MusicPlaybackContext(
    val sourceCollectionId: String,
    val radioCollectionId: String,
    val audioProviderId: String,
    val preferCollectionRadio: Boolean = true,
)
