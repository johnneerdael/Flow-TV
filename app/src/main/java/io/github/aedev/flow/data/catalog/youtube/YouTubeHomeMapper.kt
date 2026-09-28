package io.github.aedev.flow.data.catalog.youtube

import io.github.aedev.flow.innertube.pages.HomePage
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.MetadataPage

/** YouTube Music's home feed as a catalog page: its mood chips, then every shelf as served. */
internal object YouTubeHomeMapper {
    const val HOME_PAGE_ID = "youtube-music/home"

    fun page(home: HomePage): MetadataPage =
        MetadataPage(
            id = HOME_PAGE_ID,
            blocks = home.shelves.mapNotNull(YouTubeShelfMapper::carousel),
            filters = home.chips?.let(::filters),
            nextCursor = home.continuation,
        )

    private fun filters(chips: List<HomePage.Chip>): FilterControl? =
        chips
            .mapNotNull { chip ->
                val id = chip.endpoint?.params ?: return@mapNotNull null
                FilterOption(id = id, label = chip.title)
            }.takeIf { it.isNotEmpty() }
            ?.let(::FilterControl)
}
