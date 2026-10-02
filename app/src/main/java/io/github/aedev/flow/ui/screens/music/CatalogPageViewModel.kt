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
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.catalog.listenerMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.PageBlock
import nl.neerdael.milkbeat.catalog.ProviderAccount
import javax.inject.Inject

data class CatalogPageState(
    val blocks: List<PageBlock> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    internal val sourceKey: String? = null,
)

/** One artist, album or playlist page of the music provider, with a long playlist's tracks followed to the end. */
@HiltViewModel
class CatalogPageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        defaultProvider: MetadataProvider,
        defaultPlayback: CatalogPlayback,
        private val subscriptions: SubscriptionRepository,
        pluginCatalog: PluginMetadataProvider? = null,
    ) : ViewModel() {
        val sourcePluginId: String? = savedStateHandle.get<String>(PROVIDER_ARG)
        private val scoped = sourcePluginId?.let { checkNotNull(pluginCatalog).scoped(it) }
        private val provider: MetadataProvider = scoped ?: defaultProvider
        private val playback: CatalogPlayback = scoped ?: defaultPlayback

        fun radioSeed(id: String?): String? =
            id?.let { value ->
                sourcePluginId?.let {
                    ProviderEntityReference.encode(
                        it,
                        if (value ==
                            entity.providerId
                        ) {
                            entity
                        } else {
                            EntityRef(EntityKind.PLAYLIST, value)
                        },
                    )
                }
                    ?: value
            }

        private val entity =
            EntityRef(
                kind = EntityKind.valueOf(checkNotNull(savedStateHandle.get<String>(KIND_ARG))),
                providerId = checkNotNull(savedStateHandle.get<String>(ID_ARG)),
            )

        private val _state = MutableStateFlow(CatalogPageState())
        val sourceIdentity = provider.account.map { sourceKey(it) }.distinctUntilChanged()
        val state: StateFlow<CatalogPageState> =
            combine(_state, sourceIdentity) { state, identity ->
                if (state.sourceKey != null && state.sourceKey != identity) CatalogPageState() else state
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS, replayExpirationMillis = 0),
                CatalogPageState(),
            )

        private fun sourceKey(account: ProviderAccount): String =
            when (account) {
                is ProviderAccount.SignedIn -> "${provider.id}:signed-in:${account.key}"
                ProviderAccount.Anonymous -> "${provider.id}:anonymous"
                ProviderAccount.Expired -> "${provider.id}:expired"
            }

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

        fun load(expectedIdentity: String? = null) {
            if (expectedIdentity != null && job?.isActive == true && _state.value.sourceKey == expectedIdentity) return
            job?.cancel()
            job =
                viewModelScope.launch {
                    val identity = sourceKey(provider.account.first())
                    if (_state.value.sourceKey == identity && _state.value.blocks.isNotEmpty()) return@launch
                    _state.value = CatalogPageState(sourceKey = identity)
                    val first =
                        provider.page(entity).getOrElse { error ->
                            currentCoroutineContext().ensureActive()
                            if (sourceKey(provider.account.first()) != identity) return@launch
                            Log.w(TAG, "page ${entity.kind} ${entity.providerId} failed", error)
                            _state.update { it.copy(isLoading = false, error = error.listenerMessage) }
                            return@launch
                        }
                    currentCoroutineContext().ensureActive()
                    if (sourceKey(provider.account.first()) != identity) return@launch
                    _state.update { it.copy(blocks = emptyList<PageBlock>().withPage(first.blocks), isLoading = false) }
                    var cursor = first.nextCursor
                    val seen = mutableSetOf<String>()
                    var pages = 0
                    while (cursor != null && seen.add(cursor) && pages++ < MAX_CONTINUATION_PAGES) {
                        val next = provider.page(entity, cursor).getOrNull() ?: break
                        currentCoroutineContext().ensureActive()
                        if (sourceKey(provider.account.first()) != identity) return@launch
                        _state.update { it.copy(blocks = it.blocks.extendedBy(next.blocks)) }
                        cursor = next.nextCursor
                    }
                }
        }

        companion object {
            const val PROVIDER_ARG = "catalogProvider"
            const val KIND_ARG = "kind"
            const val ID_ARG = "id"
            private const val TAG = "CatalogPage"
            private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

            // A playlist of a few thousand tracks, in pages of a hundred.
            private const val MAX_CONTINUATION_PAGES = 30
        }
    }
