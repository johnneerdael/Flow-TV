package io.github.aedev.flow.plugin.host

/**
 * Whether [host] is covered by one of [patterns]: an exact host (`music.youtube.com`) or a wildcard
 * for its subdomains (`*.googlevideo.com`, which does not cover `googlevideo.com` itself).
 */
internal fun hostAllowed(
    host: String,
    patterns: List<String>,
): Boolean {
    val name = host.lowercase().trimEnd('.')
    return patterns.any { raw ->
        val pattern = raw.lowercase().trimEnd('.')
        if (pattern.startsWith("*.")) name.endsWith(pattern.substring(1)) else name == pattern
    }
}
