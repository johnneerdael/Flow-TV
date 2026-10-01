package io.github.aedev.flow.ui.tv.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvMediaRow
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvMusicCard
import io.github.aedev.flow.ui.tv.components.TvMusicCollectionCard
import io.github.aedev.flow.ui.tv.components.TvPlaylistCard
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.components.TvVideoCard
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibraryCallbacks
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibraryContent
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibrarySection
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibraryViewModel
import io.github.aedev.flow.ui.tv.screens.account.TvAccountStatus
import io.github.aedev.flow.ui.tv.screens.account.TvPluginAccountViewModel
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import io.github.aedev.flow.ui.tv.toTvMusicTrack
import io.github.aedev.flow.ui.tv.toTvVideo
import io.github.aedev.flow.ui.tv.tvWatchProgress
import nl.neerdael.milkbeat.catalog.EntityRef

private const val LIBRARY_GRID_COLUMNS = 3

private enum class TvLibrarySection(
    @StringRes val titleRes: Int,
) {
    HISTORY(R.string.tv_library_history),
    LIKES(R.string.tv_library_likes),
    WATCH_LATER(R.string.tv_library_watch_later),
    PLAYLISTS(R.string.tv_library_playlists),
}

/**
 * Library hub mirroring mobile's mixed video + music library: every section
 * surfaces its played/saved songs as a music shelf above the video grid, and
 * Playlists covers both video and music playlists. When the music plugin has a
 * signed-in account, its library sections come first.
 */
