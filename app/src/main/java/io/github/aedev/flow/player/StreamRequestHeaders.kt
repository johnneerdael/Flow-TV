package io.github.aedev.flow.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource

/**
 * Headers the source of a playback asks the host to send: [common] on every request the playback
 * makes, and [byUrl] on the requests for one format's URL, on top of [common].
 */
data class StreamRequestHeaders(
    val common: Map<String, String> = emptyMap(),
    val byUrl: Map<String, Map<String, String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = common.isEmpty() && byUrl.values.all { it.isEmpty() }

    fun forUrl(url: String): Map<String, String> = byUrl[url]?.let { common + it } ?: common

    companion object {
        val NONE = StreamRequestHeaders()
    }
}

/** Adds [headers] to every request this factory's sources open; the factory itself when there are none. */
@UnstableApi
fun DataSource.Factory.withRequestHeaders(headers: StreamRequestHeaders): DataSource.Factory =
    if (headers.isEmpty) {
        this
    } else {
        ResolvingDataSource.Factory(this) { dataSpec ->
            val extra = headers.forUrl(dataSpec.uri.toString())
            if (extra.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(extra)
        }
    }
