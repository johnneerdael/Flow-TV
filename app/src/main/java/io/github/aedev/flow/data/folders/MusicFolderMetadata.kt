package io.github.aedev.flow.data.folders

import android.util.LruCache
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicFolderMetadata
    @Inject
    internal constructor(
        private val reader: MusicFolderMetadataReader,
    ) {
        private data class Cached(
            val metadata: FolderAudioMetadata,
            val expires: Instant,
        )

        private val lock = Mutex()
        private val cache =
            object : LruCache<FolderAudioRef, Cached>(16 * 1024 * 1024) {
                override fun sizeOf(
                    key: FolderAudioRef,
                    value: Cached,
                ): Int = 1024 + (value.metadata.artwork?.size ?: 0)
            }

        internal suspend fun load(ref: FolderAudioRef): FolderAudioMetadata =
            lock.withLock {
                cache.get(ref)?.takeIf { Instant.now().isBefore(it.expires) }?.let { return@withLock it.metadata }
                val metadata =
                    try {
                        reader.read(ref)
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (_: Exception) {
                        FolderAudioMetadata()
                    }
                cache.put(ref, Cached(metadata, Instant.now().plusSeconds(300)))
                metadata
            }

        suspend fun enrich(track: MusicTrack): MusicTrack {
            val ref = FolderAudioRef.fromUri(android.net.Uri.parse(track.thumbnailUrl)) ?: return track
            return load(ref).applyTo(track)
        }

        suspend fun invalidate(sourceId: String) =
            lock.withLock {
                cache
                    .snapshot()
                    .keys
                    .filter { it.sourceId == sourceId }
                    .forEach { cache.remove(it) }
            }
    }
