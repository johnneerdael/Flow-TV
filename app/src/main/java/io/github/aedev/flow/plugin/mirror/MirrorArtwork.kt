package io.github.aedev.flow.plugin.mirror

import io.github.aedev.flow.plugin.host.PluginHttp
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.runtime.PluginCallException
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.PlaylistArtwork
import nl.neerdael.milkbeat.plugin.HttpBodyEncoding
import nl.neerdael.milkbeat.plugin.HttpRequest
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.Base64
import javax.inject.Inject

class MirrorArtwork
    @Inject
    constructor(
        private val client: OkHttpClient,
    ) {
        suspend fun fetch(
            source: InstalledPlugin,
            artwork: Artwork?,
        ): PlaylistArtwork? {
            if (artwork == null) return null

            fun fail(code: PluginErrorCode): Nothing =
                throw PluginCallException(source.id, PluginError(code, "Could not load the source playlist artwork"))
            val response =
                try {
                    PluginHttp(client, source.grantedNetwork).fetch(
                        HttpRequest(artwork.url, responseEncoding = HttpBodyEncoding.BASE64),
                    )
                } catch (e: IOException) {
                    fail(PluginErrorCode.NETWORK)
                }
            if (response.status == 429) fail(PluginErrorCode.RATE_LIMITED)
            if (response.status >= 500) fail(PluginErrorCode.UNAVAILABLE)
            if (response.status !in 200..299) fail(PluginErrorCode.NOT_FOUND)
            val mime = response.headers["content-type"]?.substringBefore(';') ?: fail(PluginErrorCode.UNSUPPORTED)
            val size = Base64.getDecoder().decode(response.body).size
            if (size !in 1..5 * 1024 * 1024 || !mime.startsWith("image/")) fail(PluginErrorCode.UNSUPPORTED)
            return PlaylistArtwork(response.body, mime, size)
        }
    }
