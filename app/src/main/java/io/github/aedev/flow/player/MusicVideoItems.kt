package io.github.aedev.flow.player

/**
 * How a music video travels through the music player. Its queue item uses its own URI scheme, so the
 * service builds it as picture plus sound; the picture half is cached and resolved under its own key.
 */
object MusicVideoItems {
    const val SCHEME = "musicvideo"
    private const val VIDEO_KEY_SUFFIX = "#video"

    fun uri(videoId: String): String = "$SCHEME://$videoId"

    fun videoKey(videoId: String): String = videoId + VIDEO_KEY_SUFFIX

    /** The video id a cache key names when it is the picture half of a music video, else null. */
    fun videoIdOfVideoKey(key: String): String? = key.takeIf { it.endsWith(VIDEO_KEY_SUFFIX) }?.removeSuffix(VIDEO_KEY_SUFFIX)
}
