package com.example.myvideoplayer.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import com.example.myvideoplayer.ui.icons.Pause
import androidx.compose.material.icons.filled.PlayArrow
import com.example.myvideoplayer.ui.icons.Repeat
import com.example.myvideoplayer.ui.icons.RepeatOne
import com.example.myvideoplayer.ui.icons.SkipNext
import com.example.myvideoplayer.ui.icons.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myvideoplayer.ui.viewmodel.LoopMode
import com.example.myvideoplayer.ui.viewmodel.PlayerViewModel

/**
 * 底部控制条：进度滑块 + 时间 + 五个播放控制按钮。
 *
 * 单独抽出来的核心原因是**进度订阅的隔离**。currentPosition / duration 每 500ms
 * 变一次，如果在 PlayerScreen 顶层 collect，整个播放页（PlayerView、两套手势
 * 检测器、五个 AnimatedVisibility、侧栏 LazyColumn）会跟着全量重组。让这两个
 * StateFlow 只在这个函数体内被收集，重组范围就被限制在 Slider 和两个 Text 上。
 */
@Composable
internal fun PlayerBottomBar(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    loopMode: LoopMode,
    sidebarOpen: Boolean,
    onToggleSidebar: () -> Unit
) {
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(0.75f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
        // 时间直接挤在滑块两侧：原来单独占一行 + 6dp 间隔，黑框整体高了一大截
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatTime(currentPosition),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Slider(
                value = progress,
                onValueChange = { viewModel.seekTo((it * duration).toLong()) },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(0.3f)
                )
            )
            Text(
                formatTime(duration),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.toggleLoopMode() }) {
                Icon(
                    if (loopMode == LoopMode.PLAYLIST) Icons.Filled.Repeat else Icons.Filled.RepeatOne,
                    contentDescription = "循环模式",
                    tint = Color.White
                )
            }
            IconButton(onClick = { viewModel.previous() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一集", tint = Color.White)
            }
            IconButton(
                onClick = { viewModel.playPause() },
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.White.copy(0.2f), CircleShape)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "播放暂停",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = { viewModel.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一集", tint = Color.White)
            }
            IconButton(onClick = onToggleSidebar) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "列表",
                    tint = if (sidebarOpen) Color.White else Color.White.copy(0.6f)
                )
            }
        }
    }
}
