package io.github.aedev.flow.ui.tv.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.data.local.matching
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.ui.screens.music.MusicSearchViewModel
import io.github.aedev.flow.ui.screens.search.SearchViewModel
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvKeyboard
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.delay

private const val SUGGESTION_CHIPS = 8
private const val RECENT_SUGGESTIONS = 3

private enum class TvSearchTop(
    @StringRes val labelRes: Int,
) {
    MUSIC(R.string.nav_music),
    VIDEOS(R.string.tv_filter_videos),
}

private enum class TvVideoSubFilter(
    val contentType: ContentType,
    @StringRes val labelRes: Int,
) {
    CHANNELS(ContentType.CHANNELS, R.string.tv_filter_channels),
    PLAYLISTS(ContentType.PLAYLISTS, R.string.tv_filter_playlists),
    LIVE(ContentType.LIVE, R.string.tv_filter_live),
}

private enum class TvMusicSubFilter(
    @StringRes val labelRes: Int,
) {
    SONGS(R.string.filter_songs),
    ARTISTS(R.string.tv_filter_artists),
    ALBUMS(R.string.filter_albums),
}

private fun TvMusicSubFilter.toYouTubeFilter(): YouTube.SearchFilter =
    when (this) {
        TvMusicSubFilter.SONGS -> YouTube.SearchFilter.FILTER_SONG
        TvMusicSubFilter.ARTISTS -> YouTube.SearchFilter.FILTER_ARTIST
        TvMusicSubFilter.ALBUMS -> YouTube.SearchFilter.FILTER_ALBUM
    }

/**
 * D-pad-first search: grid keyboard on the left, live results on the right.
 * Primary chips select Music (always the starting point) / Videos; Videos exposes channel/playlist/
 * live sub-filters and Music exposes song/artist/album sub-filters backed by
 * the shared [MusicSearchViewModel]. Suggestion chips appear while typing.
 */
