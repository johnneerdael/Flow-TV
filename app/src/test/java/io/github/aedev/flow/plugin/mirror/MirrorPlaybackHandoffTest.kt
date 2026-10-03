package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import org.junit.Test
import java.util.concurrent.TimeUnit

class MirrorPlaybackHandoffTest {
    private val key = MirrorKey("source", "a", "target", "b", EntityRef(EntityKind.PLAYLIST, "playlist"))
    private val record =
        MirrorRecord(key, "Playlist", "revision", emptyList(), destination = EntityRef(EntityKind.PLAYLIST, "copy"), ready = true)

    @Test
    fun `handoff expires sixty seconds after verification and can only be consumed once`() {
        var now = 0L
        val handoff = MirrorPlaybackHandoff { now }
        handoff.offer(record, "context")
        now = TimeUnit.SECONDS.toNanos(59)
        assertThat(handoff.take(key, "Playlist", "context")).isSameInstanceAs(record)
        assertThat(handoff.take(key, "Playlist", "context")).isNull()
        handoff.offer(record, "context")
        now += TimeUnit.SECONDS.toNanos(60)
        assertThat(handoff.take(key, "Playlist", "context")).isNull()
    }

    @Test
    fun `changed playlist title account or plugin context cannot reuse the handoff`() {
        val handoff = MirrorPlaybackHandoff()
        for ((changedKey, title, context) in listOf(
            Triple(key.copy(sourceAccount = "other"), "Playlist", "context"),
            Triple(key, "Renamed", "context"),
            Triple(key, "Playlist", "updated-plugin"),
        )) {
            handoff.offer(record, "context")
            assertThat(handoff.take(changedKey, title, context)).isNull()
        }
    }

    @Test
    fun `incomplete or destinationless preparation does not produce a handoff`() {
        val handoff = MirrorPlaybackHandoff()
        for (invalid in listOf(record.copy(ready = false), record.copy(destination = null))) {
            handoff.offer(invalid, "context")
            assertThat(handoff.take(key, "Playlist", "context")).isNull()
        }
    }

    @Test
    fun `late playback cleanup does not remove a newer verified result`() {
        val handoff = MirrorPlaybackHandoff()
        val newer = record.copy(revision = "newer")
        handoff.offer(newer, "context")
        handoff.clear(record)
        assertThat(handoff.take(key, "Playlist", "context")).isSameInstanceAs(newer)
        handoff.offer(record, "context")
        handoff.clear()
        assertThat(handoff.take(key, "Playlist", "context")).isNull()
    }
}
