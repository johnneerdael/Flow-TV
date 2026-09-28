package io.github.aedev.flow.ui.tv.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.music.CatalogPageViewModel
import io.github.aedev.flow.ui.screens.music.sharedMusicViewModel
import io.github.aedev.flow.ui.screens.search.SearchViewModel
import io.github.aedev.flow.ui.tv.screens.TvCatalogPageScreen
import io.github.aedev.flow.ui.tv.screens.TvChannelScreen
import io.github.aedev.flow.ui.tv.screens.TvLibraryScreen
import io.github.aedev.flow.ui.tv.screens.TvMusicCollectionScreen
import io.github.aedev.flow.ui.tv.screens.TvMusicScreen
import io.github.aedev.flow.ui.tv.screens.TvPlaylistDetailScreen
import io.github.aedev.flow.ui.tv.screens.TvSearchScreen
import io.github.aedev.flow.ui.tv.screens.TvSettingsScreen
import io.github.aedev.flow.ui.tv.screens.account.TvAccountSignInScreen
import nl.neerdael.milkbeat.catalog.EntityRef

/** Top-level TV navigation graph plus detail routes (channel, …). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvNavHost(
    navController: NavHostController,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    searchViewModel: SearchViewModel,
    onPlayVideo: (Video) -> Unit,
    onPlayPlaylist: (List<Video>, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val openChannel: (String) -> Unit = { channelRef ->
        navController.navigate(TvRoutes.channel(channelRef))
    }
    val openCatalog: (EntityRef) -> Unit = { navController.navigate(TvRoutes.catalog(it)) }

    NavHost(
        navController = navController,
        startDestination = TvDestination.start.route,
        modifier = modifier,
    ) {
        composable(TvDestination.MUSIC.route) {
            TvMusicScreen(
                onPlayCollection = onPlayCollection,
                onPlayMix = onPlayMix,
                onOpen = openCatalog,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(
            route = TvRoutes.CATALOG,
            arguments =
                listOf(
                    navArgument(CatalogPageViewModel.KIND_ARG) { type = NavType.StringType },
                    navArgument(CatalogPageViewModel.ID_ARG) { type = NavType.StringType },
                ),
        ) {
            TvCatalogPageScreen(
                onPlayMix = onPlayMix,
                onPlayCollection = onPlayCollection,
                onOpen = openCatalog,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(
            route = TvRoutes.MUSIC_COLLECTION,
            arguments = listOf(navArgument(TvRoutes.MUSIC_COLLECTION_ARG) { type = NavType.StringType }),
        ) { entry ->
            val collectionId = entry.arguments?.getString(TvRoutes.MUSIC_COLLECTION_ARG).orEmpty()
            TvMusicCollectionScreen(
                collectionId = collectionId,
                viewModel = sharedMusicViewModel(),
                onPlayCollection = onPlayCollection,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvDestination.SEARCH.route) {
            TvSearchScreen(
                viewModel = searchViewModel,
                onVideoClick = onPlayVideo,
                onChannelClick = openChannel,
                onOpenPlaylist = { navController.navigate(TvRoutes.playlist(it)) },
                onPlayMix = onPlayMix,
                onOpenCatalog = openCatalog,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvDestination.LIBRARY.route) {
            TvLibraryScreen(
                onVideoClick = onPlayVideo,
                onOpenPlaylist = { navController.navigate(TvRoutes.playlist(it)) },
                onPlayTrack = onPlayTrack,
                onOpenMusicCollection = { navController.navigate(TvRoutes.musicCollection(it)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvDestination.SETTINGS.route) {
            TvSettingsScreen(
                onOpenAccountSignIn = { navController.navigate(TvRoutes.ACCOUNT_SIGN_IN) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvRoutes.ACCOUNT_SIGN_IN) {
            TvAccountSignInScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = TvRoutes.CHANNEL,
            arguments =
                listOf(
                    navArgument(TvRoutes.CHANNEL_ARG) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
        ) { entry ->
            val channelRef =
                entry.arguments
                    ?.getString(TvRoutes.CHANNEL_ARG)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    .orEmpty()
            TvChannelScreen(
                channelUrl = channelRef,
                onVideoClick = onPlayVideo,
                onOpenPlaylist = { navController.navigate(TvRoutes.playlist(it)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(
            route = TvRoutes.PLAYLIST,
            arguments = listOf(navArgument(TvRoutes.PLAYLIST_ARG) { type = NavType.StringType }),
        ) {
            TvPlaylistDetailScreen(
                onVideoClick = onPlayVideo,
                onPlayPlaylist = onPlayPlaylist,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
