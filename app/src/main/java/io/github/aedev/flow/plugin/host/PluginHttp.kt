package io.github.aedev.flow.plugin.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.plugin.HttpRequest
import nl.neerdael.milkbeat.plugin.HttpResponse
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT_MS = 20_000L
private const val MAX_TIMEOUT_MS = 60_000L
private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
private val BODYLESS_METHODS = setOf("GET", "HEAD")

class PluginHttpException(
    message: String,
) : IOException(message)

/**
 * `mb.http.fetch` for one plugin: the app's HTTP client, restricted to the hosts the listener granted.
 * The check runs on every hop, so a redirect cannot lead a plugin anywhere it was not allowed to go.
 * Plugins handle their own cookies through headers; nothing is shared between plugins.
 */
internal class PluginHttp(
    base: OkHttpClient,
    private val allowedHosts: List<String>,
) {
    private val client =
        base
            .newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .addNetworkInterceptor(Interceptor { chain -> chain.proceed(checked(chain.request())) })
            .build()

    suspend fun fetch(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val method = request.method.uppercase()
            val url = checked(Request.Builder().url(request.url).build()).url
            val body =
                request.body?.takeUnless { method in BODYLESS_METHODS }?.toRequestBody(
                    request.headers.entries
                        .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                        ?.value
                        ?.toMediaTypeOrNull(),
                )
            val call =
                client
                    .newBuilder()
                    .followRedirects(request.followRedirects)
                    .followSslRedirects(request.followRedirects)
                    .callTimeout((request.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                            .method(method, body ?: if (method in BODYLESS_METHODS) null else ByteArray(0).toRequestBody())
                            .build(),
                    )
            call.execute().use { response ->
                val length = response.body.contentLength()
                if (length > MAX_RESPONSE_BYTES) throw PluginHttpException("Response of $length bytes is too large")
                val source = response.body.source()
                if (source.request(MAX_RESPONSE_BYTES + 1)) throw PluginHttpException("Response is too large")
                HttpResponse(
                    status = response.code,
                    url = response.request.url.toString(),
                    headers =
                        response.headers.names().associate { name ->
                            val lower = name.lowercase()
                            lower to response.headers.values(name).joinToString(if (lower == "set-cookie") "\n" else ", ")
                        },
                    body = source.buffer.readUtf8(),
                )
            }
        }

    private fun checked(request: Request): Request {
        val url = request.url
        if (!url.isHttps) throw PluginHttpException("Plugins may only use HTTPS: ${url.host}")
        if (!hostAllowed(url.host, allowedHosts)) throw PluginHttpException("${url.host} is not in the plugin's permissions")
        return request
    }
}
