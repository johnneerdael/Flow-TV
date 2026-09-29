package io.github.aedev.flow.ui.tv.screens.channel

import nl.neerdael.milkbeat.catalog.FilterOption

/**
 * A channel's tabs are its page's filters. The unfiltered page is the first tab's, so the first tab
 * is read with no filter and reuses the page the header came from instead of asking again.
 */
internal object TvChannelTabs {
    fun selected(
        filters: List<FilterOption>,
        selectedId: String?,
    ): FilterOption? = filters.firstOrNull { it.id == selectedId } ?: filters.firstOrNull()

    /** The filter to send for [filterId]: none for the first tab, which the unfiltered page already is. */
    fun requestFilter(
        filters: List<FilterOption>,
        filterId: String?,
    ): String? = filterId?.takeIf { it != filters.firstOrNull()?.id }
}
