package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.TrackList

internal object RadioContinuationPolicy {
    fun merge(
        previous: TrackList,
        next: TrackList,
    ): TrackList {
        val replacementFilters = next.filters
        return next.copy(
            filters = replacementFilters ?: previous.filters,
            selectedFilterId =
                next.selectedFilterId ?: previous.selectedFilterId?.takeIf { selected ->
                    replacementFilters == null || replacementFilters.options.any { it.id == selected }
                },
        )
    }
}
