package io.github.aedev.flow.data.video

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadItemEntity
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy compat — wraps DownloadWithItems for backward compatibility with existing UI.
 */
data class DownloadedVideo(
    val video: Video,
    val filePath: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val fileSize: Long = 0,
    val downloadId: Long = -1,
    val quality: String = "Unknown",
    val isAudioOnly: Boolean = false,
)

/**
 * Manages all video/audio download persistence and file operations.
 * Backed by Room database via DownloadDao.
 */
@Singleton
class VideoDownloadManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadDao: DownloadDao,
        private val offlineSubtitleStore: OfflineSubtitleStore,
    ) {
        companion object {
            private const val TAG = "VideoDownloadManager"
            const val VIDEO_DIR = "Milkbeat"
            const val AUDIO_DIR = "Milkbeat"

            /**
             * Legacy bridge — callers that still use getInstance() will get a crash
             * with a clear message telling them to switch to DI.
             */
            @Deprecated("Use Hilt injection instead", level = DeprecationLevel.ERROR)
            fun getInstance(context: Context): VideoDownloadManager =
                throw UnsupportedOperationException(
                    "VideoDownloadManager is now Hilt-managed. Use @Inject instead of getInstance().",
                )
        }

        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** In-memory guard: paths whose DB entry was deleted but whose file deletion is in-flight. */
        private val recentlyDeletedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Persistent tombstone: file paths deleted from the DB by the user but whose physical
         * file could not be removed (e.g. Android 11+ scoped storage issue, file in use).
         * Prevents scanAndRecoverDownloads() from re-inserting them across app restarts.
         */
        private val tombstonePrefs by lazy {
            context.getSharedPreferences("flow_file_tombstones", Context.MODE_PRIVATE)
        }

        /**
         * Tell the Android system to scan the newly downloaded file.
         * This adds the file into the system's MediaStore index so it's instantly
         * visible to external gallery and media player apps, without needing a duplicate copy.
         */
        fun scanFile(
            filePath: String,
            mimeType: String = "video/mp4",
        ) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "scanFile: File does not exist: $filePath")
                    return
                }

                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType),
                ) { path, uri ->
                    Log.d(TAG, "scanFile: Scanned $path: -> uri=$uri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "scanFile failed", e)
            }
        }

        /** Fallback to internal app storage if external isn't available */
        fun getInternalDownloadDir(): File {
            val dir = File(context.filesDir, "downloads")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        // ===== Database Operations =====

        /** All downloads with their items */
        val allDownloads: Flow<List<DownloadWithItems>> = downloadDao.getAllDownloadsWithItems()

        /** Only audio-only downloads */
        val audioOnlyDownloads: Flow<List<DownloadWithItems>> = downloadDao.getAudioOnlyDownloads()

        /**
         * Legacy compatibility — exposes only COMPLETED downloads as DownloadedVideo list.
         * Used by DownloadsScreen and VideoPlayerViewModel for offline playback.
         * Only includes downloads with at least one COMPLETED item and an existing file.
         */
        val downloadedVideos: Flow<List<DownloadedVideo>>
            get() =
                allDownloads
                    .map { list ->
                        list
                            .filter { dwi ->
                                dwi.overallStatus == DownloadItemStatus.COMPLETED &&
                                    !dwi.isAudioOnly &&
                                    dwi.primaryFilePath?.let { File(it).exists() } == true
                            }.map { toDownloadedVideo(it) }
                    }.flowOn(Dispatchers.IO)

        /** Insert a download item and return its generated ID */
        suspend fun insertItem(item: DownloadItemEntity): Int = downloadDao.insertItem(item).toInt()

        /** Update download progress */
        suspend fun updateProgress(
            itemId: Int,
            downloadedBytes: Long,
            status: DownloadItemStatus,
        ) {
            downloadDao.updateProgress(itemId, downloadedBytes, status)
        }

        /** Update download item with full info including totalBytes */
        suspend fun updateItemFull(
            itemId: Int,
            downloadedBytes: Long,
            totalBytes: Long,
            status: DownloadItemStatus,
        ) {
            downloadDao.updateItemFull(itemId, downloadedBytes, totalBytes, status)
        }

        /** Update item status */
        suspend fun updateStatus(
            itemId: Int,
            status: DownloadItemStatus,
        ) {
            downloadDao.updateStatus(itemId, status)
        }

        /** Update all items for a video */
        suspend fun updateAllItemsStatus(
            videoId: String,
            status: DownloadItemStatus,
        ) {
            downloadDao.updateAllItemsStatus(videoId, status)
        }

        /** Check if a video is downloaded */
        suspend fun isDownloaded(videoId: String): Boolean = downloadDao.isDownloaded(videoId)

        /** Persist SponsorBlock segments JSON for a downloaded video. */
        suspend fun saveSponsorBlockData(
            videoId: String,
            json: String,
        ) {
            downloadDao.updateSponsorBlockData(videoId, json)
        }

        /** Retrieve the stored SponsorBlock segments JSON, or null if not available. */
        suspend fun getSponsorBlockData(videoId: String): String? = downloadDao.getSponsorBlockData(videoId)

        /** Get download with items */
        suspend fun getDownloadWithItems(videoId: String): DownloadWithItems? = downloadDao.getDownloadWithItems(videoId)

        /** Path of the finished video download of [videoId] when its file is still on disk. */
        suspend fun localCopyPath(videoId: String): String? =
            withContext(Dispatchers.IO) {
                downloadDao
                    .getDownloadWithItems(videoId)
                    ?.takeIf { it.overallStatus == DownloadItemStatus.COMPLETED && !it.isAudioOnly }
                    ?.primaryFilePath
                    ?.takeIf { File(it).exists() }
            }

        /** Delete download and its files from disk.
         *
         * Based on NewPipe's deletion order:
         *  1. Collect file paths                    (before DB removal)
         *  2. Guard paths against scanner re-insert (ConcurrentHashMap set)
         *  3. Delete DB entry                       (UI disappears instantly via Room Flow)
         *  4. Delete files in app-scoped ioScope    (immune to ViewModel back-press cancellation)
         *  5. Notify MediaScanner each file is gone (removes stale index on Android 10+)
         */
        suspend fun deleteDownload(videoId: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val download =
                        downloadDao.getDownloadWithItems(videoId)
                            ?: return@withContext false

                    val filePaths = download.items.flatMap { artifactPathsFor(it.filePath) }.distinct()
                    val thumbPath = download.download.thumbnailPath

                    recentlyDeletedPaths.addAll(filePaths)

                    downloadDao.deleteDownload(videoId)

                    ioScope.launch {
                        filePaths.forEach { path ->
                            val fileGone = deleteFileFromDisk(path)
                            if (fileGone) {
                                recentlyDeletedPaths.remove(path)
                                tombstonePrefs.edit().remove(path).apply()
                                MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                                Log.d(TAG, "Deleted: $path")
                            } else {
                                tombstonePrefs.edit().putBoolean(path, true).apply()
                                Log.w(TAG, "File not deleted (kept in guard + tombstoned): $path")
                            }
                        }
                        thumbPath?.let { tp ->
                            try {
                                File(tp).takeIf { it.exists() }?.delete()
                            } catch (_: Exception) {
                            }
                        }
                        offlineSubtitleStore.delete(videoId)
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete download: $videoId", e)
                    false
                }
            }

        private fun artifactPathsFor(path: String): List<String> = listOf(path, "$path.video.tmp", "$path.audio.tmp")

        /**
         * Delete a single file from disk.
         * Returns true if the file is confirmed gone (never existed, successfully deleted,
         * or removed via MediaStore fallback on Android Q+).
         */
        private fun deleteFileFromDisk(path: String): Boolean {
            val file = File(path)
            if (!file.exists()) return true
            if (file.delete()) return true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    context.contentResolver
                        .query(
                            contentUri,
                            arrayOf(MediaStore.MediaColumns._ID),
                            "${MediaStore.MediaColumns.DATA} = ?",
                            arrayOf(path),
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                val fileUri = ContentUris.withAppendedId(contentUri, id)
                                if (context.contentResolver.delete(fileUri, null, null) > 0) {
                                    return !file.exists()
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore delete fallback failed for: $path", e)
                }
            }

            return !file.exists()
        }

        /** Get total storage used by downloads */
        suspend fun getTotalDownloadSize(): Long = downloadDao.getTotalDownloadSize()

        /** Legacy compatibility: convert DownloadWithItems to DownloadedVideo for existing UI */
        fun toDownloadedVideo(dwi: DownloadWithItems): DownloadedVideo =
            DownloadedVideo(
                video =
                    Video(
                        id = dwi.download.videoId,
                        title = dwi.download.title,
                        channelName = dwi.download.uploader,
                        channelId = "local",
                        thumbnailUrl = dwi.download.thumbnailUrl,
                        duration = dwi.download.duration.toInt(),
                        viewCount = 0,
                        uploadDate = dwi.download.createdAt.toString(),
                        description = context.getString(R.string.fallback_downloaded_locally),
                    ),
                filePath = dwi.primaryFilePath ?: "",
                downloadedAt = dwi.download.createdAt,
                fileSize = dwi.totalSize,
                downloadId = dwi.download.createdAt,
                quality = dwi.items.firstOrNull()?.quality ?: "Unknown",
                isAudioOnly = dwi.isAudioOnly,
            )
    }
