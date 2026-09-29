package io.github.aedev.flow.data.update

private const val RELEASES_URL = "https://github.com/johnneerdael/Milkbeat/releases"
private const val SHA256_HEX_LENGTH = 64

/** The APK every release carries under the same name; it runs on every ABI. */
internal const val RELEASE_APK = "milkbeat-universal.apk"
internal const val RELEASE_CHECKSUMS = "checksums.txt"

/** Always redirects to [RELEASE_APK] of the newest release, so its redirect names the newest tag. */
internal const val LATEST_RELEASE_APK_URL = "$RELEASES_URL/latest/download/$RELEASE_APK"

internal fun releaseAssetUrl(
    tag: String,
    name: String,
): String = "$RELEASES_URL/download/$tag/$name"

/** The tag in the stable link's redirect, `…/releases/download/<tag>/milkbeat-universal.apk`. */
internal fun releaseTagFromRedirect(location: String?): String? {
    val parts = location?.substringBefore('?')?.split('/') ?: return null
    val download = parts.indexOf("download")
    if (download < 0 || parts.getOrNull(download + 2) != RELEASE_APK) return null
    return parts.getOrNull(download + 1)?.takeIf { it.isNotBlank() }
}

/** [name]'s SHA-256 in a `sha256sum` listing, or null when the listing has no well-formed entry for it. */
internal fun releaseChecksum(
    checksums: String,
    name: String,
): String? =
    checksums
        .lineSequence()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }
        ?.first()
        ?.lowercase()
        ?.takeIf { hash -> hash.length == SHA256_HEX_LENGTH && hash.all { it in '0'..'9' || it in 'a'..'f' } }

/** The release [tag] names, when it is newer than [currentVersion]; its APK is checked against [sha256]. */
internal fun releaseIfNewer(
    tag: String,
    currentVersion: String,
    sha256: String,
): AppRelease? {
    if (!AppVersions.isNewer(tag, currentVersion)) return null
    return AppRelease(
        version = AppVersions.normalize(tag),
        tag = tag,
        notes = "",
        publishedAt = null,
        pageUrl = "$RELEASES_URL/tag/$tag",
        apk = ReleaseApk(name = RELEASE_APK, url = releaseAssetUrl(tag, RELEASE_APK), sizeBytes = 0, sha256 = sha256),
    )
}
