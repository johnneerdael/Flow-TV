package io.github.aedev.flow.ui.tv.screens.search

import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.github.aedev.flow.plugin.catalog.NoVideoPluginException
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.SearchRequest
import nl.neerdael.milkbeat.catalog.SuggestRequest
import nl.neerdael.milkbeat.catalog.Suggestions
import nl.neerdael.milkbeat.plugin.PluginOperations

/** The two halves of TV search: music through the metadata plugin, videos through the video plugin. */
enum class TvSearchSource {
    MUSIC,
    VIDEOS,
}

/** A plugin that answers one half of search: result pages and typeahead. */
internal interface TvSearchBackend {
    suspend fun search(request: SearchRequest): Result<MetadataPage>

    suspend fun suggest(query: String): Result<Suggestions>
}

internal fun PluginMetadataProvider.searchBackend(): TvSearchBackend =
    object : TvSearchBackend {
        override suspend fun search(request: SearchRequest) = call(PluginOperations.search, request)

        override suspend fun suggest(query: String) = call(PluginOperations.suggest, SuggestRequest(query))
    }

internal fun PluginVideoProvider.searchBackend(): TvSearchBackend =
    object : TvSearchBackend {
        override suspend fun search(request: SearchRequest) = this@searchBackend.search(request)

        override suspend fun suggest(query: String) = this@searchBackend.suggest(query)
    }

/** Whether [error] says no plugin is chosen for that half, rather than that the plugin failed. */
internal val Throwable.isNoPlugin: Boolean
    get() = this is NoMetadataPluginException || this is NoVideoPluginException
