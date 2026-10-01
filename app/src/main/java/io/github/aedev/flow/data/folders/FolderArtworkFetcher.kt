package io.github.aedev.flow.data.folders

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import java.io.IOException

internal class FolderArtworkFetcher(
    private val metadata: MusicFolderMetadata,
    private val ref: FolderAudioRef,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = metadata.load(ref).artwork ?: throw IOException("No embedded artwork")
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory(
        private val metadata: MusicFolderMetadata,
    ) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val ref = FolderAudioRef.fromUri(android.net.Uri.parse(data.toString())) ?: return null
            return FolderArtworkFetcher(metadata, ref, options)
        }
    }
}
