package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioDelivery
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
    private val accounts = PluginAccounts(host)
    private val audio = PluginAudio(host, registry, matcher, accounts)
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
    fun `the preferred matching provider runs before a lower priority direct id`() =
        runTest {
            val beatport =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            id = "beatport",
                            roles = Roles(audio = AudioRole(setOf("beatport"), match = true, delivery = AudioDelivery.HLS)),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(
                    PluginRegistryState(listOf(plugin, beatport), ProviderSelection(audio = listOf("youtube", "beatport"))),
                )
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } returns stream
            val described = original.copy(ids = original.ids + ("beatport" to "123"))
            assertThat(audio.resolve(described, null).pluginId).isEqualTo("youtube")
            coVerify(exactly = 0) { host.call("beatport", PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `changing provider priority does not reuse the previous providers stream`() =
        runTest {
            val beatport =
                plugin.copy(
                    manifest = plugin.manifest.copy(id = "beatport", roles = Roles(audio = AudioRole(setOf("beatport"), match = true))),
                )
            val selected =
                MutableStateFlow(PluginRegistryState(listOf(plugin, beatport), ProviderSelection(audio = listOf("youtube", "beatport"))))
            every { registry.state } returns selected
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } returns
                AudioMatches(listOf(matchTrack("123", "beatport")))
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } returns stream
            assertThat(audio.resolve(original, null).pluginId).isEqualTo("youtube")
            selected.value = selected.value.copy(selection = ProviderSelection(audio = listOf("beatport", "youtube")))
            assertThat(audio.resolve(original, null).pluginId).isEqualTo("beatport")
        }

    @Test
    fun `preparation retains a track when matching fails temporarily`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.INTERNAL, "offline"))
            try {
                audio.prepare(original, null)
                throw AssertionError("Expected transient error")
            } catch (e: PluginCallException) {
                assertThat(e.error.code).isEqualTo(PluginErrorCode.INTERNAL)
            }
        }

    @Test
    fun `provider not found errors are not a confirmed catalog miss`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.NOT_FOUND, "endpoint missing"))
            try {
                audio.prepare(original, null)
                throw AssertionError("Expected provider error")
            } catch (e: PluginCallException) {
                assertThat(e.pluginId).isEqualTo("youtube")
            }
            assertThat(audio.prepareQueue(original, null)).isEqualTo(QueuePreparationResult.Retryable)
        }

    @Test
    fun `preparation distinguishes confirmed catalog misses from unavailable streams`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(emptyList())
            assertThat(audio.prepareQueue(original, null)).isInstanceOf(QueuePreparationResult.Unmatched::class.java)
            matcher.invalidate(original, "youtube")
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "temporary"))
            try {
                audio.prepare(original, null)
                throw AssertionError("Expected unavailable stream")
            } catch (e: PluginCallException) {
                assertThat(e.error.code).isEqualTo(PluginErrorCode.UNAVAILABLE)
            }
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `playback waits for preparation of the same track without a duplicate resolve`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } coAnswers {
                gate.await()
                stream
            }
            val first = async { audio.prepare(candidate, null) }
            runCurrent()
            val second = async { audio.resolve(candidate, null) }
            runCurrent()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.resolveAudio, any()) }
            gate.complete(Unit)
            assertThat(second.await()).isSameInstanceAs(first.await())
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `account invalidation cannot restore an in flight cached stream`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } coAnswers {
                gate.await()
                stream
            }
            val prepared = async { audio.prepare(candidate, null) }
            runCurrent()
            audio.forgetAll()
            gate.complete(Unit)
            prepared.await()
            assertThat(audio.current(candidate.ref.providerId)).isNull()
        }

    @Test
    fun `forgetting one track preserves other prepared streams`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns stream
            val other = candidate.copy(ref = candidate.ref.copy(providerId = "other"), ids = mapOf("youtube" to "other"))
            audio.prepare(candidate, null)
            val cached = audio.prepare(other, null)
            audio.forget(candidate.ref.providerId)
            assertThat(audio.prepare(other, null)).isSameInstanceAs(cached)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `confirmed misses carry a live account and provider guard`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(emptyList())
            val result = audio.prepareQueue(original, null) as QueuePreparationResult.Unmatched
            assertThat(result.isCurrent()).isTrue()
            accounts.expired("youtube")
            assertThat(result.isCurrent()).isFalse()
            audio.forgetAll()
            assertThat(result.isCurrent()).isFalse()
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
