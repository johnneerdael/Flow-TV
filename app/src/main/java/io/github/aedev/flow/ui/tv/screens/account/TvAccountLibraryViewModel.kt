package io.github.aedev.flow.ui.tv.screens.account

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.paging.PluginPagingSource
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.ui.screens.music.extendedBy
import io.github.aedev.flow.ui.screens.music.withPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.PageBlock
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginOperations
import javax.inject.Inject

/**
 * The signed-in account's library sections from the music plugin's `metadata.library`. A section is
 * read when it is first shown and kept for a while; another account reads everything afresh.
 */
@HiltViewModel
class TvAccountLibraryViewModel
    @Inject
    constructor(
        private val provider: PluginMetadataProvider,
    ) : ViewModel() {
        private val _sections = MutableStateFlow<Map<TvAccountLibrarySection, TvLibrarySectionState>>(emptyMap())
        val sections: StateFlow<Map<TvAccountLibrarySection, TvLibrarySectionState>> = _sections.asStateFlow()

        private val jobs = mutableMapOf<TvAccountLibrarySection, Job>()

        private val accountKey: Flow<String?> = provider.account.map { it.key }.distinctUntilChanged()

        /** The watch history, paged as the grid scrolls; a new pager for each account. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val watchHistory: Flow<PagingData<MetadataItem>> =
            accountKey
                .flatMapLatest {
                    Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                        PluginPagingSource(null) { cursor ->
                            provider.call(PluginOperations.library, LibraryRequest(TvAccountLibrarySection.WATCH_HISTORY.sectionId, cursor))
                        }
                    }.flow
                }.cachedIn(viewModelScope)

        fun track(item: MetadataItem): MusicTrack? = provider.track(item)

        fun open(section: TvAccountLibrarySection) {
            if (section.isVideoGrid || jobs[section]?.isActive == true) return
            jobs[section] =
                viewModelScope.launch {
                    val key = provider.account.first().key
                    val known = _sections.value[section]
                    if (known != null && !known.needsLoad(key, System.currentTimeMillis(), FRESH_FOR_MS)) return@launch
                    read(section, key)
                }
        }

        private suspend fun read(
            section: TvAccountLibrarySection,
            key: String?,
        ) {
            fun set(transform: (TvLibrarySectionState) -> TvLibrarySectionState) =
                _sections.update { it + (section to transform(it[section] ?: TvLibrarySectionState())) }

            set { TvLibrarySectionState(isLoading = true, accountKey = key) }
            val first =
                request(section, null).getOrElse { error ->
                    Log.w(TAG, "library ${section.sectionId} failed", error)
                    set { it.copy(isLoading = false, error = error.listenerMessage) }
                    return
                }
            set {
                it.copy(
                    blocks = emptyList<PageBlock>().withPage(first.blocks),
                    isLoading = false,
                    loadedAtMs = System.currentTimeMillis(),
                )
            }
            // Play and Shuffle queue what the section lists, so its pages are read ahead, bounded and in turn.
            var cursor = first.nextCursor
            var pages = 0
            while (cursor != null && pages++ < MAX_CONTINUATION_PAGES) {
                val next = request(section, cursor).getOrNull() ?: break
                set { it.copy(blocks = it.blocks.extendedBy(next.blocks)) }
                cursor = next.nextCursor
            }
        }

        private suspend fun request(
            section: TvAccountLibrarySection,
            cursor: String?,
        ) = provider.call(PluginOperations.library, LibraryRequest(section.sectionId, cursor))

        private val ProviderAccount.key: String?
            get() = (this as? ProviderAccount.SignedIn)?.key

        private companion object {
            const val TAG = "TvAccountLibrary"
            const val PAGE_SIZE = 30
            const val FRESH_FOR_MS = 10 * 60_000L
            const val MAX_CONTINUATION_PAGES = 10
        }
    }
