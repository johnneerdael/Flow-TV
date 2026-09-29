package io.github.aedev.flow.player

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.stream.ResolvedStreamData

/** Where the player resolves a video it moves to on its own: queue advance, autoplay and gapless preload. */
fun interface VideoStreamSource {
    /** Everything needed to play [video], or null when its streams cannot be had. */
    suspend fun resolve(video: Video): ResolvedStreamData?
}
