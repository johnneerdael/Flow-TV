package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.FilterOption

data class RadioTuningState(
    val choices: List<FilterOption> = emptyList(),
    val selectedId: String? = null,
    val loading: Boolean = false,
)

data class RadioFilterSelection(
    val id: String,
    val page: RadioPage,
    val generation: Long,
    val epoch: Any,
)
