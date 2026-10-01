package io.github.aedev.flow.data.folders

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DocumentMusicFoldersTest {
    @Test fun selectedTreeIsPersistedAndOnlyFoldersAndAudioAreListed() {
        val tree = Uri.parse("content://documents/tree/root")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root")
        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        every { resolver.query(root, any(), null, null, null) } answers {
            MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).apply { addRow(arrayOf("Music")) }
        }
        every { resolver.query(children, any(), null, null, null) } answers {
            MatrixCursor(arrayOf("document_id", "_display_name", "mime_type", "_size")).apply {
                addRow(arrayOf<Any>("root:album", "Album", DocumentsContract.Document.MIME_TYPE_DIR, 0L))
                addRow(arrayOf<Any>("root:song", "Été #1.flac", "audio/flac", 123L))
                addRow(arrayOf<Any>("root:note", "ignore.txt", "text/plain", 30L))
            }
        }
        val documents = DocumentMusicFolders(context)
        val source = documents.add(tree)
        assertThat(source.name).isEqualTo("Music")
        verify { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val entries = documents.list(source, "")
        assertThat(entries.map { it.name }).containsExactly("Album", "Été #1.flac").inOrder()
        assertThat(entries.last().location).isEqualTo(DocumentsContract.buildDocumentUriUsingTree(tree, "root:song").toString())
        documents.release(source)
        verify { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
