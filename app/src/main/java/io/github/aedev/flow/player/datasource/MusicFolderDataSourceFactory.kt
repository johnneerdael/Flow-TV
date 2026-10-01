package io.github.aedev.flow.player.datasource

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.data.folders.MusicFolderStore
import io.github.aedev.flow.data.folders.SmbMusicClient
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import javax.inject.Inject

@OptIn(UnstableApi::class)
class MusicFolderDataSourceFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: MusicFolderStore,
        private val smb: SmbMusicClient,
    ) {
        fun wrap(delegate: DataSource.Factory): DataSource.Factory =
            DataSource.Factory {
                MusicFolderRoutingDataSource(
                    delegate = delegate,
                    smb = {
                        SmbMusicDataSource { uri ->
                            val access =
                                runBlocking(PerformanceDispatcher.diskIO) {
                                    store.access(
                                        uri.host ?: throw FileNotFoundException("Missing folder identity"),
                                        uri.getQueryParameter("revision").orEmpty(),
                                    )
                                }
                            smb.open(access.source, access.password, uri.path.orEmpty())
                        }
                    },
                    documents = { DefaultDataSource.Factory(context).createDataSource() },
                )
            }
    }

@OptIn(UnstableApi::class)
internal class MusicFolderRoutingDataSource(
    private val delegate: DataSource.Factory,
    private val smb: () -> DataSource,
    private val documents: () -> DataSource = { delegate.createDataSource() },
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null)
        val source =
            when {
                dataSpec.uri.scheme == MusicFolder.SMB_SCHEME -> smb()
                dataSpec.uri.scheme == "content" && DocumentsContract.isTreeUri(dataSpec.uri) -> documents()
                else -> delegate.createDataSource()
            }
        active = source
        listeners.forEach(source::addTransferListener)
        return try {
            source.open(dataSpec)
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}
