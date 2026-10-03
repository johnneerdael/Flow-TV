package io.github.aedev.flow.service

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RadioQueuePolicy

internal object RadioStationPolicy {
    fun eligibleTracks(
        tracks: List<MusicTrack>,
        blockedAliases: Set<String>,
    ): List<MusicTrack> =
        tracks
            .filterNot { track -> RadioQueuePolicy.aliases(track).any { it in blockedAliases } }
            .distinctBy { it.videoId }
}
