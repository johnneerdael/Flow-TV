package io.github.aedev.flow.extensions

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters

fun Player.togglePlayPause() {
    if (!playWhenReady && playbackState == Player.STATE_IDLE) {
        prepare()
    }
    playWhenReady = !playWhenReady
}

val Player.mediaItems: List<MediaItem>
    get() =
        object : AbstractList<MediaItem>() {
            override val size: Int
                get() = mediaItemCount

            override fun get(index: Int): MediaItem = getMediaItemAt(index)
        }

fun Player.setOffloadEnabled(enabled: Boolean) {
    trackSelectionParameters =
        trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences
                    .Builder()
                    .setAudioOffloadMode(
                        if (enabled) {
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                        } else {
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                        },
                    ).build(),
            ).build()
}

val Player.isPlayable: Boolean
    get() = playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
