package io.github.aedev.flow.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * The music player's sources. A queue item goes to [default] as ever, except a music video, which
 * joins its picture to its sound. Both halves resolve through [dataSourceFactory] by cache key, so the
 * sound is exactly what the song alone would play, visualizer tap included.
 */
@OptIn(UnstableApi::class)
class MusicMediaSourceFactory(
    private val default: MediaSource.Factory,
    dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory by default {
    private val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        if (mediaItem.localConfiguration?.uri?.scheme != MusicVideoItems.SCHEME) return default.createMediaSource(mediaItem)
        val videoId = mediaItem.mediaId
        val sound = progressive.createMediaSource(mediaItem)
        val picture =
            progressive.createMediaSource(
                MediaItem
                    .Builder()
                    .setUri(mediaItem.localConfiguration!!.uri)
                    .setMediaId(videoId)
                    .setCustomCacheKey(MusicVideoItems.videoKey(videoId))
                    .build(),
            )
        // The sound sets the length: a picture that ends early must not cut the song short.
        return MergingMediaSource(true, false, sound, picture)
    }
}
