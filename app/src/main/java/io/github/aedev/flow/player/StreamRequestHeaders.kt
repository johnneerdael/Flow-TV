package io.github.aedev.flow.player

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import java.io.InterruptedIOException

/**
 * What the source of a playback asks of its requests: [common] headers on every request, [byUrl] on
 * the requests for one format's URL on top of [common], and none before [opensAtElapsedMs]
 * ([SystemClock.elapsedRealtime]), when a provider opens its URLs only some time after answering.
 */
data class StreamRequestHeaders(
    val common: Map<String, String> = emptyMap(),
    val byUrl: Map<String, Map<String, String>> = emptyMap(),
    val opensAtElapsedMs: Long = 0L,
) {
    val isEmpty: Boolean get() = common.isEmpty() && byUrl.values.all { it.isEmpty() } && opensAtElapsedMs == 0L

    fun forUrl(url: String): Map<String, String> = byUrl[url]?.let { common + it } ?: common

    companion object {
        val NONE = StreamRequestHeaders()
    }
}

/**
 * Adds [headers] to every request this factory's sources open, holding a request made before the URLs
 * open until they do (on the loader's thread, which a cancelled load interrupts); the factory itself
 * when there is nothing to add.
 */
@UnstableApi
fun DataSource.Factory.withRequestHeaders(headers: StreamRequestHeaders): DataSource.Factory =
    if (headers.isEmpty) {
        this
    } else {
        ResolvingDataSource.Factory(this) { dataSpec ->
            val waitMs = headers.opensAtElapsedMs - SystemClock.elapsedRealtime()
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Cancelled while the stream had not opened yet")
                }
            }
            val extra = headers.forUrl(dataSpec.uri.toString())
            if (extra.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(extra)
        }
    }