@Composable
fun TvSearchScreen(
    viewModel: SearchViewModel,
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    onChannelClick: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit = { _, _, _ -> },
    onOpenMusicCollection: (String) -> Unit = {},
    onOpenMusicArtist: (String) -> Unit = {},
    musicSearchViewModel: MusicSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dimens = LocalTvDimens.current
    var query by rememberSaveable { mutableStateOf("") }
    // Not saveable on purpose: every visit to Search starts on Music.
    var topFilter by remember { mutableStateOf(TvSearchTop.MUSIC) }
    var videoSubFilter by remember { mutableStateOf<TvVideoSubFilter?>(null) }
    var musicSubFilter by remember { mutableStateOf<TvMusicSubFilter?>(null) }
    val results = viewModel.searchResults.collectAsLazyPagingItems()
    val musicState by musicSearchViewModel.uiState.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    var videoSuggestions by remember { mutableStateOf(emptyList<String>()) }

    val voiceLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.let {
                        query = it
                        viewModel.rememberSearch(it, SearchType.VOICE)
                    }
            }
        }
    val voiceIntent =
        remember {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
        }
    val voiceAvailable =
        remember {
            voiceIntent.resolveActivity(context.packageManager) != null
        }

    // Live search with a short debounce as the user types on the grid keyboard.
    LaunchedEffect(query, topFilter, videoSubFilter, musicSubFilter) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            viewModel.clearSearch()
            musicSearchViewModel.clearSearch()
            videoSuggestions = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        when (topFilter) {
            TvSearchTop.MUSIC -> {
                musicSearchViewModel.onQueryChange(trimmed)
                val subFilter = musicSubFilter
                if (subFilter == null) {
                    musicSearchViewModel.performSearch(trimmed)
                } else {
                    musicSearchViewModel.applyFilter(subFilter.toYouTubeFilter())
                }
            }

            TvSearchTop.VIDEOS -> {
                videoSuggestions =
                    runCatching { viewModel.getSearchSuggestions(trimmed).map { it.text } }
                        .getOrDefault(emptyList())
                val contentType = videoSubFilter?.contentType ?: ContentType.VIDEOS
                viewModel.search(trimmed, SearchFilter(contentType = contentType))
            }
        }
    }

    val liveSuggestions = if (topFilter == TvSearchTop.MUSIC) musicState.suggestions else videoSuggestions
    // Searches made before come first, as they do on YouTube.
    val suggestions =
        (recentSearches.matching(query.trim(), RECENT_SUGGESTIONS).map { it.query } + liveSuggestions)
            .distinct()
            .take(SUGGESTION_CHIPS)
    // A live search runs on every pause in typing; only acting on a result saves the query.
    val remembered = { viewModel.rememberSearch(query) }

    Row(
        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    start = dimens.overscanHorizontal,
                    end = dimens.overscanHorizontal,
                    top = dimens.overscanVertical,
                ),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            modifier = Modifier.width(340.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = query.ifBlank { stringResource(R.string.tv_search_prompt) },
                style = MaterialTheme.typography.headlineSmall,
                color =
                    if (query.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TvKeyboard(
                onInput = { query += it },
                onDelete = { query = query.dropLast(1) },
                onClear = { query = "" },
                onVoice =
                    if (voiceAvailable) {
                        { voiceLauncher.launch(voiceIntent) }
                    } else {
                        null
                    },
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LazyRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .tvRowFocus(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(TvSearchTop.entries, key = TvSearchTop::name) { top ->
                    TvFilterChip(
                        label = stringResource(top.labelRes),
                        selected = topFilter == top,
                        onClick = {
                            topFilter = top
                            videoSubFilter = null
                            musicSubFilter = null
                        },
                    )
                }
            }

            when (topFilter) {
                TvSearchTop.VIDEOS -> {
                    LazyRow(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .tvRowFocus(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(TvVideoSubFilter.entries, key = TvVideoSubFilter::name) { sub ->
                            TvFilterChip(
                                label = stringResource(sub.labelRes),
                                selected = videoSubFilter == sub,
                                onClick = {
                                    videoSubFilter = if (videoSubFilter == sub) null else sub
                                },
                            )
                        }
                    }
                }

                TvSearchTop.MUSIC -> {
                    LazyRow(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .tvRowFocus(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(TvMusicSubFilter.entries, key = TvMusicSubFilter::name) { sub ->
                            TvFilterChip(
                                label = stringResource(sub.labelRes),
                                selected = musicSubFilter == sub,
                                onClick = {
                                    musicSubFilter = if (musicSubFilter == sub) null else sub
                                },
                            )
                        }
                    }
                }
            }

            if (query.isNotBlank() && suggestions.isNotEmpty()) {
                LazyRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .tvRowFocus(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(suggestions, key = { it }) { suggestion ->
                        TvFilterChip(
                            label = suggestion,
                            selected = false,
                            onClick = {
                                query = suggestion
                                viewModel.rememberSearch(suggestion, SearchType.SUGGESTION)
                            },
                        )
                    }
                }
            }

            if (query.isBlank() && recentSearches.isNotEmpty()) {
                TvRecentSearches(
                    history = recentSearches,
                    onPick = { query = it },
                    onForget = viewModel::forgetSearch,
                    onClear = viewModel::clearSearchHistory,
                    modifier = Modifier.weight(1f),
                )
            } else if (topFilter == TvSearchTop.MUSIC) {
                TvMusicSearchResults(
                    query = query,
                    state = musicState,
                    filtered = musicSubFilter != null,
                    onPlayTrack = { track, queue, source ->
                        remembered()
                        onPlayTrack(track, queue, source)
                    },
                    onOpenMusicCollection = {
                        remembered()
                        onOpenMusicCollection(it)
                    },
                    onOpenMusicArtist = {
                        remembered()
                        onOpenMusicArtist(it)
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                TvVideoSearchResults(
                    query = query,
                    results = results,
                    onVideoClick = {
                        remembered()
                        onVideoClick(it)
                    },
                    onChannelClick = {
                        remembered()
                        onChannelClick(it)
                    },
                    onOpenPlaylist = {
                        remembered()
                        onOpenPlaylist(it)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
