package io.github.aedev.flow.data.update

import java.time.Instant

/** A published Milkbeat release that is newer than the running build. */
data class AppRelease(
    val version: String,
    val tag: String,
    val notes: String,
    val publishedAt: Instant?,
    val pageUrl: String,
    val apk: ReleaseApk?,
)

/** The APK to install: the release's universal build, with the SHA-256 its checksum listing gives. */
data class ReleaseApk(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/** Orders dotted version names, ignoring a leading `v` and anything after the first `-`. */
object AppVersions {
    fun normalize(version: String): String = version.trim().removePrefix("v").substringBefore('-')

    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        val candidateParts = normalize(candidate).split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = normalize(current).split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(candidateParts.size, currentParts.size)) {
            val a = candidateParts.getOrElse(i) { 0 }
            val b = currentParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