@Composable
fun TvLibraryScreen(
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaylist: (String) -> Unit = {},
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit = { _, _, _ -> },
    onOpenMusicCollection: (String) -> Unit = {},
    onPlayMix: (MusicTrack) -> Unit = {},
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit = { _, _, _, _ -> },
    onOpenCatalog: (EntityRef) -> Unit = {},
    accountViewModel: TvPluginAccountViewModel = hiltViewModel(),
    accountLibrary: TvAccountLibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val historyRepository = remember { ViewHistory.getInstance(context.applicationContext) }
    val likedRepository = remember { LikedVideosRepository.getInstance(context.applicationContext) }
    val playlistRepository = remember { PlaylistRepository(context.applicationContext) }
    val history by historyRepository
        .getAllHistory()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val liked by likedRepository
        .getAllLikedVideos()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val watchLater by playlistRepository
        .getWatchLaterVideosFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val videoPlaylists by playlistRepository
        .getAllPlaylistsFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val musicPlaylists by playlistRepository
        .getMusicPlaylistsFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedSection by rememberSaveable { mutableStateOf(TvLibrarySection.HISTORY) }
    val dimens = LocalTvDimens.current
    val accountStatus by accountViewModel.status.collectAsStateWithLifecycle()
    val signedIn = accountStatus is TvAccountStatus.SignedIn
    var selectedAccountSection by rememberSaveable { mutableStateOf<TvAccountLibrarySection?>(null) }
    var accountOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val accountIdentity by accountLibrary.accountIdentity.collectAsStateWithLifecycle(initialValue = "")
    val accountTabs by accountLibrary.tabs.collectAsStateWithLifecycle()
    LaunchedEffect(accountViewModel) { accountViewModel.refresh() }
    LaunchedEffect(signedIn, accountIdentity) {
        if (accountIdentity.isNotEmpty()) accountLibrary.accountChanged(accountIdentity)
        if (signedIn && accountIdentity.isNotEmpty()) {
            if (accountOwner != accountIdentity || selectedAccountSection == null) selectedAccountSection = TvAccountLibrarySection.OVERVIEW
            accountOwner = accountIdentity
            accountLibrary.open(TvAccountLibrarySection.OVERVIEW)
        } else {
            selectedAccountSection = null
            accountOwner = null
        }
    }

    TvScreenScaffold(
        title = null,
        modifier = modifier,
        subtitle = if (accountStatus == TvAccountStatus.Expired) stringResource(R.string.tv_account_session_expired) else null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LazyRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .tvRowFocus(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
            ) {
                if (signedIn) {
                    items(accountTabs, key = { "account-${it.section.name}" }) { tab ->
                        val section = tab.section
                        TvFilterChip(
                            label = tab.label ?: stringResource(section.titleRes),
                            selected = selectedAccountSection == section,
                            onClick = { selectedAccountSection = section },
                        )
                    }
                }
                items(TvLibrarySection.entries, key = TvLibrarySection::name) { section ->
                    TvFilterChip(
                        label = stringResource(section.titleRes),
                        selected = selectedAccountSection == null && selectedSection == section,
                        onClick = {
                            selectedAccountSection = null
                            selectedSection = section
                        },
                    )
                }
            }

            val accountSection =
                selectedAccountSection?.takeIf { section ->
                    signedIn && accountOwner == accountIdentity && accountTabs.any { it.section == section }
                }
            if (accountSection != null) {
                TvAccountLibraryContent(
                    section = accountSection,
                    viewModel = accountLibrary,
                    callbacks =
                        TvAccountLibraryCallbacks(
                            onVideoClick = onVideoClick,
                            onOpenPlaylist = onOpenPlaylist,
                            onPlayMix = onPlayMix,
                            onPlayCollection = onPlayCollection,
                            onOpenCatalog = onOpenCatalog,
                        ),
                )
            } else {
                when (selectedSection) {
                    TvLibrarySection.HISTORY -> {
                        TvLibraryMixedContent(
                            musicTracks = history.filter { it.isMusic }.map { it.toTvMusicTrack() },
                            musicSource = stringResource(TvLibrarySection.HISTORY.titleRes),
                            videos =
                                history
                                    .filterNot { it.isMusic }
                                    .map { entry -> entry.toTvVideo() to entry.tvWatchProgress() },
                            onVideoClick = onVideoClick,
                            onPlayTrack = onPlayTrack,
                        )
                    }

                    TvLibrarySection.LIKES -> {
                        TvLibraryMixedContent(
                            musicTracks = liked.filter { it.isMusic }.map(LikedVideoInfo::toTvMusicTrack),
                            musicSource = stringResource(TvLibrarySection.LIKES.titleRes),
                            videos =
                                liked
                                    .filterNot { it.isMusic }
                                    .map { info -> info.toTvVideo() to null },
                            onVideoClick = onVideoClick,
                            onPlayTrack = onPlayTrack,
                        )
                    }

                    TvLibrarySection.WATCH_LATER -> {
                        TvLibraryMixedContent(
                            musicTracks = watchLater.filter { it.isMusic }.map(Video::toTvMusicTrack),
                            musicSource = stringResource(TvLibrarySection.WATCH_LATER.titleRes),
                            videos = watchLater.filterNot { it.isMusic }.map { it to null },
                            onVideoClick = onVideoClick,
                            onPlayTrack = onPlayTrack,
                        )
                    }

                    TvLibrarySection.PLAYLISTS -> {
                        TvLibraryPlaylists(
                            videoPlaylists =
                                videoPlaylists
                                    .filterNot { it.id == PlaylistRepository.WATCH_LATER_ID || it.id == PlaylistRepository.SAVED_SHORTS_ID }
                                    .map { info ->
                                        Playlist(
                                            id = info.id,
                                            name = info.name,
                                            thumbnailUrl = info.thumbnailUrl,
                                            videoCount = info.videoCount,
                                            description = info.description,
                                        )
                                    },
                            musicPlaylists =
                                musicPlaylists
                                    .filterNot {
                                        it.id == PlaylistRepository.WATCH_LATER_ID || it.id == PlaylistRepository.SAVED_SHORTS_ID
                                    },
                            onOpenPlaylist = onOpenPlaylist,
                            onOpenMusicCollection = onOpenMusicCollection,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvLibraryMixedContent(
    musicTracks: List<MusicTrack>,
    musicSource: String,
    videos: List<Pair<Video, Float?>>,
    onVideoClick: (Video) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
) {
    val dimens = LocalTvDimens.current
    if (musicTracks.isEmpty() && videos.isEmpty()) {
        TvMessageState(
            title = stringResource(R.string.tv_library_empty),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.overscanHorizontal),
        )
        return
    }
    ProvideTvColumnPivot {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cardWidth =
                (
                    maxWidth - dimens.overscanHorizontal * 2 -
                        dimens.itemSpacing * (LIBRARY_GRID_COLUMNS - 1)
                ) / LIBRARY_GRID_COLUMNS
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                contentPadding = PaddingValues(bottom = dimens.overscanVertical),
            ) {
                if (musicTracks.isNotEmpty()) {
                    item(key = "library-music") {
                        TvMediaRow(
                            items = musicTracks,
                            key = MusicTrack::videoId,
                            title = stringResource(R.string.nav_music),
                        ) { track ->
                            TvMusicCard(
                                track = track,
                                onClick = { onPlayTrack(track, musicTracks, musicSource) },
                            )
                        }
                    }
                }
                items(
                    items = videos.chunked(LIBRARY_GRID_COLUMNS),
                    key = { rowItems -> rowItems.first().first.id },
                ) { rowItems ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = dimens.overscanHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                    ) {
                        rowItems.forEach { (video, progress) ->
                            TvVideoCard(
                                video = video,
                                onClick = { onVideoClick(video) },
                                watchProgress = progress,
                                modifier = Modifier.width(cardWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvLibraryPlaylists(
    videoPlaylists: List<Playlist>,
    musicPlaylists: List<PlaylistInfo>,
    onOpenPlaylist: (String) -> Unit,
    onOpenMusicCollection: (String) -> Unit,
) {
    val dimens = LocalTvDimens.current
    if (videoPlaylists.isEmpty() && musicPlaylists.isEmpty()) {
        TvMessageState(
            title = stringResource(R.string.tv_library_empty),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.overscanHorizontal),
        )
        return
    }
    ProvideTvColumnPivot {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cardWidth =
                (
                    maxWidth - dimens.overscanHorizontal * 2 -
                        dimens.itemSpacing * (LIBRARY_GRID_COLUMNS - 1)
                ) / LIBRARY_GRID_COLUMNS
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                contentPadding = PaddingValues(bottom = dimens.overscanVertical),
            ) {
                if (musicPlaylists.isNotEmpty()) {
                    item(key = "music-playlists") {
                        TvMediaRow(
                            items = musicPlaylists,
                            key = { it.id },
                            title = stringResource(R.string.nav_music),
                        ) { info ->
                            TvMusicCollectionCard(
                                title = info.name,
                                subtitle = stringResource(R.string.tracks_count_template, info.videoCount),
                                thumbnailUrl = info.thumbnailUrl,
                                onClick = { onOpenMusicCollection(info.id) },
                            )
                        }
                    }
                }
                items(
                    items = videoPlaylists.chunked(LIBRARY_GRID_COLUMNS),
                    key = { rowItems -> rowItems.first().id },
                ) { rowItems ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = dimens.overscanHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                    ) {
                        rowItems.forEach { playlist ->
                            TvPlaylistCard(
                                playlist = playlist,
                                onClick = { onOpenPlaylist(playlist.id) },
                                modifier = Modifier.width(cardWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}
