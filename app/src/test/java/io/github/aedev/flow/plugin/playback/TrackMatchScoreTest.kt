package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Test

class TrackMatchScoreTest {
    private fun track(
        title: String,
        artist: String,
        seconds: Long?,
        ids: Map<String, String> = emptyMap(),
        id: String = title,
    ) = TrackDescriptor(
        ref = EntityRef(EntityKind.TRACK, id),
        title = title,
        artists = listOf(ArtistCredit(artist)),
        durationMs = seconds?.let { it * 1000 },
        ids = ids,
    )

    private val spotify = track("Sky and Sand", "Paul Kalkbrenner", 238, mapOf("spotify" to "4uLU"))

    @Test
    fun `the same song by the same artist at the same length is nearly certain`() {
        assertThat(TrackMatchScore.score(spotify, track("Sky and Sand", "Paul Kalkbrenner", 239))).isAtLeast(0.95)
    }

    @Test
    fun `featuring credits, remaster notes and punctuation do not count against a match`() {
        val candidate = track("Sky & Sand (feat. Fritz Kalkbrenner) [2021 Remaster]", "Paul Kalkbrenner", 238)
        assertThat(TrackMatchScore.score(spotify, candidate)).isAtLeast(0.8)
    }

    @Test
    fun `another artist's song of the same name scores well below the same artist's`() {
        val same = TrackMatchScore.score(spotify, track("Sky and Sand", "Paul Kalkbrenner", 238))
        val other = TrackMatchScore.score(spotify, track("Sky and Sand", "Kygo", 238))
        assertThat(other).isLessThan(same - 0.2)
    }

    @Test
    fun `an unknown length is neutral, and a closer length never scores lower`() {
        assertThat(TrackMatchScore.durationScore(238_000, null)).isEqualTo(0.5)
        val scores = listOf(0L, 3, 7, 20, 60).map { TrackMatchScore.durationScore(238_000, (238 + it) * 1000) }
        assertThat(scores).isInOrder(Comparator.reverseOrder<Double>())
    }

    @Test
    fun `a shared ISRC is certain whatever the titles say`() {
        val isrc = track("Sky and Sand", "Paul Kalkbrenner", 238, mapOf("isrc" to "DEA620900166"))
        val candidate = track("Sky & Sand - Original", "Paul K", 120, mapOf("isrc" to "dea620900166"))
        assertThat(TrackMatchScore.score(isrc, candidate)).isEqualTo(1.0)
    }

    @Test
    fun `a studio upload is picked over a live one the listener did not ask for`() {
        val live = track("Sky and Sand (Live)", "Paul Kalkbrenner", 238, id = "live")
        val studio = track("Sky and Sand", "Paul Kalkbrenner", 244, id = "studio")
        assertThat(
            TrackMatchScore
                .best(spotify, listOf(live, studio))
                ?.candidate
                ?.ref
                ?.providerId,
        ).isEqualTo("studio")
    }

    @Test
    fun `nothing is played when no candidate is close enough`() {
        assertThat(TrackMatchScore.best(spotify, listOf(track("Blue Monday", "New Order", 450)))).isNull()
        assertThat(TrackMatchScore.best(spotify, emptyList())).isNull()
    }

    @Test
    fun `a track is known by its ISRC when it has one, else by all its ids`() {
        assertThat(PluginTrackMatcher.fingerprint(track("a", "b", 1, mapOf("isrc" to "de1", "spotify" to "x")))).isEqualTo("isrc:DE1")
        assertThat(
            PluginTrackMatcher.fingerprint(track("a", "b", 1, mapOf("spotify" to "x", "beatport" to "9"))),
        ).isEqualTo("beatport:9,spotify:x")
        assertThat(PluginTrackMatcher.fingerprint(track("a", "b", 1, id = "r"))).isEqualTo("ref:TRACK:r")
    }
}
