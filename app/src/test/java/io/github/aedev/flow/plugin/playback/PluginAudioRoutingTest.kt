package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PluginAudioRoutingTest {
    private val original = matchTrack("spotify:song", "spotify")
    private val unavailable = matchTrack("unavailable", "youtube")
    private val candidate = matchTrack("youtube-song", "youtube")
    private val host = mockk<PluginHost>()
    private val registry = mockk<PluginRegistry>()
    private val matcher = PluginTrackMatcher(host, MemoryTrackMatches())
    private val audio = PluginAudio(host, registry, matcher)
    private val radio = PluginRadio(host, registry, audio)
    private val stream = AudioStream("https://example.invalid/audio", "song", "audio", "audio/mp4", trackingToken = "listen")
    private val plugin =
        InstalledPlugin(
            PluginManifest(
                1,
                ApiRange(1, 2),
                "youtube",
                "YouTube",
                "1.0",
                1,
                roles = Roles(audio = AudioRole(setOf("youtube"), match = true, radio = true, reportPlayback = true)),
            ),
            "signer",
            "test://youtube",
            0,
            emptyList(),
            emptyList(),
        )

    init {
        val state =
            PluginRegistryState(
                listOf(plugin),
                ProviderSelection(metadata = "spotify", audio = listOf("youtube")),
            )
        every { registry.state } returns MutableStateFlow(state)
        coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(unavailable, candidate))
        coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == unavailable.ref }) } throws
            PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "recording unavailable"))
        coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns stream
        coEvery { host.call("youtube", PluginOperations.reportListen, any()) } returns Unit
        coEvery { host.call("youtube", PluginOperations.audioRadio, any()) } returns TrackList(emptyList())
    }

    @Test
    fun `unavailable matched recording falls back and listen reports the playable identity`() =
        runTest {
            val resolved = audio.resolve(original, null)
            assertThat(resolved.track).isEqualTo(candidate)
            assertThat(resolved.stream).isEqualTo(stream)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.resolveAudio, any()) }
            audio.reportListen(original, 120_000, 143_000)
            coVerify {
                host.call(
                    "youtube",
                    PluginOperations.reportListen,
                    ReportPlaybackRequest(candidate.ref, "listen", 120_000, 143_000),
                )
            }
        }

    @Test
    fun `sole unavailable recording remains retryable after a transient failure`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "temporary"))
            try {
                audio.resolve(original, null)
                throw AssertionError("Expected unavailable")
            } catch (_: PluginCallException) {
            }
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns stream
            assertThat(audio.resolve(original, null).track).isEqualTo(candidate)
        }

    @Test
    fun `radio continues with the matched audio seed and ignores foreign playlists`() =
        runTest {
            val first = radio.page(original.ref, original)!!
            assertThat(first.seed).isEqualTo(unavailable.ref)
            assertThat(first.fromAudio).isTrue()
            val next = radio.next(first, "continuation")
            assertThat(next.seed).isEqualTo(first.seed)
            coVerify { host.call("youtube", PluginOperations.audioRadio, RadioRequest(unavailable.ref, "continuation")) }
            assertThat(radio.page(EntityRef(EntityKind.PLAYLIST, "spotify:playlist"))).isNull()
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.audioRadio, any()) }
        }
}
