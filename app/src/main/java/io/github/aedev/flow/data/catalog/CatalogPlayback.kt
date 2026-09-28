package io.github.aedev.flow.data.catalog

import io.github.aedev.flow.data.music.model.MusicTrack
import nl.neerdael.milkbeat.catalog.MetadataItem

/**
 * Which of the active provider's items the player can take, and as what. Pages only describe
 * content; whether an id can be played is the app's decision, bound per provider.
 */
fun interface CatalogPlayback {
    fun track(item: MetadataItem): MusicTrack?
}
