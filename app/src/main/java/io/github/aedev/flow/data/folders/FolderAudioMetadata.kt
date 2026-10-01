package io.github.aedev.flow.data.folders

import android.net.Uri
import io.github.aedev.flow.data.music.model.MusicTrack
import java.util.UUID

internal data class FolderAudioRef(
    val sourceId: String,
    val revision: String,
    val location: String,
    val size: Long,
    val modified: Long,
) {
    fun uri(): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(sourceId)
            .appendQueryParameter("revision", revision)
            .appendQueryParameter(
                "location",
                location,
            ).appendQueryParameter("size", size.toString())
            .appendQueryParameter("modified", modified.toString())
            .build()

    companion object {
        const val SCHEME = "folderart"

        fun fromUri(uri: Uri): FolderAudioRef? =
            if (uri.scheme != SCHEME) {
                null
            } else {
                runCatching {
                    FolderAudioRef(
                        requireNotNull(uri.host),
                        requireNotNull(uri.getQueryParameter("revision")),
                        requireNotNull(uri.getQueryParameter("location")),
                        uri.getQueryParameter("size")?.toLongOrNull() ?: 0,
                        uri.getQueryParameter("modified")?.toLongOrNull() ?: 0,
                    )
                }.getOrNull()
            }
    }
}

internal data class FolderAudioMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val artwork: ByteArray? = null,
    val artworkVersion: String = UUID.randomUUID().toString(),
) {
    fun applyTo(track: MusicTrack): MusicTrack {
        val ref = FolderAudioRef.fromUri(Uri.parse(track.thumbnailUrl))?.uri() ?: Uri.parse(track.thumbnailUrl)
        val artworkUri = if (artwork == null) ref else ref.buildUpon().appendQueryParameter("artwork", artworkVersion).build()
        return track.copy(
            title = title.ifBlank { track.title },
            artist = artist.ifBlank { track.artist },
            album = album.ifBlank { track.album },
            duration = duration.takeIf { it > 0 } ?: track.duration,
            thumbnailUrl = artworkUri.toString(),
        )
    }
}
