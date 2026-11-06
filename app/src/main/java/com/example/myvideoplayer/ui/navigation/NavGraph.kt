package com.example.myvideoplayer.ui.navigation

import androidx.compose.runtime.Composable; import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType; import androidx.navigation.compose.*; import androidx.navigation.navArgument
import com.example.myvideoplayer.ui.screen.*; import com.example.myvideoplayer.ui.viewmodel.MainViewModel; import com.example.myvideoplayer.ui.viewmodel.PlayerViewModel

object Routes { const val PLAYLIST_LIST = "playlist_list" }

@Composable
fun AppNavGraph() {
    val nav = rememberNavController(); val mvm: MainViewModel = viewModel(); val pvm: PlayerViewModel = viewModel()

    NavHost(navController = nav, startDestination = Routes.PLAYLIST_LIST) {
        composable(Routes.PLAYLIST_LIST) {
            PlaylistListScreen(vm = mvm, onAll = { nav.navigate("detail/all") },
                onPlaylist = { pl -> nav.navigate("detail/${pl.id}") },
                onCompare = { nav.navigate("compare") })
        }
        composable("detail/{pid}", arguments = listOf(navArgument("pid") { type = NavType.StringType })) { e ->
            val raw = e.arguments?.getString("pid") ?: "all"
            val pl = if (raw == "all") null else mvm.playlists.value.find { it.id == raw.toLongOrNull() ?: -1 }
            PlaylistDetailScreen(vm = mvm, playlist = pl, onBack = { nav.popBackStack() },
                onPlayVideo = { vids, idx -> mvm.setPlayingVideos(vids); nav.navigate("player/$idx") })
        }
        composable("player/{idx}", arguments = listOf(navArgument("idx") { type = NavType.IntType })) { e ->
            val idx = e.arguments?.getInt("idx") ?: 0
            PlayerScreen(pvm, mvm, mvm.playingVideos.value.ifEmpty { mvm.allVideos.value }, idx, { nav.popBackStack() })
        }
        composable("compare") {
            CompareScreen(vm = mvm, onBack = { nav.popBackStack() })
        }
    }
}
