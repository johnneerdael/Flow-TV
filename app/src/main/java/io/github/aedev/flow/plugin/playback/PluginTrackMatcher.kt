package io.github.aedev.flow.plugin.playback

import android.util.Log
import io.github.aedev.flow.data.local.dao.TrackMatchDao
import io.github.aedev.flow.data.local.entity.TrackMatchEntity
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.CompletableDeferred
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.MatchAudioRequest
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperations
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginTrackMatcher"
private val MATCH_LIFETIME_MS = TimeUnit.DAYS.toMillis(90)
private val MISS_LIFETIME_MS = TimeUnit.DAYS.toMillis(1)

/**
 * Finds an audio plugin's own track for a track another plugin describes (a Spotify track played
 * through YouTube Music): the plugin offers candidates, [TrackMatchScore] picks one, and the answer,
 * a miss included, is kept so each track is searched once. One lookup per track and plugin is in
 * flight at a time; a second caller waits for it.
 */
@Singleton
class PluginTrackMatcher
    @Inject
    constructor(
        private val host: PluginHost,
        private val matches: TrackMatchDao,
    ) {
        private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TrackDescriptor?>>()
        private val pruned = AtomicBoolean(false)

        /** [pluginId]'s track for [track], or null when it has none it is confident of. */
        suspend fun match(
            track: TrackDescriptor,
            pluginId: String,
            excludedId: String? = null,
        ): TrackDescriptor? {
            val fingerprint = fingerprint(track)
            cached(fingerprint, pluginId)?.let {
                if (excludedId == null || it.candidate?.ref?.providerId != excludedId) return it.candidate
            }
            val key = "$pluginId|$fingerprint|${excludedId.orEmpty()}"
            val mine = CompletableDeferred<TrackDescriptor?>()
            inFlight.putIfAbsent(key, mine)?.let { return it.await() }
            try {
                return lookup(track, fingerprint, pluginId, excludedId).also(mine::complete)
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(key, mine)
            }
        }

        suspend fun invalidate(
            track: TrackDescriptor,
            pluginId: String,
        ) {
            matches.delete(fingerprint(track), pluginId)
        }

        private class Cached(
            val candidate: TrackDescriptor?,
        )

        private suspend fun cached(
            fingerprint: String,
            pluginId: String,
        ): Cached? {
            if (pruned.compareAndSet(false, true)) matches.deleteOlderThan(System.currentTimeMillis() - MATCH_LIFETIME_MS)
            val row = matches.find(fingerprint, pluginId) ?: return null
            val lifetime = if (row.candidate == null) MISS_LIFETIME_MS else MATCH_LIFETIME_MS
            if (System.currentTimeMillis() - row.matchedAt > lifetime) return null
            val candidate =
                row.candidate?.let { json ->
                    runCatching { PluginJson.decodeFromString(TrackDescriptor.serializer(), json) }.getOrNull() ?: return null
                }
            return Cached(candidate)
        }

        private suspend fun lookup(
            track: TrackDescriptor,
            fingerprint: String,
            pluginId: String,
            excludedId: String?,
        ): TrackDescriptor? {
            val candidates =
                try {
                    host.call(pluginId, PluginOperations.matchAudio, MatchAudioRequest(track)).candidates
                } catch (e: PluginCallException) {
                    // A failed search says nothing about the track, so nothing is kept.
                    Log.w(TAG, "$pluginId could not search for ${track.title}: ${e.error.message}")
                    return null
                }
            val best = TrackMatchScore.best(track, candidates.filter { it.ref.providerId != excludedId })
            Log.d(
                TAG,
                "${track.title}: ${candidates.size} candidates from $pluginId, best ${best?.score?.let { "%.2f".format(it) } ?: "none"}",
            )
            if (best == null && excludedId != null) return null
            matches.upsert(
                TrackMatchEntity(
                    fingerprint = fingerprint,
                    pluginId = pluginId,
                    candidate = best?.candidate?.let { PluginJson.encodeToString(TrackDescriptor.serializer(), it) },
                    confidence = best?.score ?: 0.0,
                    matchedAt = System.currentTimeMillis(),
                ),
            )
            return best?.candidate
        }

        companion object {
            /** A track's identity across plugins: its ISRC when it has one, else every id it carries. */
            internal fun fingerprint(track: TrackDescriptor): String =
                track.ids["isrc"]?.takeIf { it.isNotBlank() }?.let { "isrc:${it.uppercase()}" }
                    ?: track.ids.entries
                        .sortedBy { it.key }
                        .joinToString(",") { "${it.key}:${it.value}" }
                        .ifEmpty { "ref:${track.ref.kind}:${track.ref.providerId}" }
        }
    }
