package io.github.aedev.flow.ui.screens.music

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.Job
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
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.PageBlock
import javax.inject.Inject

data class CatalogPageState(
    val blocks: List<PageBlock> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** One artist, album or playlist page of the music provider, with a long playlist's tracks followed to the end. */
@HiltViewModel
class CatalogPageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val provider: MetadataProvider,
        private val playback: CatalogPlayback,
        private val subscriptions: SubscriptionRepository,
    ) : ViewModel() {
        private val entity =
            EntityRef(
                kind = EntityKind.valueOf(checkNotNull(savedStateHandle.get<String>(KIND_ARG))),
                providerId = checkNotNull(savedStateHandle.get<String>(ID_ARG)),
            )

        private val _state = MutableStateFlow(CatalogPageState())
        val state: StateFlow<CatalogPageState> = _state.asStateFlow()

        private var job: Job? = null

        /** Whether this artist is followed in the app's library; nothing else can be followed. */
        val following: StateFlow<Boolean> =
            if (entity.kind == EntityKind.ARTIST) {
                subscriptions
                    .isSubscribed(entity.providerId)
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)
            } else {
                MutableStateFlow(false)
            }

        fun track(item: MetadataItem): MusicTrack? = playback.track(item)

        fun toggleFollow(header: EntityHeader) {
            if (header.entity.kind != EntityKind.ARTIST) return
            viewModelScope.launch {
                if (following.value) {
                    subscriptions.unsubscribe(header.entity.providerId)
                } else {
                    subscriptions.subscribe(
                        ChannelSubscription(
                            channelId = header.entity.providerId,
                            channelName = header.title,
                            channelThumbnail = header.artwork?.url.orEmpty(),
                            isMusic = true,
                        ),
                    )
                }
            }
        }

        fun load() {
            if (job?.isActive == true || _state.value.blocks.isNotEmpty()) return
            job =
                viewModelScope.launch {
                    _state.update { it.copy(isLoading = true, error = null) }
                    val first =
                        provider.page(entity).getOrElse { error ->
                            Log.w(TAG, "page ${entity.kind} ${entity.providerId} failed", error)
                            _state.update { it.copy(isLoading = false, error = error.message) }
                            return@launch
                        }
                    _state.update { it.copy(blocks = emptyList<PageBlock>().withPage(first.blocks), isLoading = false) }
                    // Play and Shuffle queue the whole playlist before its mix takes over, so a long one is
                    // read to the end once, when it is opened; the pages are bounded and sequential.
                    var cursor = first.nextCursor
                    var pages = 0
                    while (cursor != null && pages++ < MAX_CONTINUATION_PAGES) {
                        val next = provider.page(entity, cursor).getOrNull() ?: break
                        _state.update { it.copy(blocks = it.blocks.extendedBy(next.blocks)) }
                        cursor = next.nextCursor
                    }
                }
        }

        companion object {
            const val KIND_ARG = "kind"
            const val ID_ARG = "id"
            private const val TAG = "CatalogPage"
            private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

            // A playlist of a few thousand tracks, in pages of a hundred.
            private const val MAX_CONTINUATION_PAGES = 30
        }
    }
