package io.github.aedev.flow.utils

import java.util.Locale

fun parseToTimestamp(text: String?): Long? {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return null
    raw.toLongOrNull()?.takeIf { it > 100_000_000_000L }?.let { return it }

    val cleanRaw =
        raw
            // "Streamed live on Jun 19, 2020" and "Premiered on Jan 1, 2020" carry the date behind
            // words no format string matches, so without dropping them the parse fails and the
            // caller falls back to whatever timestamp it already had.
            .replace(Regex("(?i)^(streamed|premiered)\\s+(live\\s+)?(on\\s+)?"), "")
            .trim()

    val absFormats =
        listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "MMM dd, yyyy",
            "MMM d, yyyy",
            "d MMM yyyy",
            "dd MMM yyyy",
        )
    for (f in absFormats) {
        for (loc in listOf(Locale.US, Locale.getDefault())) {
            try {
                val sdf = java.text.SimpleDateFormat(f, loc)
                sdf.isLenient = false
                val d = sdf.parse(cleanRaw)
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
    }
    return parseRelativeToTimestamp(cleanRaw)
}

internal fun parseRelativeToTimestamp(
    text: String,
    now: Long = System.currentTimeMillis(),
): Long? {
    val n =
        text
            .lowercase(Locale.US)
            .replace("streamed", "")
            .replace("premiered", "")
            .replace("live", "")
            .replace("ago", "")
            .trim()
    if (n.isBlank()) return null
    if (n.contains("just now") || n.contains("today")) return now
    if (n.contains("yesterday")) return now - 86_400_000L

    val compactMatch =
        Regex("""(\d+)\s*(mo|sec|secs|second|seconds|min|mins|minute|minutes|hr|hrs|hour|hours|[smhdwy])\b""")
            .find(n)
    val value =
        compactMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: Regex("(\\d+)")
                .find(n)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
            ?: return null
    val compactUnit = compactMatch?.groupValues?.getOrNull(2)
    val unitMillis =
        when {
            compactUnit in listOf("s", "sec", "secs", "second", "seconds") || n.contains("second") -> 1_000L
            compactUnit in listOf("m", "min", "mins", "minute", "minutes") || n.contains("minute") -> 60_000L
            compactUnit in listOf("h", "hr", "hrs", "hour", "hours") || n.contains("hour") -> 3_600_000L
            compactUnit == "d" || n.contains("day") -> 86_400_000L
            compactUnit == "w" || n.contains("week") -> 7L * 86_400_000L
            compactUnit == "mo" || n.contains("month") -> 30L * 86_400_000L
            compactUnit == "y" || n.contains("year") -> 365L * 86_400_000L
            else -> return null
        }
    return now - value * unitMillis
}
