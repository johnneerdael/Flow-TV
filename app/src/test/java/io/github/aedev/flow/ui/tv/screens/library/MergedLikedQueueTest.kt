package io.github.aedev.flow.ui.tv.screens.library

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.LibraryProvider
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.catalog.ProviderLibraryItem
import io.github.aedev.flow.plugin.catalog.trackDescriptor
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Test

class MergedLikedQueueTest {
    private fun song(
        provider: String,
        id: String = "same",
        kind: EntityKind = EntityKind.TRACK,
    ): ProviderLibraryItem {
        val descriptor = TrackDescriptor(EntityRef(kind, id), provider, ids = mapOf(provider to id))
        return ProviderLibraryItem(
            LibraryProvider(provider, provider, 1, "account", provider),
            MetadataItem(id, descriptor.ref, provider, track = descriptor),
        )
    }

    @Test fun collidingTracksKeepBothOwnersAndRawDescriptors() {
        val queue = mergedLikedQueue(emptyList(), listOf(song("a"), song("b")))
        assertThat(queue.map { it.provider }).containsExactly("a", "b").inOrder()
        assertThat(queue.map { it.videoId }.distinct()).hasSize(2)
        queue.forEach { track ->
            assertThat(track.trackDescriptor()!!.ref.providerId).isEqualTo("same")
            assertThat(ProviderEntityReference.decode(track.videoId)!!.pluginId).isEqualTo(track.provider)
        }
    }

    @Test fun nativeIdsAndNoncollidingProviderTracksKeepTheirIds() {
        val native = MusicTrack("same", "native", "artist", "", 120)
        val queue = mergedLikedQueue(listOf(native), listOf(song("b"), song("a", "other")))
        assertThat(queue.first()).isEqualTo(native)
        assertThat(queue[1].videoId).isNotEqualTo("same")
        assertThat(queue[2].videoId).isEqualTo("other")
    }

    @Test fun trackAndMusicVideoWithTheSameIdKeepDistinctPlaybackDescriptors() {
        val queue = mergedLikedQueue(emptyList(), listOf(song("a"), song("a", kind = EntityKind.MUSIC_VIDEO)))
        assertThat(queue).hasSize(2)
        assertThat(queue.map { it.trackDescriptor()!!.ref.kind }).containsExactly(EntityKind.TRACK, EntityKind.MUSIC_VIDEO)
    }
}
