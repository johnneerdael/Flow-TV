package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioDelivery
import nl.neerdael.milkbeat.plugin.AudioQuality
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import nl.neerdael.milkbeat.plugin.ResolveAudioRequest
import nl.neerdael.milkbeat.plugin.StreamFailure
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val EXPIRY_MARGIN_MS = 60_000L
private const val DEFAULT_LIFETIME_MS = 5 * 60 * 60_000L

/** A stream an audio plugin handed out, with the plugin and when to ask again. */
class ResolvedAudio(
    val pluginId: String,
    /** The track as the plugin knows it: the listener's own, or the plugin's match for it. */
    val track: TrackDescriptor,
    val stream: AudioStream,
    val validUntilMs: Long,
    /** Whether the plugin was asked for the picture too. */
    val withPicture: Boolean,
    internal val providerOrder: List<String> = emptyList(),
    internal val request: ResolveAudioRequest? = null,
)

/** What the picture of a music video is resolved against: what this TV decodes, best first. */
class PictureLimits(
    val maxHeight: Int,
    val codecs: List<String>,
)

/**
 * Plays tracks through the listener's audio plugins in their selected order. Each plugin uses its
 * own id when present, otherwise searches for a match; the next plugin is tried when no suitable
 * recording is found or the recording is unavailable. One resolve serves a music video's sound and picture; it is kept until shortly
 * before it expires, and dropped when playback reports it failed, so the plugin is asked for another.
 */
@Singleton
class PluginAudio
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
        private val matcher: PluginTrackMatcher,
    ) {
        private val resolved = ConcurrentHashMap<String, ResolvedAudio>()
        private val failures = ConcurrentHashMap<String, StreamFailure>()

        /** The stream for [track], resolving it unless a still-valid one covers what is asked. */
        suspend fun resolve(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality = AudioQuality.AUTO,
        ): ResolvedAudio {
            val key = track.ref.providerId
            val attempts = audioProviderAttempts(registry.state.value, track, withPicture = picture != null)
            val order = attempts.map { "${it.plugin.id}:${it.plugin.manifest.versionCode}" }
            resolved[key]
                ?.takeIf {
                    it.providerOrder == order && it.validUntilMs > System.currentTimeMillis() && (picture == null || it.withPicture)
                }?.let { return it }
            if (attempts.isEmpty()) {
                throw PluginCallException("none", PluginError(PluginErrorCode.UNAVAILABLE, "No audio plugin plays ${track.title}"))
            }
            var last: PluginCallException? = null
            for ((plugin, known) in attempts) {
                var playable = known ?: matcher.match(track, plugin.id) ?: continue
                for (attempt in 0..1) {
                    val request =
                        ResolveAudioRequest(
                            track = playable,
                            quality = quality,
                            video = picture != null,
                            maxVideoHeight = picture?.maxHeight,
                            videoCodecs = picture?.codecs.orEmpty(),
                            failure = failures.remove(key),
                        )
                    try {
                        val stream = host.call(plugin.id, PluginOperations.resolveAudio, request)
                        if (picture != null && stream.video == null) {
                            throw PluginCallException(
                                plugin.id,
                                PluginError(PluginErrorCode.UNAVAILABLE, "The audio provider has no picture for this recording"),
                            )
                        }
                        val lifetime = stream.expiresInMs ?: DEFAULT_LIFETIME_MS
                        return ResolvedAudio(
                            plugin.id,
                            playable,
                            stream,
                            System.currentTimeMillis() + lifetime - EXPIRY_MARGIN_MS,
                            picture != null,
                            order,
                            request,
                        ).also { resolved[key] = it }
                    } catch (e: PluginCallException) {
                        last = e
                        if (e.error.code != PluginErrorCode.UNAVAILABLE && e.error.code != PluginErrorCode.NOT_FOUND) throw e
                        if (known != null) break
                        matcher.invalidate(track, plugin.id)
                        if (attempt != 0) break
                        playable = matcher.match(track, plugin.id, excludedId = playable.ref.providerId) ?: break
                    }
                }
            }
            throw last ?: PluginCallException("none", PluginError(PluginErrorCode.NOT_FOUND, "No audio plugin found ${track.title}"))
        }

        suspend fun refreshBound(audio: ResolvedAudio): ResolvedAudio {
            val request = audio.request ?: ResolveAudioRequest(audio.track, video = audio.withPicture)
            val stream = host.call(audio.pluginId, PluginOperations.resolveAudio, request)
            val previousHls =
                audio.stream.mimeType
                    .substringBefore(';')
                    .lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
            val nextHls =
                stream.mimeType.substringBefore(';').lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
            if (previousHls != nextHls || (audio.withPicture && stream.video == null)) {
                throw PluginCallException(
                    audio.pluginId,
                    PluginError(PluginErrorCode.UNAVAILABLE, "The provider changed this recording's delivery format"),
                )
            }
            return ResolvedAudio(
                audio.pluginId,
                audio.track,
                stream,
                System.currentTimeMillis() + (stream.expiresInMs ?: DEFAULT_LIFETIME_MS) - EXPIRY_MARGIN_MS,
                audio.withPicture,
                audio.providerOrder,
                request,
            )
        }

        /** How the first audio plugin that would play [track] delivers its streams. */
        fun deliveryFor(track: TrackDescriptor): AudioDelivery =
            audioProviderAttempts(registry.state.value, track)
                .firstOrNull()
                ?.plugin
                ?.manifest
                ?.roles
                ?.audio
                ?.delivery ?: AudioDelivery.PROGRESSIVE

        /** What was resolved for track [id], if anything still is: its loudness, tracking token and plugin. */
        fun current(id: String): ResolvedAudio? = resolved[id]

        /** Playback of [id] failed on [url] with [status]; the next resolve asks the plugin for another. */
        fun failed(
            id: String,
            url: String,
            status: Int?,
        ) {
            resolved.remove(id)
            failures[id] = StreamFailure(url, status)
        }

        /** [pluginId]'s own track for [track]: the track itself when the plugin plays its ids, else its match. */
        suspend fun playableIn(
            track: TrackDescriptor,
            pluginId: String,
        ): TrackDescriptor? {
            val plugin = registry.state.value.plugin(pluginId) ?: return null
            directAudioTrack(track, plugin)?.let { return it }
            if (plugin.manifest.roles.audio
                    ?.match != true
            ) {
                return null
            }
            return resolved[track.ref.providerId]?.takeIf { it.pluginId == pluginId }?.track ?: matcher.match(track, pluginId)
        }

        fun forget(id: String) {
            resolved.remove(id)
        }

        fun forgetAll() {
            resolved.clear()
        }

        /** Reports a listen to the plugin that played it, when it reports listens and the listener allows it. */
        suspend fun reportListen(
            track: TrackDescriptor,
            playedMs: Long,
            durationMs: Long?,
        ) {
            val played = resolved[track.ref.providerId] ?: return
            val plugin = registry.state.value.plugin(played.pluginId) ?: return
            if (plugin.manifest.roles.audio
                    ?.reportPlayback != true
            ) {
                return
            }
            host.call(
                played.pluginId,
                PluginOperations.reportListen,
                ReportPlaybackRequest(played.track.ref, played.stream.trackingToken, playedMs, durationMs),
            )
        }
    }
