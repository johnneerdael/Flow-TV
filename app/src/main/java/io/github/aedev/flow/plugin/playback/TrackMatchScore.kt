package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.TrackDescriptor
import kotlin.math.abs

/**
 * How well an audio plugin's track stands for a track another plugin describes, the way Meld matches
 * Spotify to YouTube Music: title (45%), first artist (35%) and duration (20%), each compared after
 * normalisation. A shared ISRC is certain. Live, karaoke, cover and similar uploads the described
 * title does not ask for rank lower, but keep their raw score.
 */
internal object TrackMatchScore {
    /** The least raw score a candidate needs to be played at all. */
    const val MIN_SCORE = 0.35
    private const val CERTAIN = 1.0
    private const val EARLY_EXIT = 0.95
    private const val TITLE_WEIGHT = 0.45
    private const val ARTIST_WEIGHT = 0.35
    private const val DURATION_WEIGHT = 0.20
    private const val UNKNOWN_DURATION = 0.5
    private const val PENALTY_PER_MARKER = 0.15
    private const val MAX_PENALTY = 0.30
    private const val ISRC = "isrc"

    private val featuring = Regex("\\(feat\\..*?\\)")
    private val ft = Regex("\\(ft\\..*?\\)")
    private val brackets = Regex("\\[.*?]")
    private val remaster = Regex("\\(.*?remaster.*?\\)", RegexOption.IGNORE_CASE)
    private val remix = Regex("\\(.*?remix.*?\\)", RegexOption.IGNORE_CASE)
    private val nonAlphanumeric = Regex("[^a-z0-9\\s]")
    private val spaces = Regex("\\s+")
    private val variantMarkers =
        Regex(
            "\\b(live|en vivo|en directo|ao vivo|karaoke|cover|instrumental|sped up|spedup|slowed|nightcore|8d|" +
                "music video|official video|lyric video)\\b",
        )

    /** The candidate that best stands for [track], or null when none scores at least [MIN_SCORE]. */
    fun best(
        track: TrackDescriptor,
        candidates: List<TrackDescriptor>,
    ): Scored? {
        var best: Scored? = null
        var bestRanked = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val score = score(track, candidate)
            if (score < MIN_SCORE) continue
            val ranked = score - variantPenalty(track.title, candidate.title)
            if (ranked > bestRanked) {
                best = Scored(candidate, score)
                bestRanked = ranked
            }
            if (ranked >= EARLY_EXIT) break
        }
        return best
    }

    fun score(
        track: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Double {
        val isrc = track.ids[ISRC]
        if (isrc != null && isrc.equals(candidate.ids[ISRC], ignoreCase = true)) return CERTAIN
        val title = similarity(normalize(track.title), normalize(candidate.title))
        val artist =
            similarity(
                normalize(
                    track.artists
                        .firstOrNull()
                        ?.name
                        .orEmpty(),
                ),
                normalize(
                    candidate.artists
                        .firstOrNull()
                        ?.name
                        .orEmpty(),
                ),
            )
        return title * TITLE_WEIGHT + artist * ARTIST_WEIGHT + durationScore(track.durationMs, candidate.durationMs) * DURATION_WEIGHT
    }

    internal fun normalize(text: String): String =
        text
            .lowercase()
            .replace(featuring, "")
            .replace(ft, "")
            .replace(brackets, "")
            .replace(remaster, "")
            .replace(remix, "")
            .replace(nonAlphanumeric, "")
            .replace(spaces, " ")
            .trim()

    /** The Dice coefficient of the two strings' character bigrams. */
    internal fun similarity(
        a: String,
        b: String,
    ): Double {
        if (a == b) return CERTAIN
        val bigramsA = bigrams(a)
        val bigramsB = bigrams(b)
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
        return 2.0 * bigramsA.count { it in bigramsB } / (bigramsA.size + bigramsB.size)
    }

    private fun bigrams(text: String): Set<String> = if (text.length < 2) emptySet() else text.windowed(2).toSet()

    internal fun durationScore(
        trackMs: Long?,
        candidateMs: Long?,
    ): Double {
        if (trackMs == null || candidateMs == null || trackMs <= 0) return UNKNOWN_DURATION
        val seconds = abs(trackMs / 1000 - candidateMs / 1000)
        return when {
            seconds <= 2 -> 1.0
            seconds <= 5 -> 0.8
            seconds <= 10 -> 0.5
            seconds <= 30 -> 0.2
            else -> 0.0
        }
    }

    private fun variantPenalty(
        title: String,
        candidateTitle: String,
    ): Double {
        val asked = variantMarkers.findAll(title.lowercase()).map { it.value }.toSet()
        val extra = variantMarkers.findAll(candidateTitle.lowercase()).map { it.value }.toSet() - asked
        return (extra.size * PENALTY_PER_MARKER).coerceAtMost(MAX_PENALTY)
    }

    class Scored(
        val candidate: TrackDescriptor,
        val score: Double,
    )
}
