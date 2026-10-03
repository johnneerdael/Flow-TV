package io.github.aedev.flow.player

import io.github.aedev.flow.data.music.model.MusicQueueOrigin
import io.github.aedev.flow.data.music.model.MusicTrack

internal object RadioQueuePolicy {
    fun removable(
        queue: List<MusicTrack>,
        currentIndex: Int,
    ): List<Int> =
        queue.indices.filter {
            it > currentIndex && queue[it].queueOrigin == MusicQueueOrigin.RADIO
        }

    fun aliases(track: MusicTrack): Set<String> =
        setOf(track.videoId) +
            MusicVideoItems
                .descriptor(track)
                .ids
                .filterKeys { it != "isrc" }
                .values
}
