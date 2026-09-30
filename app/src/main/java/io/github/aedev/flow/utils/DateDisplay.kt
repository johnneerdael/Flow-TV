package io.github.aedev.flow.utils

import java.util.Locale

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
