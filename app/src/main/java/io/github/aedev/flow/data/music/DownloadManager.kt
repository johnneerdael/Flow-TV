package io.github.aedev.flow.data.music

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.withTypedArtists
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.service.ExoDownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.downloadDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "downloads")

data class DownloadedTrack(
    val track: MusicTrack,
    val filePath: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val fileSize: Long = 0,
    val downloadId: Long = -1,
)

@Singleton
class DownloadManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadUtil: DownloadUtil,
        private val videoDownloadManager: VideoDownloadManager,
    ) {
        private val gson = Gson()
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        companion object {
            private val DOWNLOADED_TRACKS_KEY = stringPreferencesKey("downloaded_tracks")
        }

        /**
         * Check if a track is cached for offline playback.
         * This checks the actual cache, not the download state metadata.
         */
        fun isCachedForOffline(mediaId: String): Boolean = downloadUtil.isCachedForOffline(mediaId)

        val downloadedTracks: Flow<List<DownloadedTrack>> =
            combine(
                context.downloadDataStore.data.map { prefs -> parseDownloadedTracks(prefs[DOWNLOADED_TRACKS_KEY]) },
                videoDownloadManager.audioOnlyDownloads,
            ) { storedTracks, audioDownloads ->
                val roomById =
                    audioDownloads
                        .filter { it.overallStatus == DownloadItemStatus.COMPLETED }
                        .associateBy { it.download.videoId }

                storedTracks
                    .mapNotNull { storedTrack ->
                        val roomDownload = roomById[storedTrack.track.videoId]
                        when {
                            roomDownload != null -> {
                                val audioItem =
                                    roomDownload.items.firstOrNull {
                                        it.status == DownloadItemStatus.COMPLETED && isReadablePath(it.filePath)
                                    }
                                audioItem?.let {
                                    storedTrack.copy(
                                        filePath = it.filePath,
                                        downloadedAt = roomDownload.download.createdAt,
                                        fileSize = it.totalBytes.takeIf { size -> size > 0 } ?: it.downloadedBytes,
                                    )
                                }
                            }

                            downloadUtil.isFullyDownloaded(storedTrack.track.videoId) ||
                                downloadUtil.isCachedForOffline(storedTrack.track.videoId) -> {
                                storedTrack
                            }

                            else -> {
                                null
                            }
                        }
                    }.distinctBy { it.track.videoId }
            }.flowOn(Dispatchers.IO)

        init {
            downloadUtil.getDownloadManagerInstance().addListener(
                object : androidx.media3.exoplayer.offline.DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        if (download.state == Download.STATE_COMPLETED) {
                            scope.launch {
                                updateDownloadedTrack(download.request.id, download.contentLength)
                            }
                        }
                    }
                },
            )
        }

        suspend fun updateDownloadedTrack(
            videoId: String,
            size: Long = 0,
        ) {
            context.downloadDataStore.edit { prefs ->
                val json = prefs[DOWNLOADED_TRACKS_KEY] ?: "[]"
                val storedTracks = parseDownloadedTracks(json).toMutableList()

                val index = storedTracks.indexOfFirst { it.track.videoId == videoId }
                if (index != -1) {
                    val updated =
                        storedTracks[index].copy(
                            fileSize = size,
                            downloadedAt = System.currentTimeMillis(),
                        )
                    storedTracks[index] = updated
                    prefs[DOWNLOADED_TRACKS_KEY] = gson.toJson(storedTracks)
                }
            }
        }

        suspend fun isDownloaded(videoId: String): Boolean {
            if (getCompletedAudioFilePath(videoId) != null) return true
            val download = downloadUtil.downloads.value[videoId]
            return download?.state == Download.STATE_COMPLETED
        }

        suspend fun getDownloadedTrackPath(videoId: String): String? = getCompletedAudioFilePath(videoId)

        suspend fun deleteDownload(videoId: String) {
            videoDownloadManager
                .getDownloadWithItems(videoId)
                ?.takeIf { it.isAudioOnly }
                ?.let { videoDownloadManager.deleteDownload(videoId) }

            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, videoId, false)

            context.downloadDataStore.edit { prefs ->
                val json = prefs[DOWNLOADED_TRACKS_KEY] ?: "[]"
                val currentTracks = parseDownloadedTracks(json).toMutableList()
                currentTracks.removeAll { it.track.videoId == videoId }
                prefs[DOWNLOADED_TRACKS_KEY] = gson.toJson(currentTracks)
            }
        }

        private fun parseDownloadedTracks(json: String?): List<DownloadedTrack> =
            runCatching {
                val type = object : TypeToken<List<DownloadedTrack>>() {}.type
                gson
                    .fromJson<List<DownloadedTrack>>(json ?: "[]", type)
                    .orEmpty()
                    .map { it.copy(track = it.track.withTypedArtists()) }
            }.getOrElse {
                Log.w("DownloadManager", "Failed to parse music downloads", it)
                emptyList()
            }

        private suspend fun getCompletedAudioFilePath(videoId: String): String? {
            val download = videoDownloadManager.getDownloadWithItems(videoId) ?: return null
            if (!download.isAudioOnly || download.overallStatus != DownloadItemStatus.COMPLETED) return null
            return download.items
                .firstOrNull {
                    it.status == DownloadItemStatus.COMPLETED && isReadablePath(it.filePath)
                }?.filePath
        }

        private fun isReadablePath(path: String): Boolean = path.startsWith("content://") || File(path).exists()
    }
