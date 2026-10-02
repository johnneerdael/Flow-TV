package io.github.aedev.flow.ui.tv.screens.library

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.catalog.ProviderLibraryItem
import io.github.aedev.flow.plugin.catalog.trackDescriptor
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef

private data class LikedSong(
    val provider: String?,
    val ref: EntityRef,
    val track: MusicTrack,
)

internal fun mergedLikedQueue(
    local: List<MusicTrack>,
    remote: List<ProviderLibraryItem>,
): List<MusicTrack> {
    val songs =
        local.map { track ->
            LikedSong(track.provider, track.trackDescriptor()?.ref ?: EntityRef(EntityKind.TRACK, track.videoId), track)
        } +
            remote.mapNotNull { item ->
                item.playableTrack?.let { LikedSong(item.provider.id, item.item.track!!.ref, it) }
            }
    val unique = songs.distinctBy { it.provider to it.ref }
    val collisions =
        unique
            .groupingBy { it.track.videoId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return unique.map { song ->
        val track = song.track
        if (track.videoId in collisions && track.descriptor != null && song.provider != null) {
            track.copy(videoId = ProviderEntityReference.encode(song.provider, song.ref))
        } else {
            track
        }
    }
}

internal fun selectedLikedTrack(
    track: MusicTrack,
    queue: List<MusicTrack>,
): MusicTrack {
    val ref = track.trackDescriptor()?.ref
    val scopedId = if (track.provider != null && ref != null) ProviderEntityReference.encode(track.provider, ref) else null
    return queue.first { it.provider == track.provider && (it.videoId == track.videoId || it.videoId == scopedId) }
}
