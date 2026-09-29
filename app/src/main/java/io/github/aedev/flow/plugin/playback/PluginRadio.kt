package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginOperations
import javax.inject.Inject
import javax.inject.Singleton

/** A page of a radio, from the plugin [pluginId] that built it. */
class RadioPage(
    val pluginId: String,
    val tracks: TrackList,
)

/**
 * Where a queue's continuation comes from: the metadata plugin's radio first, since taste lives
 * there (YouTube's own mix of a track, or a collection's similar content), then the audio plugins'
 * radio. What the seed means (a track, a collection, a station) is the plugin's to know.
 */
@Singleton
class PluginRadio
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
    ) {
        suspend fun page(
            seed: EntityRef,
            cursor: String? = null,
        ): RadioPage? {
            val state = registry.state.value
            state
                .plugin(
                    state.selection.metadata,
                )?.takeIf {
                    MetadataSurface.RADIO in
                        it.manifest.roles.metadata
                            ?.surfaces
                            .orEmpty()
                }?.let { plugin ->
                    try {
                        return RadioPage(plugin.id, host.call(plugin.id, PluginOperations.radio, RadioRequest(seed, cursor)))
                    } catch (e: PluginCallException) {
                        if (state.selection.audio.isEmpty()) throw e
                    }
                }
            state.selection.audio
                .mapNotNull(state::plugin)
                .firstOrNull {
                    it.manifest.roles.audio
                        ?.radio == true
                }?.let { plugin ->
                    return RadioPage(plugin.id, host.call(plugin.id, PluginOperations.audioRadio, RadioRequest(seed, cursor)))
                }
            return null
        }
    }
