package io.github.aedev.flow.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import nl.neerdael.milkbeat.catalog.MetadataProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogModule {
    @Binds
    abstract fun bindMusicProvider(provider: PluginMetadataProvider): MetadataProvider

    @Binds
    abstract fun bindMusicPlayback(playback: PluginMetadataProvider): CatalogPlayback
}
