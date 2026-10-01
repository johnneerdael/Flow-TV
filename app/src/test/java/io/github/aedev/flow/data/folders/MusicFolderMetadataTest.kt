package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = android.app.Application::class)
class MusicFolderMetadataTest {
    @Test fun artworkAndTagsShareOneReadAndModifiedFilesInvalidateTheCache() =
        runBlocking {
            val reader = mockk<MusicFolderMetadataReader>()
            var calls = 0
            coEvery { reader.read(any()) } coAnswers
                {
                    calls++
                    delay(20)
                    FolderAudioMetadata("Title", "Artist", "Album", 60, byteArrayOf(1, 2, 3))
                }
            val repo = MusicFolderMetadata(reader)
            val key = FolderAudioRef("source", "revision", "Album/song.mp3", 100L, 123L)
            val tags = async { repo.load(key) }
            val art = async { repo.load(FolderAudioRef.fromUri(key.uri())!!) }
            assertThat(tags.await().title).isEqualTo("Title")
            assertThat(art.await().artwork).isNotNull()
            assertThat(calls).isEqualTo(1)
            repo.load(key.copy(modified = 124))
            assertThat(calls).isEqualTo(2)
            repo.invalidate("source")
            repo.load(key)
            assertThat(calls).isEqualTo(3)
        }

    @Test fun missingTagsPreserveTheFilenameAndAvailableTagsReplaceFallbacks() {
        val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music")
        val original = MusicFolderEntry("01 file.mp3", "01 file.mp3", false).track(source)
        val empty = FolderAudioMetadata().applyTo(original)
        assertThat(empty.title).isEqualTo("01 file")
        assertThat(empty.artist).isEqualTo("NAS")
        val tagged = FolderAudioMetadata("Tagged", "Singer", "Record", 123).applyTo(original)
        assertThat(tagged.title).isEqualTo("Tagged")
        assertThat(tagged.artist).isEqualTo("Singer")
        assertThat(tagged.album).isEqualTo("Record")
        assertThat(tagged.duration).isEqualTo(123)
        assertThat(tagged.videoId).isEqualTo(original.videoId)
    }

    @Test fun applyingCachedArtworkMetadataTwiceIsIdempotent() {
        val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music")
        val original = MusicFolderEntry("file.mp3", "file.mp3", false).track(source)
        val metadata = FolderAudioMetadata("Tagged", artwork = byteArrayOf(1))
        val tagged = metadata.applyTo(original)
        assertThat(metadata.applyTo(tagged)).isEqualTo(tagged)
    }

    @Test fun failedOrArtworkFreeReadsKeepTheirReferenceForRefreshAndRetry() =
        runBlocking {
            val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music")
            val original = MusicFolderEntry("file.mp3", "file.mp3", false).track(source)
            val reader = mockk<MusicFolderMetadataReader>()
            coEvery { reader.read(any()) } throws java.io.IOException()
            val repo = MusicFolderMetadata(reader)
            val fallback = repo.enrich(original)
            assertThat(FolderAudioRef.fromUri(android.net.Uri.parse(fallback.thumbnailUrl))).isNotNull()
            coEvery { reader.read(any()) } returns FolderAudioMetadata("Recovered", artwork = byteArrayOf(1))
            repo.invalidate(source.id)
            assertThat(repo.enrich(fallback).title).isEqualTo("Recovered")
        }
}
