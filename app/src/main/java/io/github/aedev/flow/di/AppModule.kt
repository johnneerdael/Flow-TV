package io.github.aedev.flow.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import coil3.video.VideoFrameDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.folders.FolderArtworkFetcher
import io.github.aedev.flow.data.folders.MusicFolderMetadata
import io.github.aedev.flow.data.localmedia.MediaStoreThumbnailFetcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    internal fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        folderMetadata: MusicFolderMetadata,
    ): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                add(VideoFrameDecoder.Factory())
                add(MediaStoreThumbnailFetcher.Factory(context))
                add(FolderArtworkFetcher.Factory(folderMetadata))
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, 0.10)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizePercent(0.02)
                    .build()
            }.crossfade(true)
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
}
