package io.github.aedev.flow.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations

@OptIn(UnstableApi::class)
internal fun hasCompleteMusicDownload(
    cache: Cache,
    id: String,
    completedLength: Long = -1,
): Boolean {
    val metadataLength = ContentMetadata.getContentLength(cache.getContentMetadata(id))
    val length = if (metadataLength > 0) metadataLength else completedLength
    return length > 0 && cache.isCached(id, 0, length)
}

@OptIn(UnstableApi::class)
internal fun bindCachedMusicRendition(
    cache: Cache,
    id: String,
    rendition: String,
) {
    val previous = cache.getContentMetadata(id).get(RENDITION_KEY, "")
    if (previous != rendition) {
        cache.removeResource(id)
        cache.applyContentMetadataMutations(id, ContentMetadataMutations().set(RENDITION_KEY, rendition))
    }
}

private const val RENDITION_KEY = "milkbeat.rendition"
