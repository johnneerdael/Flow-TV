package io.github.aedev.flow.ui.screens.music

import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.PageBlock

/**
 * Appends a page's blocks. A block served again unchanged is dropped; a different block that
 * happens to share an id (two shelves with one title) is kept under a numbered id, since block ids
 * key the lazy list that shows them.
 */
internal fun List<PageBlock>.withPage(page: List<PageBlock>): List<PageBlock> {
    val merged = toMutableList()
    val ids = mapTo(HashSet()) { it.id }
    for (block in page) {
        if (block.withTrackOccurrences() in merged) continue
        var id = block.id
        var n = 2
        while (!ids.add(id)) id = "${block.id}#${n++}"
        merged += (if (id == block.id) block else block.withId(id)).withTrackOccurrences()
    }
    return merged
}

/** A continuation page's blocks extend the collections with the same id; anything else is appended. */
internal fun List<PageBlock>.extendedBy(page: List<PageBlock>): List<PageBlock> {
    val merged = toMutableList()
    val rest = mutableListOf<PageBlock>()
    for (block in page) {
        val index = merged.indexOfFirst { it.id == block.id }
        val existing = merged.getOrNull(index)
        if (existing is CollectionBlock && block is CollectionBlock) {
            val items = existing.items + block.items
            merged[index] =
                if (existing.layout == CollectionLayout.TRACK_TABLE && block.layout == CollectionLayout.TRACK_TABLE) {
                    existing.copy(items = items).withTrackOccurrences()
                } else {
                    existing.copy(items = items.distinctBy { it.id })
                }
        } else {
            rest += block
        }
    }
    return merged.withPage(rest)
}

private fun PageBlock.withId(id: String): PageBlock =
    when (this) {
        is CollectionBlock -> copy(id = id)
        is EntityHeader -> copy(id = id)
    }

private fun PageBlock.withTrackOccurrences(): PageBlock =
    if (this is CollectionBlock && layout == CollectionLayout.TRACK_TABLE) {
        copy(
            items =
                items.mapIndexed { index, item ->
                    val occurrenceId = "$id/row/$index"
                    if (item.id == occurrenceId) item else item.copy(id = occurrenceId)
                },
        )
    } else {
        this
    }
