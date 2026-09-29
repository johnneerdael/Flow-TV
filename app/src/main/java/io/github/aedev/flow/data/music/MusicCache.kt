package io.github.aedev.flow.data.music

import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory cache for music data to enable instant loading
 */
object MusicCache {
    private val mutex = Mutex()

    // Cache with timestamp for expiration
    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 minutes

    private val trendingCache = ConcurrentHashMap<String, CacheEntry<List<MusicTrack>>>()
    private val genreCache = ConcurrentHashMap<String, CacheEntry<List<MusicTrack>>>()
    private val searchCache = ConcurrentHashMap<String, CacheEntry<List<MusicTrack>>>()
    private val relatedCache = ConcurrentHashMap<String, CacheEntry<List<MusicTrack>>>()

    // Check if cache entry is still valid
    private fun <T> CacheEntry<T>.isValid(): Boolean = System.currentTimeMillis() - timestamp < CACHE_EXPIRY_MS

    // Trending music
    suspend fun getTrendingMusic(limit: Int): List<MusicTrack>? =
        mutex.withLock {
            val key = "trending_$limit"
            trendingCache[key]?.takeIf { it.isValid() }?.data
        }

    suspend fun cacheTrendingMusic(
        limit: Int,
        tracks: List<MusicTrack>,
    ) = mutex.withLock {
        val key = "trending_$limit"
        trendingCache[key] = CacheEntry(tracks)
    }

    suspend fun cacheGenreTracks(
        genre: String,
        limit: Int,
        tracks: List<MusicTrack>,
    ) = mutex.withLock {
        val key = "${genre}_$limit"
        genreCache[key] = CacheEntry(tracks)
    }

    // Related music
    suspend fun getRelatedMusic(videoId: String): List<MusicTrack>? =
        mutex.withLock {
            relatedCache[videoId]?.takeIf { it.isValid() }?.data
        }

    // Clear all caches
    suspend fun clearAll() =
        mutex.withLock {
            trendingCache.clear()
            genreCache.clear()
            searchCache.clear()
            relatedCache.clear()
        }
}
