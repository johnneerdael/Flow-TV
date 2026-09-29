package io.github.aedev.flow.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.aedev.flow.data.model.DistinctKeyTracker
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage

/**
 * The items of a plugin page that continues by cursor, such as a channel tab or a video playlist,
 * page by page. [firstPage] is a first page the screen already read (for its header), served once
 * instead of asking the plugin again; a refresh after that reads it afresh.
 */
class PluginPagingSource(
    private var firstPage: MetadataPage?,
    private val fetch: suspend (cursor: String?) -> Result<MetadataPage>,
) : PagingSource<String, MetadataItem>() {
    private val seen = DistinctKeyTracker()

    override fun getRefreshKey(state: PagingState<String, MetadataItem>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, MetadataItem> {
        val cursor = params.key
        val known = firstPage.takeIf { cursor == null }
        firstPage = null
        val page = known ?: fetch(cursor).getOrElse { return LoadResult.Error(it) }
        return LoadResult.Page(
            data = seen.filter(page.pagedItems()) { it.id },
            prevKey = null,
            nextKey = page.nextCursor?.takeIf { it != cursor },
        )
    }
}

/** Every item of a page's collections, in order: what a paged grid lists. */
fun MetadataPage.pagedItems(): List<MetadataItem> = blocks.filterIsInstance<CollectionBlock>().flatMap { it.items }
