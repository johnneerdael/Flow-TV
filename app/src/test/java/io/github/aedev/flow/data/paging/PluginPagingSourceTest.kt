package io.github.aedev.flow.data.paging

import androidx.paging.PagingSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import org.junit.Test

class PluginPagingSourceTest {
    private val requests = mutableListOf<String?>()

    private fun page(
        vararg ids: String,
        next: String? = null,
    ) = MetadataPage(
        id = "p",
        blocks =
            listOf(
                EntityHeader(id = "header", style = HeaderStyle.PORTRAIT, entity = EntityRef(EntityKind.CHANNEL, "UC1"), title = "Channel"),
                CollectionBlock(
                    id = "tab:videos",
                    header = null,
                    layout = CollectionLayout.HORIZONTAL_SHELF,
                    defaultItemView = ItemView.LANDSCAPE_CARD,
                    items = ids.map { MetadataItem(id = "video:$it", entity = EntityRef(EntityKind.VIDEO, it), title = it) },
                ),
            ),
        nextCursor = next,
    )

    private fun source(
        first: MetadataPage?,
        pages: (String?) -> Result<MetadataPage>,
    ) = PluginPagingSource(first) { cursor ->
        requests += cursor
        pages(cursor)
    }

    private suspend fun PluginPagingSource.loadPage(key: String? = null) = load(PagingSource.LoadParams.Refresh(key, 30, false))

    @Test
    fun `a first page already read is served without asking the plugin again`() =
        runTest {
            val paging = source(page("a", "b", next = "c1")) { Result.success(page("c")) }

            val first = paging.loadPage() as PagingSource.LoadResult.Page

            assertThat(first.data.map { it.entity.providerId }).containsExactly("a", "b").inOrder()
            assertThat(first.nextKey).isEqualTo("c1")
            assertThat(requests).isEmpty()
        }

    @Test
    fun `later pages follow the cursor and drop what an earlier page listed`() =
        runTest {
            val paging = source(page("a", "b", next = "c1")) { Result.success(page("b", "c")) }

            paging.loadPage()
            val next = paging.loadPage("c1") as PagingSource.LoadResult.Page

            assertThat(next.data.map { it.entity.providerId }).containsExactly("c")
            assertThat(next.nextKey).isNull()
            assertThat(requests).containsExactly("c1")
        }

    @Test
    fun `without a page already read the first page is fetched, once`() =
        runTest {
            val paging = source(null) { Result.success(page("a")) }

            paging.loadPage()

            assertThat(requests).containsExactly(null)
        }

    @Test
    fun `a failed page is an error the grid can retry`() =
        runTest {
            val paging = source(null) { Result.failure(IllegalStateException("offline")) }

            assertThat(paging.loadPage()).isInstanceOf(PagingSource.LoadResult.Error::class.java)
        }

    @Test
    fun `a cursor that answers with itself ends the paging instead of looping`() =
        runTest {
            val paging = source(null) { Result.success(page("a", next = "same")) }

            val result = paging.loadPage("same") as PagingSource.LoadResult.Page

            assertThat(result.nextKey).isNull()
        }
}
