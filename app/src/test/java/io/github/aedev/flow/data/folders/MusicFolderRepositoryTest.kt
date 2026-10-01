package io.github.aedev.flow.data.folders

import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MusicFolderRepositoryTest {
    @Test fun failedAdditionReleasesOnlyANewUnreferencedGrant() =
        runBlocking {
            val uri = Uri.parse("content://documents/tree/root")
            val source = MusicFolder(name = "Local", kind = MusicFolderKind.LOCAL, treeUri = uri.toString())
            for (existing in listOf(false, true)) {
                val store = mockk<MusicFolderStore>()
                val documents = mockk<DocumentMusicFolders>(relaxed = true)
                every { store.folders } returns flowOf(emptyList())
                every { documents.hasAccess(uri) } returns existing
                every { documents.add(uri) } returns source
                coEvery { store.save(source, any()) } throws IOException()
                val repository = MusicFolderRepository(store, documents, mockk())
                assertThrows(IOException::class.java) { runBlocking { repository.addLocal(uri) } }
                verify(exactly = if (existing) 0 else 1) { documents.release(uri) }
            }
        }

    @Test fun failedRootQueryAlsoRollsBackNewGrant() =
        runBlocking {
            val uri = Uri.parse("content://documents/tree/root")
            val store = mockk<MusicFolderStore>()
            val documents = mockk<DocumentMusicFolders>(relaxed = true)
            every { store.folders } returns flowOf(emptyList())
            every { documents.hasAccess(uri) } returns false
            every { documents.add(uri) } throws SecurityException()
            val repository = MusicFolderRepository(store, documents, mockk())
            assertThrows(SecurityException::class.java) { runBlocking { repository.addLocal(uri) } }
            verify { documents.release(uri) }
        }
}
