package com.example.myvideoplayer.ui.screen

import android.app.Activity
import android.content.res.Configuration
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.ui.screen.player.AddToPlaylistDialog
import com.example.myvideoplayer.ui.screen.player.CenterPlayButton
import com.example.myvideoplayer.ui.screen.player.PlayerBottomBar
import com.example.myvideoplayer.ui.screen.player.PlayerContent
import com.example.myvideoplayer.ui.screen.player.PlayerErrorOverlay
import com.example.myvideoplayer.ui.screen.player.PlayerLifecycleEffects
import com.example.myvideoplayer.ui.screen.player.PlayerSidebar
import com.example.myvideoplayer.ui.screen.player.PlayerTitleOverlay
import com.example.myvideoplayer.ui.screen.player.SeekPreviewOverlay
import com.example.myvideoplayer.ui.screen.player.SpeedHud
import com.example.myvideoplayer.ui.screen.player.VolumeOverlay
import com.example.myvideoplayer.ui.viewmodel.MainViewModel
import com.example.myvideoplayer.ui.viewmodel.PlayerViewModel

/**
 * 播放页。这个文件只做三件事：**订阅状态、编排子组件、处理用户意图**。
 *
 * | 关注点 | 位置 |
 * |---|---|
 * | 画面与两套手势 | player/PlayerContent.kt |
 * | 浮层（标题/倍速/快进/音量/暂停/错误） | player/PlayerOverlays.kt |
 * | 底部控制条（进度订阅隔离在这里） | player/PlayerBottomBar.kt |
 * | 右侧播放列表侧栏 | player/PlayerSidebar.kt |
 * | 加入播放列表对话框 | player/AddToPlaylistDialog.kt |
 * | 方向/常亮/轮询/后台暂停 | player/PlayerLifecycleEffects.kt |
 * | 时间与倍速格式化 | player/PlayerFormat.kt |
 *
 * 拆分动机：原来 601 行里同时住着两套手势检测器、六个浮层、一个 LazyColumn
 * 侧栏、一个对话框、方向切换、屏幕常亮、后台暂停、进度轮询 —— 改任何一个
 * 功能都要先在这个文件里重建一遍上下文。最直接的代价是上次排查"退出后仍
 * 横屏"时，方向复位那句藏在 onDispose 的第二个语句位置，一眼看不见它会被
 * 前面的异常跳过。
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    mainViewModel: MainViewModel,
    videos: List<VideoEntity>,
    startIndex: Int,
    onBack: () -> Unit
) {
    // 只订阅"会改变布局结构"的状态。currentPosition / duration 刻意不在这里收，
    // 它们每 500ms 变一次，在这一层订阅等于让整个播放页全量重组（见 PlayerBottomBar）。
    val isPlaying by viewModel.isPlaying.collectAsState()
    val loopMode by viewModel.loopMode.collectAsState()
    val isFastForward by viewModel.isFastForward.collectAsState()
    // 倍速只有 ViewModel 一份状态：原来 UI 里还有一份 currentSpeed，长按调速时
    // 两个一起改，谁也不知道以谁为准，HUD 显示与实际播放速度可能不一致。
    val currentSpeed by viewModel.playbackSpeed.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val playlist by viewModel.playlist.collectAsState()
    val allPlaylists by mainViewModel.playlists.collectAsState()
    val playerError by viewModel.playerError.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var showSpeedIndicator by remember { mutableStateOf(false) }
    var showVolIndicator by remember { mutableStateOf(false) }
    var showSeekPreview by remember { mutableStateOf(false) }
    var seekTargetMs by remember { mutableLongStateOf(0L) }
    var showSidebar by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var existingPlaylistIds by remember { mutableStateOf(emptySet<Long>()) }
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val audioManager = context.getSystemService(AudioManager::class.java)
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    val currentVideoName = videos.getOrNull(currentIndex)?.name ?: ""

    // 方向、屏幕常亮、进度轮询、后台暂停，全部在另一个文件里
    PlayerLifecycleEffects(
        viewModel = viewModel,
        isPlaying = isPlaying,
        isFastForward = isFastForward,
        showControls = showControls,
        showVolIndicator = showVolIndicator,
        onHideControls = { showControls = false },
        onHideVolumeIndicator = { showVolIndicator = false }
    )

    LaunchedEffect(videos) {
        if (videos.isNotEmpty()) viewModel.setPlaylist(videos, startIndex)
    }

    LaunchedEffect(Unit) {
        mainViewModel.uiMessage.collect { snackbarHostState.showSnackbar(it) }
    }

    // 打开添加对话框时查询已包含当前视频的播放列表
    LaunchedEffect(showAddDialog) {
        if (showAddDialog) {
            videos.getOrNull(currentIndex)?.let { video ->
                existingPlaylistIds = mainViewModel.getExistingPlaylistIdsSync(listOf(video.id))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerContent(
            viewModel = viewModel,
            volume = volume,
            onVolumeChange = { volume = it },
            onVolumeTouched = { showVolIndicator = true },
            onSeekPreview = { target, visible ->
                seekTargetMs = target
                showSeekPreview = visible
            },
            onSpeedIndicatorChange = { showSpeedIndicator = it },
            onToggleControls = { showControls = !showControls }
        )

        PlayerTitleOverlay(
            visible = showControls,
            title = currentVideoName,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        SpeedHud(
            visible = showSpeedIndicator,
            speed = currentSpeed,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)
        )

        SeekPreviewOverlay(
            visible = showSeekPreview,
            targetMs = seekTargetMs,
            modifier = Modifier.align(Alignment.Center)
        )

        // 左上返回
        IconButton(
            onClick = { viewModel.stop(); onBack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color.Black.copy(0.5f), CircleShape)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
        }

        // 右上加入列表
        IconButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(0.5f), CircleShape)
        ) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "加入列表", tint = Color.White)
        }

        VolumeOverlay(
            visible = showVolIndicator,
            level = volume,
            maxLevel = maxVolume,
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        CenterPlayButton(
            visible = showControls && !isPlaying,
            onPlay = { viewModel.playPause() },
            modifier = Modifier.align(Alignment.Center)
        )

        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlayerBottomBar(
                viewModel = viewModel,
                isPlaying = isPlaying,
                loopMode = loopMode,
                sidebarOpen = showSidebar,
                onToggleSidebar = { showSidebar = !showSidebar }
            )
        }

        AnimatedVisibility(
            visible = showSidebar,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(140.dp)
                .fillMaxHeight()
        ) {
            PlayerSidebar(
                playlist = playlist,
                currentIndex = currentIndex,
                onPlayAt = { idx ->
                    viewModel.playVideoAt(idx)
                    showSidebar = false
                }
            )
        }

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
        )

        PlayerErrorOverlay(
            message = playerError,
            onDismiss = { viewModel.clearError() },
            modifier = Modifier.align(Alignment.Center)
        )
    }

    if (showAddDialog && currentVideoName.isNotEmpty()) {
        AddToPlaylistDialog(
            videoName = currentVideoName,
            allPlaylists = allPlaylists,
            available = allPlaylists.filterNot { it.id in existingPlaylistIds },
            onPick = { playlistEntity ->
                videos.getOrNull(currentIndex)?.let { video ->
                    mainViewModel.addVideosToPlaylist(
                        listOf(video.id),
                        playlistEntity.id,
                        playlistEntity.name
                    )
                    existingPlaylistIds = existingPlaylistIds + playlistEntity.id
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}
