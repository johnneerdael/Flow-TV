package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicQueueOrigin
import io.github.aedev.flow.data.music.model.MusicTrack
import org.junit.Test

class RadioQueuePolicyTest {
    @Test
    fun `tuning removes future radio only preserving playing occurrence and user queue`() {
        fun track(
            id: String,
            radio: Boolean,
        ) = MusicTrack(id, id, "", "", 10, queueOrigin = if (radio) MusicQueueOrigin.RADIO else MusicQueueOrigin.USER)
        val queue =
            listOf(track("past", true), track("playing", true), track("manual", false), track("radio", true), track("source", false))
        assertThat(RadioQueuePolicy.removable(queue, 1)).containsExactly(3)
    }
}
