package com.example.myvideoplayer.ui.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myvideoplayer.data.entity.PlaylistEntity
import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.ui.viewmodel.LoopMode
import com.example.myvideoplayer.ui.viewmodel.MainViewModel
import com.example.myvideoplayer.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    mainViewModel: MainViewModel,
    videos: List<VideoEntity>,
    startIndex: Int,
    onBack: () -> Unit
) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val loopMode by viewModel.loopMode.collectAsState()
    val isFastForward by viewModel.isFastForward.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val playlist by viewModel.playlist.collectAsState()
    val allPlaylists by mainViewModel.playlists.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var showSpeedIndicator by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(3f) }
    var showVolIndicator by remember { mutableStateOf(false) }
    var volLevel by remember { mutableIntStateOf(0) }
    var showSeekPreview by remember { mutableStateOf(false) }
    var seekTargetMs by remember { mutableLongStateOf(0L) }
    var showSidebar by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // 已包含当前视频的播放列表ID（用于过滤添加对话框）
    var existingPlaylistIds by remember { mutableStateOf(emptySet<Long>()) }
    val density = LocalDensity.current
    val context = LocalContext.current

    val audioManager = context.getSystemService(AudioManager::class.java)
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    var volume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val currentVideoName = videos.getOrNull(currentIndex)?.name ?: ""

    var lastTapTime by remember { mutableLongStateOf(0L) }

    val activity = context as Activity
    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            viewModel.stop()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    LaunchedEffect(showControls, isPlaying, isFastForward) {
        if (showControls && isPlaying && !isFastForward) { delay(3000); showControls = false }
    }
    LaunchedEffect(videos) {
        if (videos.isNotEmpty()) viewModel.setPlaylist(videos, startIndex)
    }
    // 仅播放时更新进度
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                viewModel.updatePosition()
                delay(500)
            }
        }
    }

    LaunchedEffect(showVolIndicator) {
        if (showVolIndicator) {
            delay(800)
            showVolIndicator = false
        }
    }

    // 收集添加提示消息
    LaunchedEffect(Unit) { mainViewModel.uiMessage.collect { snackbarHostState.showSnackbar(it) } }

    // 打开添加对话框时查询已包含当前视频的播放列表
    LaunchedEffect(showAddDialog) {
        if (showAddDialog) {
            val v = videos.getOrNull(currentIndex)
            if (v != null) existingPlaylistIds = mainViewModel.getExistingPlaylistIdsSync(listOf(v.id))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 视频播放器
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    player = viewModel.exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
                // 主区域：横滑快进 + 倍速 + 单击/双击（排除右 20% 音量区）
                .pointerInput(density, duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x; val startY = down.position.y
                        val w = size.width.toFloat()
                        if (startX > w * 0.8f) return@awaitEachGesture  // 跳过音量区
                        down.consume()

                        var isSeek = false; var isLong = false; var seekDelta = 0f
                        val result = withTimeoutOrNull(500L) {
                            // 等待期间检测横滑
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (ch.changedToUp()) { ch.consume(); break }
                                val dx = ch.position.x - startX
                                if (kotlin.math.abs(dx) > 30f && !isLong) {
                                    isSeek = true; seekDelta = dx
                                    // 显示快进预览
                                    val dpDx = with(density) { dx.toDp().value }
                                    val seekMs = (currentPosition + (dpDx * 1000).toLong()).coerceIn(0, duration)
                                    seekTargetMs = seekMs; showSeekPreview = true
                                }
                            }
                        }

                        if (isSeek) {
                            // 横滑快进：执行 seek
                            viewModel.seekTo(seekTargetMs); showSeekPreview = false
                        } else if (result == null) {
                            // 长按：倍速模式
                            showSpeedIndicator = true; currentSpeed = 3f; viewModel.setSpeed(3f)
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull() ?: break
                                    if (ch.changedToUp()) { ch.consume(); break }
                                    val dy = startY - ch.position.y
                                    val dpDy = with(density) { dy.toDp().value }
                                    val raw = 3f + (dpDy / 20f) * 0.5f
                                    currentSpeed = ((raw.coerceIn(2f, 5f) * 2f).roundToInt() / 2f).toFloat().coerceIn(2f, 5f)
                                    viewModel.setSpeed(currentSpeed)
                                }
                            } finally {
                                showSpeedIndicator = false; viewModel.setSpeed(1f)
                            }
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 350) {
                                viewModel.playPause(); lastTapTime = 0L
                            } else {
                                showControls = !showControls; lastTapTime = now
                            }
                        }
                    }
                }
                // 右侧 20% 区域：音量调节
                .pointerInput(maxVolume) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val x = down.position.x; val w = size.width.toFloat()
                        if (x < w * 0.8f) return@awaitEachGesture  // 只处理右侧
                        down.consume()
                        val initVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        var moved = false
                        try {
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull() ?: break
                                if (ch.changedToUp()) { ch.consume(); break }
                                val dy = ch.position.y - ch.previousPosition.y
                                val newV = (volume - (dy / 20f).roundToInt()).coerceIn(0, maxVolume)
                                if (newV != volume) {
                                    volume = newV; volLevel = newV; moved = true
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newV, 0)
                                    showVolIndicator = true
                                }
                            }
                        } finally {
                            // 松手后不立即隐藏，通过 LaunchedEffect 延迟隐藏
                            if (moved) showVolIndicator = true
                        }
                    }
                }
        )

        // 视频名（顶部）
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(Modifier.fillMaxWidth().background(Color.Black.copy(0.65f)).padding(horizontal = 56.dp, vertical = 12.dp)) {
                Text(currentVideoName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center))
            }
        }

        // 倍速
        androidx.compose.animation.AnimatedVisibility(
            visible = showSpeedIndicator,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp, start = 16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${formatSpeed(currentSpeed)}", color = Color.White, fontSize = 32.sp)
                Text("上下滑调速", color = Color.White.copy(0.7f), fontSize = 11.sp)
            }
        }

        // 横滑快进预览
        androidx.compose.animation.AnimatedVisibility(
            visible = showSeekPreview,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatTime(seekTargetMs), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("← 拖动快进 →", color = Color.White.copy(0.6f), fontSize = 12.sp)
            }
        }

        // 左上返回
        IconButton(onClick = { viewModel.stop(); onBack() }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(0.5f), CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
        }

        // 右上加入列表
        IconButton(onClick = { showAddDialog = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(0.5f), CircleShape)) {
            Icon(Icons.Filled.PlaylistAdd, "加入列表", tint = Color.White)
        }

        // 音量提示——调节时显示
        androidx.compose.animation.AnimatedVisibility(
            visible = showVolIndicator,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(Modifier
                .padding(end = 8.dp)
                .background(Color.Black.copy(0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VolumeUp, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    // 音量条
                    val frac = if (maxVolume > 0) volLevel.toFloat() / maxVolume else 0f
                    Box(Modifier.width(60.dp).height(6.dp).background(Color.White.copy(0.3f), RoundedCornerShape(3.dp))) {
                        Box(Modifier.width((60 * frac).dp).height(6.dp).background(Color.White, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }

        // 暂停大按钮
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls && !isPlaying,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            IconButton(onClick = { viewModel.playPause() }, modifier = Modifier.size(72.dp).background(Color.White.copy(0.3f), CircleShape)) {
                Icon(Icons.Filled.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }

        // 底部控制
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(0.75f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                val p = if (duration > 0) currentPosition.toFloat() / duration else 0f
                Slider(p, { viewModel.seekTo((it * duration).toLong()) }, Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.3f)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(currentPosition), color = Color.White, fontSize = 12.sp)
                    Text(formatTime(duration), color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.toggleLoopMode() }) {
                        Icon(if (loopMode == LoopMode.PLAYLIST) Icons.Filled.Repeat else Icons.Filled.RepeatOne, null, tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.previous() }) { Icon(Icons.Filled.SkipPrevious, "上一集", tint = Color.White) }
                    IconButton(onClick = { viewModel.playPause() }, modifier = Modifier.size(52.dp).background(Color.White.copy(0.2f), CircleShape)) {
                        Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = { viewModel.next() }) { Icon(Icons.Filled.SkipNext, "下一集", tint = Color.White) }
                    IconButton(onClick = { showSidebar = !showSidebar }) {
                        Icon(Icons.Filled.Menu, "列表", tint = if (showSidebar) Color.White else Color.White.copy(0.6f))
                    }
                }
            }
        }

        // 右侧侧栏
        androidx.compose.animation.AnimatedVisibility(
            visible = showSidebar,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd).width(140.dp).fillMaxHeight()
        ) {
            Column(Modifier.fillMaxSize().background(Color.Black.copy(0.9f)).padding(8.dp)) {
                Text("播放列表", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(playlist) { idx, video ->
                        val isCur = idx == currentIndex
                        Box(Modifier.fillMaxWidth().height(65.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (isCur) Color.White.copy(0.2f) else Color.White.copy(0.08f))
                            .clickable { viewModel.playVideoAt(idx); showSidebar = false }
                            .padding(6.dp), contentAlignment = Alignment.CenterStart) {
                            Text(video.name, color = if (isCur) Color.White else Color.White.copy(0.7f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        // 添加提示
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    // 加入播放列表对话框
    if (showAddDialog && currentVideoName.isNotEmpty()) {
        val available = allPlaylists.filterNot { it.id in existingPlaylistIds }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("加入播放列表") },
            text = {
                Column {
                    Text(currentVideoName, fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (allPlaylists.isEmpty()) Text("暂无播放列表")
                    else if (available.isEmpty()) Text("该视频已在所有播放列表中")
                    else LazyColumn {
                        items(available) { pl ->
                            TextButton(onClick = {
                                videos.getOrNull(currentIndex)?.let {
                                    mainViewModel.addVideosToPlaylist(listOf(it.id), pl.id, pl.name)
                                    existingPlaylistIds = existingPlaylistIds + pl.id  // 立即从UI移除
                                }
                                showAddDialog = false
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(pl.name)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddDialog = false }) { Text("关闭") } }
        )
    }
}

private fun formatTime(ms: Long): String = "%02d:%02d".format((ms / 1000).toInt() / 60, (ms / 1000).toInt() % 60)
private fun formatSpeed(s: Float) = if (s == s.toLong().toFloat()) s.toLong().toString() else String.format("%.1f", s)
