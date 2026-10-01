package io.github.aedev.flow.player

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import io.github.aedev.flow.player.datasource.bindCachedMusicRendition
import io.github.aedev.flow.player.datasource.hasCompleteMusicDownload
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CachedMusicResourcesTest {
    private val cache = mockk<Cache>(relaxed = true)
    private val metadata = mockk<ContentMetadata>()

    init {
        every { cache.getContentMetadata("song") } returns metadata
    }

    @Test
    fun `a cached prefix does not bypass provider resolution`() {
        every { metadata.get(ContentMetadata.KEY_CONTENT_LENGTH, any<Long>()) } returns 1_048_576L
        every { cache.isCached("song", 0, 1_048_576L) } returns false
        every { cache.isCached("song", 0, 524_288L) } returns true
        assertFalse(hasCompleteMusicDownload(cache, "song"))
    }

    @Test
    fun `a complete small download is available offline`() {
        every { metadata.get(ContentMetadata.KEY_CONTENT_LENGTH, any<Long>()) } returns 64_000L
        every { cache.isCached("song", 0, 64_000L) } returns true
        assertTrue(hasCompleteMusicDownload(cache, "song"))
    }

    @Test
    fun `completed length fills absent cache metadata but unknown length does not bypass`() {
        every { metadata.get(ContentMetadata.KEY_CONTENT_LENGTH, any<Long>()) } returns -1L
        every { cache.isCached("song", 0, 64_000L) } returns true
        assertTrue(hasCompleteMusicDownload(cache, "song", 64_000L))
        assertFalse(hasCompleteMusicDownload(cache, "song"))
    }

    @Test
    fun `provider or rendition change clears persisted cached bytes`() {
        every { metadata.get("milkbeat.rendition", any<String>()) } returns "youtube:video:aac"
        bindCachedMusicRendition(cache, "song", "beatport:track:hls")
        verify(exactly = 1) { cache.removeResource("song") }
        verify(exactly = 1) { cache.applyContentMetadataMutations("song", any<ContentMetadataMutations>()) }
    }

    @Test
    fun `the same persisted rendition keeps cached bytes`() {
        every { metadata.get("milkbeat.rendition", any<String>()) } returns "youtube:video:aac"
        bindCachedMusicRendition(cache, "song", "youtube:video:aac")
        verify(exactly = 0) { cache.removeResource(any()) }
    }
}
