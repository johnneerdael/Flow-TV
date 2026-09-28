package io.github.aedev.flow.data.catalog.youtube

import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.MetadataItem
import javax.inject.Inject

/** YouTube Music tracks and videos play as they are: the entity id is the video id. */
class YouTubeCatalogPlayback
    @Inject
    constructor() : CatalogPlayback {
        override fun track(item: MetadataItem): MusicTrack? {
            if (!item.entity.kind.isPlayable) return null
            return MusicTrack(
                videoId = item.entity.providerId,
                title = item.title,
                artist = item.artists.joinToString(", ") { it.name },
                thumbnailUrl = item.artwork?.url.orEmpty(),
                duration = item.durationSeconds ?: 0,
                channelId = item.artists.firstNotNullOfOrNull { it.entity?.providerId }.orEmpty(),
                isExplicit = item.explicit,
                isVideoSong = item.entity.kind == EntityKind.MUSIC_VIDEO,
                artists = item.artists.map { MusicArtist(name = it.name, id = it.entity?.providerId) },
            )
        }
    }
