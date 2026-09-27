package io.github.aedev.flow.ui.screens.music

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountFeedClient
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.data.recommendation.MusicRecommendationAlgorithm
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.HomePage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MusicHomeFeedState(
    val chips: List<HomePage.Chip> = emptyList(),
    val selectedChip: HomePage.Chip? = null,
    val sections: List<MusicSection> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
) {
    fun isSelected(chip: HomePage.Chip): Boolean = selectedChip != null && chip.endpoint?.params == selectedChip.endpoint?.params
}

/**
 * The YouTube Music home feed, section for section in the order YouTube Music serves it: the
 * signed-in account's own home when a session is active, the anonymous home otherwise. Every
 * continuation page is followed, as the web client does while scrolling to the end.
 */
@HiltViewModel
class MusicHomeFeedViewModel
    @Inject
    constructor(
        private val store: AccountSessionStore,
        private val client: AccountFeedClient,
        private val youTube: YouTube,
        private val mapper: MusicRecommendationAlgorithm,
    ) : ViewModel() {
        private val _state = MutableStateFlow(MusicHomeFeedState())
        val state: StateFlow<MusicHomeFeedState> = _state.asStateFlow()

        val isAccountExpired: StateFlow<Boolean> =
            store.session
                .map { it?.expired == true }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)

        private var job: Job? = null
        private var loadedKey: FeedKey? = null
        private var loadedAtMs = 0L

        init {
            // A sign-in, sign-out or expiry swaps whose home this is; never keep showing the old one.
            viewModelScope.launch {
                store.session
                    .map { it.activeCookie() }
                    .distinctUntilChanged()
                    .collect { cookie ->
                        val loaded = loadedKey ?: return@collect
                        if (loaded.cookie != cookie) load(chip = null, force = true)
                    }
            }
        }

        fun load(force: Boolean = false) = load(_state.value.selectedChip, force)

        fun selectChip(chip: HomePage.Chip) = load(chip.takeUnless { _state.value.isSelected(it) }, force = true)

        private fun load(
            chip: HomePage.Chip?,
            force: Boolean,
        ) {
            if (!force && job?.isActive == true) return
            if (!force && loadedKey != null && System.currentTimeMillis() - loadedAtMs < FRESH_FOR_MS) return
            job?.cancel()
            job =
                viewModelScope.launch {
                    val key = FeedKey(store.current().activeCookie(), chip?.endpoint?.params)
                    // A refresh of the same feed keeps its shelves (and the focus on them) until page one replaces them.
                    val sameFeed = key == loadedKey
                    loadedKey = key
                    loadedAtMs = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            selectedChip = chip,
                            sections = if (sameFeed) it.sections else emptyList(),
                            isLoading = true,
                            isLoadingMore = false,
                            error = null,
                        )
                    }
                    val first =
                        page(key, continuation = null).getOrElse { error ->
                            Log.w(TAG, "home failed", error)
                            loadedAtMs = 0L
                            _state.update { it.copy(isLoading = false, error = error.message) }
                            return@launch
                        }
                    _state.update {
                        it.copy(
                            chips = first.chips ?: it.chips,
                            sections = mapper.parseHomeSections(first),
                            isLoading = false,
                            isLoadingMore = first.continuation != null,
                        )
                    }
                    var continuation = first.continuation
                    val followed = mutableSetOf<String>()
                    while (continuation != null && followed.add(continuation)) {
                        val next =
                            page(key, continuation).getOrElse { error ->
                                Log.w(TAG, "home continuation failed", error)
                                loadedAtMs = 0L
                                null
                            } ?: break
                        _state.update { it.copy(sections = it.sections + mapper.parseHomeSections(next)) }
                        continuation = next.continuation
                    }
                    _state.update { it.copy(isLoadingMore = false) }
                    Log.d(TAG, "home: ${_state.value.sections.size} sections over ${followed.size + 1} pages")
                }
        }

        private suspend fun page(
            key: FeedKey,
            continuation: String?,
        ): Result<HomePage> {
            val params = key.chipParams.takeIf { continuation == null }
            val result =
                if (key.cookie != null) {
                    client.musicHome(continuation = continuation, params = params)
                } else {
                    youTube.home(continuation = continuation, params = params)
                }
            // Both sources runCatching, so a superseded load comes back as a failure; it must not write state.
            currentCoroutineContext().ensureActive()
            return result
        }

        private fun AccountSession?.activeCookie(): String? = this?.takeUnless { it.expired }?.cookie

        private data class FeedKey(
            val cookie: String?,
            val chipParams: String?,
        )

        private companion object {
            const val TAG = "MusicHomeFeed"
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
            const val FRESH_FOR_MS = 10 * 60_000L
        }
    }
