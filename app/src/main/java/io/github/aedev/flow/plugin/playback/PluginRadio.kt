package io.github.aedev.flow.plugin.playback

import android.util.Log
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginOperations
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A page of a radio, from the plugin [pluginId] that built it from [seed], its own id for what the
 * listener seeded; [fromAudio] says it was that plugin's audio radio, so the next page comes from it too.
 */
class RadioPage(
    val pluginId: String,
    val tracks: TrackList,
    val seed: EntityRef,
    val fromAudio: Boolean,
)

/**
 * Where a queue's continuation comes from: the metadata plugin's radio first, since taste lives
 * there (YouTube's own mix of a track, or a collection's similar content), then the audio plugins'
 * radio. An audio plugin is only handed a seed it knows: the metadata plugin's own when they are the
 * same plugin, else the seed track in the plugin's own id space, matched into it when need be. A
 * Spotify playlist is never sent to YouTube's radio.
 */
@Singleton
class PluginRadio
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
        private val audio: PluginAudio,
    ) {
        /** The next page of [previous], from the same plugin and the same seed. */
        suspend fun next(
            previous: RadioPage,
            cursor: String,
        ): RadioPage {
            val operation = if (previous.fromAudio) PluginOperations.audioRadio else PluginOperations.radio
            val tracks = host.call(previous.pluginId, operation, RadioRequest(previous.seed, cursor))
            return RadioPage(previous.pluginId, tracks, previous.seed, previous.fromAudio)
        }

        /** The first page of the radio seeded from [seed]; [seedTrack] describes it when it is a track. */
        suspend fun page(
            seed: EntityRef,
            seedTrack: TrackDescriptor? = null,
        ): RadioPage? {
            val state = registry.state.value
            val scoped = ProviderEntityReference.decode(seed.providerId)?.takeIf { seedTrack == null || it.entity == seedTrack.ref }
            val compatible =
                state.plugins.filter { plugin ->
                    plugin.enabled && seedTrack?.ids?.containsKey(
                        plugin.manifest.roles.metadata
                            ?.idSpace,
                    ) == true
                }
            val metadataPlugin =
                scoped?.pluginId ?: if (seedTrack == null) {
                    state.selection.metadata
                } else {
                    compatible.firstOrNull { it.id == state.selection.metadata }?.id ?: compatible.firstOrNull()?.id
                }
            val ownSeed =
                scoped?.entity ?: seedTrack?.let { track ->
                    val space =
                        state
                            .plugin(metadataPlugin)
                            ?.manifest
                            ?.roles
                            ?.metadata
                            ?.idSpace
                    val id = space?.let(track.ids::get)?.takeIf(String::isNotBlank)
                    if (id == null) track.ref else track.ref.copy(providerId = id)
                } ?: seed
            state
                .plugin(
                    metadataPlugin,
                )?.takeIf {
                    MetadataSurface.RADIO in
                        it.manifest.roles.metadata
                            ?.surfaces
                            .orEmpty()
                }?.let { plugin ->
                    try {
                        val tracks = host.call(plugin.id, PluginOperations.radio, RadioRequest(ownSeed))
                        if (tracks.tracks.isNotEmpty()) return RadioPage(plugin.id, tracks, ownSeed, fromAudio = false)
                    } catch (e: PluginCallException) {
                        if (state.selection.audio.isEmpty()) throw e
                    }
                }
            for (plugin in state.selection.audio.mapNotNull(state::plugin)) {
                if (plugin.manifest.roles.audio
                        ?.radio != true
                ) {
                    continue
                }
                try {
                    val own =
                        when {
                            plugin.id == metadataPlugin -> ownSeed
                            seedTrack != null -> audio.playableIn(seedTrack, plugin.id)?.ref
                            else -> null
                        } ?: continue
                    val tracks = host.call(plugin.id, PluginOperations.audioRadio, RadioRequest(own))
                    if (tracks.tracks.isNotEmpty()) return RadioPage(plugin.id, tracks, own, fromAudio = true)
                } catch (e: PluginCallException) {
                    Log.w("PluginRadio", "Audio radio unavailable from ${plugin.id}: ${e.error.code}")
                    continue
                }
            }
            return null
        }
    }
