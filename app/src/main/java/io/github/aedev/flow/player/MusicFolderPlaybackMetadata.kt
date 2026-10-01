package io.github.aedev.flow.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.folders.MusicFolderMetadata
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(UnstableApi::class)
class MusicFolderPlaybackMetadata
    @Inject
    constructor(
        private val metadata: MusicFolderMetadata,
    ) {
        suspend fun enrichCurrent(track: MusicTrack) {
            val tagged = withContext(PerformanceDispatcher.diskIO) { metadata.enrich(track) }
            if (tagged == track) return
            withContext(Dispatchers.Main) {
                val manager = EnhancedMusicPlayerManager
                if (manager.currentTrack.value?.videoId != track.videoId) return@withContext
                manager.queueState.update { tracks -> tracks.map { if (it.videoId == tagged.videoId) tagged else it } }
                val player = manager.player
                val index = player?.currentMediaItemIndex ?: -1
                if (player != null && index in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(index)
                    if (item.mediaId == tagged.videoId) {
                        player.replaceMediaItem(
                            index,
                            item
                                .buildUpon()
                                .setUri(manager.streamUri(tagged))
                                .setMediaMetadata(
                                    item.mediaMetadata
                                        .buildUpon()
                                        .setTitle(tagged.title)
                                        .setArtist(tagged.artist)
                                        .setAlbumTitle(tagged.album)
                                        .setArtworkUri(tagged.thumbnailUrl.takeIf(String::isNotEmpty)?.let(Uri::parse))
                                        .build(),
                                ).build(),
                        )
                    }
                }
                manager.currentTrackState.value = tagged
            }
        }
    }
