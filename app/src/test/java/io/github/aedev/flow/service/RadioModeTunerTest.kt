package io.github.aedev.flow.service

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicQueueOrigin
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.PluginRadio
import io.github.aedev.flow.plugin.playback.RadioFilterSelection
import io.github.aedev.flow.plugin.playback.RadioPage
import io.github.aedev.flow.plugin.playback.RadioTuningCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadioModeTunerTest {
    private val radio = mockk<PluginRadio>()
    private val tuning = mockk<RadioTuningCoordinator>()
    private val audio = mockk<PluginAudio>()
    private val brain = mockk<MusicBrainEngine>()
    private val tuner = RadioModeTuner(radio, tuning, audio, brain)
    private val seed = EntityRef(EntityKind.TRACK, "seed")
    private val page = RadioPage("provider", TrackList(emptyList()), seed, false)
    private val selection = RadioFilterSelection("discover", page, 1, "account")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { tuning.valid(selection) } returns true
        coEvery { brain.ensureInitialized() } returns Unit
        every { brain.hiddenArtists } returns MutableStateFlow(emptySet())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `obsolete generation cannot apply station or clear newer loading`() =
        runTest {
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<RadioPage>()
            coEvery { radio.tune(page, "discover") } coAnswers {
                requested.complete(Unit)
                response.await()
            }
            var current = true
            val applied = mutableListOf<RadioPage>()
            val loading = mutableListOf<Boolean>()
            val job = tuner.launch(this, selection, 2, { current }, { result, _ -> applied.add(result) }, loading::add)

            requested.await()
            current = false
            response.complete(page)
            job.join()

            assertThat(applied).isEmpty()
            assertThat(loading).containsExactly(true)
        }

    @Test
    fun `account change during tuning prevents station replacement`() =
        runTest {
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<RadioPage>()
            coEvery { radio.tune(page, "discover") } coAnswers {
                requested.complete(Unit)
                response.await()
            }
            val applied = mutableListOf<RadioPage>()
            val loading = mutableListOf<Boolean>()
            val job = tuner.launch(this, selection, 2, { true }, { result, _ -> applied.add(result) }, loading::add)

            requested.await()
            every { tuning.valid(selection) } returns false
            response.complete(page)
            job.join()

            assertThat(applied).isEmpty()
            assertThat(loading).containsExactly(true, false).inOrder()
        }

    @Test
    fun `cancelled tuning cannot apply late provider response`() =
        runTest {
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<RadioPage>()
            coEvery { radio.tune(page, "discover") } coAnswers {
                requested.complete(Unit)
                response.await()
            }
            val applied = mutableListOf<RadioPage>()
            val loading = mutableListOf<Boolean>()
            val job = tuner.launch(this, selection, 2, { true }, { result, _ -> applied.add(result) }, loading::add)

            requested.await()
            job.cancel()
            response.complete(page)
            job.join()

            assertThat(applied).isEmpty()
            assertThat(job.isCancelled).isTrue()
            assertThat(loading).containsExactly(true, false).inOrder()
        }

    @Test
    fun `successful tuning applies provider station as radio tracks`() =
        runTest {
            val result =
                RadioPage(
                    "provider",
                    TrackList(listOf(TrackDescriptor(EntityRef(EntityKind.TRACK, "next"), "Next recording"))),
                    seed,
                    false,
                )
            coEvery { radio.tune(page, "discover") } returns result
            val applied = mutableListOf<MusicTrack>()
            val loading = mutableListOf<Boolean>()

            tuner.launch(this, selection, 2, { true }, { _, tracks -> applied.addAll(tracks) }, loading::add).join()

            assertThat(applied.map { it.videoId }).containsExactly("next")
            assertThat(applied.single().queueOrigin).isEqualTo(MusicQueueOrigin.RADIO)
            assertThat(loading).containsExactly(true, false).inOrder()
        }
}
