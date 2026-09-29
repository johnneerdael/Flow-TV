package io.github.aedev.flow.ui.tv.screens.channel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.paging.PluginPagingSource
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.github.aedev.flow.plugin.catalog.listenerMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import javax.inject.Inject

data class TvChannelState(
    val header: EntityHeader? = null,
    val filters: List<FilterOption> = emptyList(),
    val selectedFilterId: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val selectedFilter: FilterOption?
        get() = TvChannelTabs.selected(filters, selectedFilterId)
}

/**
 * A channel of the video plugin: its header and tabs from one page read, then each tab's items paged
 * as they are scrolled to. A tab is read once while the page is open; switching back reuses it.
 * Subscribing stays in the app's own library.
 */
@HiltViewModel
class TvChannelViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val video: PluginVideoProvider,
        private val subscriptions: SubscriptionRepository,
    ) : ViewModel() {
        private val channel = EntityRef(EntityKind.CHANNEL, checkNotNull(savedStateHandle.get<String>(CHANNEL_ARG)))

        private val _state = MutableStateFlow(TvChannelState())
        val state: StateFlow<TvChannelState> = _state.asStateFlow()

        /** Each tab's own header, keyed by the filter it was read with; About completes the description. */
        private val _tabHeaders = MutableStateFlow<Map<String?, EntityHeader>>(emptyMap())
        val tabHeaders: StateFlow<Map<String?, EntityHeader>> = _tabHeaders.asStateFlow()

        val isSubscribed: StateFlow<Boolean> =
            subscriptions
                .isSubscribed(channel.providerId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)

        private var firstPage: MetadataPage? = null
        private val tabs = mutableMapOf<String?, Flow<PagingData<MetadataItem>>>()
        private var job: Job? = null

        fun load() {
            if (job?.isActive == true || _state.value.header != null) return
            job =
                viewModelScope.launch {
                    _state.update { it.copy(isLoading = true, error = null) }
                    video
                        .page(channel)
                        .onSuccess { page ->
                            firstPage = page
                            _state.update {
                                it.copy(
                                    header = page.blocks.firstNotNullOfOrNull { block -> block as? EntityHeader },
                                    filters = page.filters?.options.orEmpty(),
                                    isLoading = false,
                                )
                            }
                        }.onFailure { error ->
                            Log.w(TAG, "channel ${channel.providerId} failed", error)
                            _state.update { it.copy(isLoading = false, error = error.listenerMessage) }
                        }
                }
        }

        fun selectFilter(filterId: String) {
            _state.update { it.copy(selectedFilterId = filterId) }
        }

        /** The filter a tab is read with, and so the key of its header in [tabHeaders]. */
        fun requestFilter(filterId: String?): String? = TvChannelTabs.requestFilter(_state.value.filters, filterId)

        /** The items of the tab [filterId], paged; the same flow every time the tab is shown. */
        fun items(filterId: String?): Flow<PagingData<MetadataItem>> {
            val request = requestFilter(filterId)
            return tabs.getOrPut(request) {
                val known = if (request == null) firstPage.also { firstPage = null } else null
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                    PluginPagingSource(known) { cursor ->
                        video.page(channel, request, cursor).onSuccess { page ->
                            val header = page.blocks.firstNotNullOfOrNull { it as? EntityHeader }
                            if (cursor == null && header != null) _tabHeaders.update { it + (request to header) }
                        }
                    }
                }.flow.cachedIn(viewModelScope)
            }
        }

        fun toggleSubscription() {
            val header = _state.value.header ?: return
            viewModelScope.launch {
                if (isSubscribed.value) {
                    subscriptions.unsubscribe(channel.providerId)
                } else {
                    subscriptions.subscribe(
                        ChannelSubscription(
                            channelId = channel.providerId,
                            channelName = header.title,
                            channelThumbnail = header.artwork?.url.orEmpty(),
                        ),
                    )
                }
            }
        }

        companion object {
            const val CHANNEL_ARG = "channelRef"
            private const val TAG = "TvChannel"
            private const val PAGE_SIZE = 30
            private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
