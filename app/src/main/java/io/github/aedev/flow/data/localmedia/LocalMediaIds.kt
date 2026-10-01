package io.github.aedev.flow.data.localmedia

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import java.util.Base64

/**
 * Ids for files on the device. A `local_<mediaStoreId>` id never names a YouTube video, so every
 * online lookup, sync write and engine signal must skip it.
 */
object LocalMediaIds {
    const val PREFIX = "local_"

    fun isLocal(id: String?): Boolean = id?.startsWith(PREFIX) == true

    fun of(mediaStoreId: Long): String = "$PREFIX$mediaStoreId"

    fun of(uri: Uri): String {
        require(uri.scheme in setOf("content", "file", "smbmusic"))
        return PREFIX + "uri_" + Base64.getUrlEncoder().withoutPadding().encodeToString(uri.toString().toByteArray(Charsets.UTF_8))
    }

    fun mediaStoreId(id: String): Long? = if (isLocal(id)) id.removePrefix(PREFIX).toLongOrNull() else null

    fun videoUri(id: String): Uri? = mediaStoreId(id)?.let { ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it) }

    fun audioUri(id: String): Uri? {
        if (id.startsWith(PREFIX + "uri_")) {
            return runCatching {
                Uri
                    .parse(String(Base64.getUrlDecoder().decode(id.removePrefix(PREFIX + "uri_")), Charsets.UTF_8))
                    .takeIf { it.scheme in setOf("content", "file", "smbmusic") }
            }.getOrNull()
        }
        return mediaStoreId(id)?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
    }
}
