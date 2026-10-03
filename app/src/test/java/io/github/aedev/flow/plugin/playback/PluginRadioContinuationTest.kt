package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Test

class PluginRadioContinuationTest {
    private val host = mockk<PluginHost>()
    private val registry = mockk<PluginRegistry>()
    private val audio = mockk<PluginAudio>()
    private val accounts = PluginAccounts(host)
    private val radio = PluginRadio(host, registry, audio, accounts)
    private val tuning = RadioTuningCoordinator(registry, accounts)
    private val seed = EntityRef(EntityKind.TRACK, "seed")
    private val controls = FilterControl(listOf(FilterOption("all", "All"), FilterOption("discover", "Discover")))
    private val previous =
        RadioPage("provider", TrackList(emptyList(), "cursor", filters = controls, selectedFilterId = "discover"), seed, false)
    private val nextTrack = TrackDescriptor(EntityRef(EntityKind.TRACK, "next-recording"), "Next recording")

    init {
        every { registry.state } returns MutableStateFlow(PluginRegistryState())
    }

    @Test
    fun `continuation omitting filter metadata preserves station controls and selection`() =
        runTest {
            val next = TrackList(listOf(nextTrack), "next-cursor")
            coEvery { host.call("provider", PluginOperations.radio, any()) } returns next
            tuning.reset(1)
            tuning.station(previous, 1)

            val result = radio.next(previous, "cursor")
            tuning.station(result, 1)

            assertThat(tuning.state.value.choices).containsExactlyElementsIn(controls.options).inOrder()
            assertThat(tuning.state.value.selectedId).isEqualTo("discover")
            assertThat(result.tracks.tracks).containsExactly(nextTrack)
            assertThat(result.tracks.next).isEqualTo("next-cursor")
        }

    @Test
    fun `continuation applies explicit replacement controls and selected mode`() =
        runTest {
            val replacement = FilterControl(listOf(FilterOption("popular", "Popular")))
            coEvery { host.call("provider", PluginOperations.radio, any()) } returns
                TrackList(listOf(nextTrack), filters = replacement, selectedFilterId = "popular")
            tuning.reset(1)
            tuning.station(previous, 1)

            tuning.station(radio.next(previous, "cursor"), 1)

            assertThat(tuning.state.value.choices).containsExactly(FilterOption("popular", "Popular"))
            assertThat(tuning.state.value.selectedId).isEqualTo("popular")
        }

    @Test
    fun `replacement controls cannot inherit a removed selected mode`() =
        runTest {
            coEvery { host.call("provider", PluginOperations.radio, any()) } returns
                TrackList(listOf(nextTrack), filters = FilterControl(listOf(FilterOption("popular", "Popular"))))

            val result = radio.next(previous, "cursor")

            assertThat(result.tracks.filters?.options).containsExactly(FilterOption("popular", "Popular"))
            assertThat(result.tracks.selectedFilterId).isNull()
        }

    @Test
    fun `replacement controls preserve an omitted selection still offered by the provider`() =
        runTest {
            val replacement = FilterControl(listOf(FilterOption("discover", "Explore")))
            coEvery { host.call("provider", PluginOperations.radio, any()) } returns
                TrackList(listOf(nextTrack), filters = replacement)

            val result = radio.next(previous, "cursor")

            assertThat(result.tracks.filters?.options).containsExactly(FilterOption("discover", "Explore"))
            assertThat(result.tracks.selectedFilterId).isEqualTo("discover")
        }

    @Test
    fun `explicit empty continuation controls clear station modes`() =
        runTest {
            coEvery { host.call("provider", PluginOperations.radio, any()) } returns
                TrackList(listOf(nextTrack), filters = FilterControl(emptyList()))
            tuning.reset(1)
            tuning.station(previous, 1)

            tuning.station(radio.next(previous, "cursor"), 1)

            assertThat(tuning.state.value.choices).isEmpty()
            assertThat(tuning.state.value.selectedId).isNull()
        }

    @Test
    fun `continuation with a selected mode only preserves existing controls`() =
        runTest {
            coEvery { host.call("provider", PluginOperations.radio, any()) } returns
                TrackList(listOf(nextTrack), selectedFilterId = "all")

            val result = radio.next(previous, "cursor")

            assertThat(result.tracks.filters?.options).containsExactlyElementsIn(controls.options).inOrder()
            assertThat(result.tracks.selectedFilterId).isEqualTo("all")
        }

    @Test
    fun `retuning without metadata cannot inherit earlier station modes`() =
        runTest {
            coEvery { host.call("provider", PluginOperations.radio, any()) } returns TrackList(listOf(nextTrack))
            tuning.reset(1)
            tuning.station(previous, 1)

            val result = radio.tune(previous, "all")
            tuning.reset(2)
            tuning.station(result, 2)

            assertThat(tuning.state.value.choices).isEmpty()
            assertThat(tuning.state.value.selectedId).isNull()
        }

    @Test
    fun `new seed without metadata cannot inherit continuation modes`() =
        runTest {
            coEvery { host.call("provider", PluginOperations.radio, any()) } returns TrackList(listOf(nextTrack))
            tuning.reset(1)
            tuning.station(radio.next(previous, "cursor"), 1)

            tuning.station(RadioPage("provider", TrackList(listOf(nextTrack)), EntityRef(EntityKind.TRACK, "new-seed"), false), 1)

            assertThat(tuning.state.value.choices).isEmpty()
            assertThat(tuning.state.value.selectedId).isNull()
        }
}
