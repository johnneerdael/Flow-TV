package io.github.aedev.flow.player.error

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/** The URL and HTTP status a playback error came from, when a stream request is what failed. */
@UnstableApi
internal object StreamHttpFailure {
    fun of(error: PlaybackException): Pair<String, Int>? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.dataSpec.uri.toString() to cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }
}
