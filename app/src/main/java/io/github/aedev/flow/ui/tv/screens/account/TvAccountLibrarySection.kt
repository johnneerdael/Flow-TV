package io.github.aedev.flow.ui.tv.screens.account

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.PageBlock

/**
 * The account's library sections the TV offers, each one `metadata.library` section of the music
 * plugin. The watch history lists videos, paged into a grid; the others are catalog pages.
 */
enum class TvAccountLibrarySection(
    val sectionId: String?,
    @param:StringRes val titleRes: Int,
    val isVideoGrid: Boolean = false,
) {
    OVERVIEW(null, R.string.tv_account_library_overview),
    WATCH_HISTORY("watchHistory", R.string.tv_account_history, isVideoGrid = true),
    RECENTLY_PLAYED("history", R.string.tv_account_recently_played),
    LIKED_MUSIC("liked", R.string.tv_account_liked_music),
    PLAYLISTS("playlists", R.string.tv_account_playlists),
}

internal data class TvAccountLibraryTab(
    val section: TvAccountLibrarySection,
    val label: String? = null,
)

internal fun libraryTabs(filters: FilterControl?): List<TvAccountLibraryTab> =
    listOf(TvAccountLibraryTab(TvAccountLibrarySection.OVERVIEW)) +
        (
            filters?.options?.mapNotNull { option ->
                TvAccountLibrarySection.entries.firstOrNull { it.sectionId == option.id }?.let { TvAccountLibraryTab(it, option.label) }
            } ?: listOf(TvAccountLibraryTab(TvAccountLibrarySection.PLAYLISTS))
        )

/** One section as read for the account [accountKey], at [loadedAtMs]. */
data class TvLibrarySectionState(
    val blocks: List<PageBlock> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val accountKey: String? = null,
    val loadedAtMs: Long = 0L,
    val providerId: String? = null,
) {
    /**
     * Whether opening the section reads it again: not while it loads, and not within [freshForMs] of
     * a good read for the same account. A failed read, or another account, is read afresh.
     */
    fun needsLoad(
        accountKey: String?,
        nowMs: Long,
        freshForMs: Long,
    ): Boolean =
        when {
            isLoading -> false
            error != null || accountKey != this.accountKey -> true
            else -> nowMs - loadedAtMs >= freshForMs
        }
}
