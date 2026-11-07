package com.example.myvideoplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myvideoplayer.ui.screen.CompareScreen
import com.example.myvideoplayer.ui.screen.PlayerScreen
import com.example.myvideoplayer.ui.screen.PlaylistDetailScreen
import com.example.myvideoplayer.ui.screen.PlaylistListScreen
import com.example.myvideoplayer.ui.viewmodel.MainViewModel
import com.example.myvideoplayer.ui.viewmodel.PlayerViewModel

/**
 * 全部导航路由集中在此，避免散落的字符串字面量出现拼写不一致。
 *
 * 每个路由同时提供「模式」（含占位符，注册用）和「构造器」（拼接实际路径，跳转用）。
 */
object Routes {
    const val PLAYLIST_LIST = "playlist_list"
    const val COMPARE = "compare"

    const val ARG_PID = "pid"
    const val ARG_IDX = "idx"

    /** 详情页 pid 取此值时表示「全部视频」，否则视为播放列表 id。 */
    const val PID_ALL = "all"

    const val DETAIL_PATTERN = "detail/{$ARG_PID}"
    const val PLAYER_PATTERN = "player/{$ARG_IDX}"

    fun detail(pid: String): String = "detail/$pid"
    fun detailAll(): String = detail(PID_ALL)
    fun detailOf(playlistId: Long): String = detail(playlistId.toString())
    fun player(index: Int): String = "player/$index"
}

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()
    // 作用域暂时保留在 Activity 级：切换路由不重建，避免列表页滚动位置与选中态丢失。
    // PlayerViewModel 的生命周期治理见 P3-2。
    val mvm: MainViewModel = viewModel()
    val pvm: PlayerViewModel = viewModel()

    NavHost(navController = nav, startDestination = Routes.PLAYLIST_LIST) {
        composable(Routes.PLAYLIST_LIST) {
            PlaylistListScreen(
                vm = mvm,
                onAll = { nav.navigate(Routes.detailAll()) },
                onPlaylist = { pl -> nav.navigate(Routes.detailOf(pl.id)) },
                onCompare = { nav.navigate(Routes.COMPARE) }
            )
        }
        composable(
            route = Routes.DETAIL_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_PID) { type = NavType.StringType })
        ) { entry ->
            val raw = entry.arguments?.getString(Routes.ARG_PID) ?: Routes.PID_ALL
            val playlist = if (raw == Routes.PID_ALL) {
                null
            } else {
                mvm.playlists.value.find { it.id == raw.toLongOrNull() ?: -1L }
            }
            PlaylistDetailScreen(
                vm = mvm,
                playlist = playlist,
                onBack = { nav.popBackStack() },
                onPlayVideo = { videos, index ->
                    mvm.setPlayingVideos(videos)
                    nav.navigate(Routes.player(index))
                }
            )
        }
        composable(
            route = Routes.PLAYER_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_IDX) { type = NavType.IntType })
        ) { entry ->
            val index = entry.arguments?.getInt(Routes.ARG_IDX) ?: 0
            PlayerScreen(
                viewModel = pvm,
                mainViewModel = mvm,
                videos = mvm.playingVideos.value.ifEmpty { mvm.allVideos.value },
                startIndex = index,
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.COMPARE) {
            CompareScreen(vm = mvm, onBack = { nav.popBackStack() })
        }
    }
}
