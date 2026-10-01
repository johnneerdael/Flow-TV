package io.github.aedev.flow.plugin.install

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun pluginUrl(value: String): HttpUrl? {
    val input = value.trim()
    if (input.isEmpty() || input.any { it.isWhitespace() || it.isISOControl() }) return null
    if ((input.startsWith("https:", ignoreCase = true) || input.startsWith("http:", ignoreCase = true)) &&
        !input.contains("://")
    ) {
        return null
    }
    val url = (if (input.contains("://")) input else "https://$input").toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url
}
