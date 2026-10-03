package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.PageBlock
import org.junit.Test

class CatalogBlockMergeTest {
    private val repeated = MetadataItem("same", EntityRef(EntityKind.TRACK, "track"), "Song")

    private fun table(vararg items: MetadataItem) =
        CollectionBlock("tracks", null, CollectionLayout.TRACK_TABLE, ItemView.TRACK_ROW, items.toList())

    @Test
    fun `repeated objects receive stable separate occurrence ids across pagination`() {
        val first = emptyList<PageBlock>().withPage(listOf(table(repeated, repeated)))
        val before = (first.single() as CollectionBlock).items
        val extended = first.extendedBy(listOf(table(repeated)))
        val after = (extended.single() as CollectionBlock).items

        assertThat(before.map { it.id }.distinct()).hasSize(2)
        assertThat(after).hasSize(3)
        assertThat(after.take(2)).containsExactlyElementsIn(before).inOrder()
        assertThat(after.map { it.id }.distinct()).hasSize(3)
        assertThat(after.map { it.entity }).containsExactly(repeated.entity, repeated.entity, repeated.entity)
        assertThat(first.withPage(listOf(table(repeated, repeated)))).isEqualTo(first)
    }

    @Test
    fun `non track collections still deduplicate continuation items by provider id`() {
        val shelf = table(repeated).copy(layout = CollectionLayout.HORIZONTAL_SHELF, defaultItemView = ItemView.COVER_CARD)
        val added = repeated.copy(id = "other", title = "Other")
        val first = emptyList<PageBlock>().withPage(listOf(shelf))
        val extended = first.extendedBy(listOf(shelf.copy(items = listOf(repeated, added))))

        assertThat((extended.single() as CollectionBlock).items).containsExactly(repeated, added).inOrder()
        assertThat(first).containsExactly(shelf)
    }
}
