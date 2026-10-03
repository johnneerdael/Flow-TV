package io.github.aedev.flow.service

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicQueueOrigin
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Test

class RadioStationPolicyTest {
    @Test
    fun `tuned station contains each recording once in provider order`() {
        val first = track("recording", "First occurrence")
        val second = track("second", "Second recording")
        val duplicate = track("recording", "Duplicate occurrence")

        assertThat(RadioStationPolicy.eligibleTracks(listOf(first, second, duplicate), emptySet()))
            .containsExactly(first, second)
            .inOrder()
    }

    @Test
    fun `station excludes seed and aliases of retained queue tracks`() {
        val alias =
            TrackDescriptor(
                ref = EntityRef(EntityKind.TRACK, "native-recording"),
                title = "Native recording",
                ids = mapOf("spotify" to "native-recording", "ytm" to "playing-video"),
            ).toMusicTrack("spotify").copy(queueOrigin = MusicQueueOrigin.RADIO)
        val seed = track("seed", "Seed")
        val next = track("next", "Next")

        assertThat(RadioStationPolicy.eligibleTracks(listOf(alias, seed, next), setOf("playing-video", "seed")))
            .containsExactly(next)
    }

    private fun track(
        id: String,
        title: String,
    ) = MusicTrack(id, title, "", "", 10, queueOrigin = MusicQueueOrigin.RADIO)
}
