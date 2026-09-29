package io.github.aedev.flow.ui.tv.screens.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.SearchHistoryItem
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.github.aedev.flow.plugin.catalog.listenerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.SearchRequest
import java.util.EnumMap
import javax.inject.Inject

/**
 * TV search through the plugins: music from the metadata plugin, videos from the video plugin. Typing
 * searches the half on screen once the typing pauses, and asks both plugins for typeahead; the other
 * half searches when it is shown, so every fetch follows one cause. A newer query cancels the older
 * one's fetches, and each half keeps its own filter and results.
 */
@HiltViewModel
class TvSearchViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val music: TvSearchBackend,
    private val videos: TvSearchBackend,
    private val trackFor: (MetadataItem) -> MusicTrack?,
    private val history: SearchHistoryRepository,
    private val stats: VideoStatsRecorder,
) : ViewModel() {
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        metadata: PluginMetadataProvider,
        video: PluginVideoProvider,
        history: SearchHistoryRepository,
        stats: VideoStatsRecorder,
    ) : this(savedStateHandle, metadata.searchBackend(), video.searchBackend(), metadata::track, history, stats)

    private val _state = MutableStateFlow(TvSearchUiState(query = savedStateHandle[QUERY_KEY] ?: ""))
    val state: StateFlow<TvSearchUiState> = _state.asStateFlow()

    /** The saved searches, most recent first; empty while search history is switched off. */
    val recentSearches: StateFlow<List<SearchHistoryItem>> =
        history.getSearchHistoryFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    private var source = TvSearchSource.MUSIC
    private val searchJobs = EnumMap<TvSearchSource, Job>(TvSearchSource::class.java)
    private val moreJobs = EnumMap<TvSearchSource, Job>(TvSearchSource::class.java)
    private var suggestJob: Job? = null
    private var lastRecordedQuery: String? = null

    fun track(item: MetadataItem): MusicTrack? = trackFor(item)

    /** Typed on the keyboard: searches once typing pauses. */
    fun onQueryChange(query: String) = setQuery(query, searchDelayMs = DEBOUNCE_MS)

    /** Picked from a suggestion or the voice prompt: searches straight away and is saved. */
    fun submit(
        query: String,
        type: SearchType,
    ) {
        pick(query)
        rememberSearch(query, type)
    }

    /** Picked from the recent searches: searches straight away. */
    fun pick(query: String) = setQuery(query, searchDelayMs = 0L)

    /** The half on screen; it searches the current query now unless it already answers it. */
    fun showSource(target: TvSearchSource) {
        source = target
        search(target, _state.value.results(target).filterId, delayMs = 0L)
    }

    /** Selects a filter of the half on screen, or drops it when picked again; "Show all" selects its section's. */
    fun selectFilter(filterId: String) {
        val target = source
        val next = _state.value.results(target).toggled(filterId)
        if (_state.value.query.isBlank()) {
            _state.update { state -> state.withResults(target) { it.copy(filterId = next) } }
        } else {
            search(target, next, delayMs = 0L)
        }
    }

    fun showAll(filterId: String) {
        if (_state.value.results(source).filterId != filterId) selectFilter(filterId)
    }

    /** The end of [target]'s results came into view: fetches the next page once. */
    fun loadMore(target: TvSearchSource) {
        val results = _state.value.results(target)
        val cursor = results.nextCursor ?: return
        if (!results.loaded || results.isLoading || results.isLoadingMore) return
        _state.update { state -> state.withResults(target) { it.copy(isLoadingMore = true) } }
        val request = SearchRequest(results.query, results.filterId, cursor)
        moreJobs[target] =
            viewModelScope.launch {
                val page = attempt { backend(target).search(request) }
                currentCoroutineContext().ensureActive()
                _state.update { state ->
                    state.withResults(target) { current ->
                        if (current.nextCursor != cursor) return@withResults current
                        page.fold(
                            onSuccess = current::withNextPage,
                            onFailure = { error ->
                                Log.w(TAG, "$target search continuation failed", error)
                                current.copy(isLoadingMore = false)
                            },
                        )
                    }
                }
            }
    }

    /** Saves [query] once the listener acts on it, not for every pause in typing. */
    fun rememberSearch(
        query: String,
        type: SearchType = SearchType.TEXT,
    ) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) viewModelScope.launch { history.saveSearchQuery(trimmed, type) }
    }

    fun forgetSearch(item: SearchHistoryItem) {
        viewModelScope.launch { history.deleteSearchItem(item.id) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            history.clearSearchHistory()
            stats.onSearchHistoryCleared()
        }
    }

    private fun setQuery(
        query: String,
        searchDelayMs: Long,
    ) {
        savedStateHandle[QUERY_KEY] = query
        _state.update { it.copy(query = query) }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        suggest(trimmed)
        search(source, _state.value.results(source).filterId, searchDelayMs)
    }

    private fun clear() {
        suggestJob?.cancel()
        (searchJobs.values + moreJobs.values).forEach(Job::cancel)
        searchJobs.clear()
        moreJobs.clear()
        _state.update {
            it.copy(
                music = it.music.cleared(),
                videos = it.videos.cleared(),
                musicSuggestions = emptyList(),
                videoSuggestions = emptyList(),
            )
        }
    }

    private fun search(
        target: TvSearchSource,
        filterId: String?,
        delayMs: Long,
    ) {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        if (_state.value.results(target).answers(query, filterId, searchJobs[target]?.isActive == true)) return
        searchJobs[target]?.cancel()
        moreJobs[target]?.cancel()
        val start = { _state.update { state -> state.withResults(target) { it.searching(query, filterId) } } }
        if (delayMs == 0L) start()
        searchJobs[target] =
            viewModelScope.launch {
                if (delayMs > 0L) {
                    delay(delayMs)
                    start()
                }
                record(query)
                val page = attempt { backend(target).search(SearchRequest(query, filterId)) }
                currentCoroutineContext().ensureActive()
                _state.update { state ->
                    state.withResults(target) { current ->
                        page.fold(
                            onSuccess = current::withFirstPage,
                            onFailure = { error ->
                                if (!error.isNoPlugin) Log.w(TAG, "$target search failed", error)
                                current.failed(error.listenerMessage, noPlugin = error.isNoPlugin)
                            },
                        )
                    }
                }
            }
    }

    private fun suggest(query: String) {
        suggestJob?.cancel()
        suggestJob =
            viewModelScope.launch {
                delay(DEBOUNCE_MS)
                val (musical, visual) =
                    coroutineScope {
                        val musical = async { attempt { music.suggest(query) } }
                        val visual = async { attempt { videos.suggest(query) } }
                        musical.await() to visual.await()
                    }
                currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(
                        musicSuggestions = musical.getOrNull()?.queries.orEmpty(),
                        videoSuggestions = visual.getOrNull()?.queries.orEmpty(),
                    )
                }
            }
    }

    private suspend fun record(query: String) {
        val normalized = query.lowercase()
        if (normalized == lastRecordedQuery) return
        lastRecordedQuery = normalized
        stats.onSearch(query.takeIf { history.isSearchHistoryEnabled() })
    }

    private fun backend(target: TvSearchSource): TvSearchBackend =
        when (target) {
            TvSearchSource.MUSIC -> music
            TvSearchSource.VIDEOS -> videos
        }

    /** A plugin call that fails in any way other than being cancelled comes back as a failure. */
    private suspend fun <T> attempt(call: suspend () -> Result<T>): Result<T> =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val TAG = "TvSearch"
        const val QUERY_KEY = "query"
        const val DEBOUNCE_MS = 350L
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
