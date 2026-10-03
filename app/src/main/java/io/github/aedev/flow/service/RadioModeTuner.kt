package io.github.aedev.flow.service

import android.util.Log
import io.github.aedev.flow.data.music.model.MusicQueueOrigin
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.data.recommendation.music.primaryArtistKey
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.RadioQueuePolicy
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.PluginRadio
import io.github.aedev.flow.plugin.playback.RadioFilterSelection
import io.github.aedev.flow.plugin.playback.RadioPage
import io.github.aedev.flow.plugin.playback.RadioTuningCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.EntityKind

internal class RadioModeTuner(
    private val radio: PluginRadio,
    private val tuning: RadioTuningCoordinator,
    private val audio: PluginAudio,
    private val brain: MusicBrainEngine,
) {
    fun queueAliases(queue: List<MusicTrack>): Set<String> =
        queue
            .flatMap { track ->
                RadioQueuePolicy.aliases(track) + audio.knownAliases(MusicVideoItems.descriptor(track))
            }.toSet()

    fun eligibleTracks(
        page: RadioPage,
        tracks: List<MusicTrack>,
        queue: List<MusicTrack>,
        seedId: String? = null,
    ): List<MusicTrack> {
        val blocked =
            queueAliases(queue) + listOfNotNull(seedId) +
                listOfNotNull(page.seed.providerId.takeIf { page.seed.kind in setOf(EntityKind.TRACK, EntityKind.MUSIC_VIDEO) })
        return RadioStationPolicy.eligibleTracks(tracks, blocked)
    }

    fun tracks(page: RadioPage): List<MusicTrack> =
        page.tracks.tracks.map { it.toMusicTrack(page.pluginId).copy(queueOrigin = MusicQueueOrigin.RADIO) }

    suspend fun withoutHiddenArtists(tracks: List<MusicTrack>): List<MusicTrack> {
        brain.ensureInitialized()
        val hidden = brain.hiddenArtists.value
        return if (hidden.isEmpty()) tracks else tracks.filterNot { it.primaryArtistKey() in hidden }
    }

    fun launch(
        scope: CoroutineScope,
        selection: RadioFilterSelection,
        generation: Long,
        isCurrent: () -> Boolean,
        applyStation: (RadioPage, List<MusicTrack>) -> Unit,
        setLoading: (Boolean) -> Unit,
    ): Job {
        setLoading(true)
        return scope.launch(Dispatchers.IO) {
            try {
                val result = withContext(NonCancellable) { radio.tune(selection.page, selection.id) }
                val station = withoutHiddenArtists(tracks(result))
                withContext(Dispatchers.Main) {
                    if (!isCurrent() || !tuning.valid(selection)) return@withContext
                    applyStation(result, station)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isCurrent() && tuning.valid(selection)) {
                        tuning.reset(generation)
                        tuning.station(selection.page, generation)
                    }
                }
                Log.w("Media3MusicService", "Radio tuning failed", e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (isCurrent()) setLoading(false)
                }
            }
        }
    }
}
