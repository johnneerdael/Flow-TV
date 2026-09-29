package io.github.aedev.flow.plugin.catalog

import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.PluginJson

/**
 * A plugin's track as the player's [MusicTrack]. The track keeps its plugin and full descriptor, so
 * any audio plugin can resolve it later; its id is the describing plugin's own, which for YouTube is
 * the bare video id the caches and history already use.
 */
fun TrackDescriptor.toMusicTrack(pluginId: String): MusicTrack =
    MusicTrack(
        videoId = ref.providerId,
        title = title,
        artist = artists.joinToString(", ") { it.name },
        thumbnailUrl = artwork?.url.orEmpty(),
        duration = ((durationMs ?: 0L) / 1000).toInt(),
        album = album.orEmpty(),
        albumId = albumRef?.providerId,
        channelId = artists.firstNotNullOfOrNull { it.entity?.providerId }.orEmpty(),
        isExplicit = explicit,
        isVideoSong = hasVideo || ref.kind == EntityKind.MUSIC_VIDEO,
        artists = artists.map { MusicArtist(name = it.name, id = it.entity?.providerId) },
        provider = pluginId,
        descriptor = PluginJson.encodeToString(TrackDescriptor.serializer(), this),
    )

/** The descriptor a plugin gave this track, or null for a track no plugin described (a local file). */
fun MusicTrack.trackDescriptor(): TrackDescriptor? =
    descriptor?.let { runCatching { PluginJson.decodeFromString(TrackDescriptor.serializer(), it) }.getOrNull() }
