package com.example.myvideoplayer.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.example.myvideoplayer.ui.icons.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 播放页的浮层集合：顶部标题、倍速刻度、快进预览、音量条、暂停大按钮、错误提示。
 *
 * 定位由调用方通过 modifier 传入（BoxScope 上的 align 必须在 Box 的直接子级
 * 生效，本层没法自己决定贴哪边），这里只负责"长什么样"。
 *
 * 单独成文件是为了让"画面上叠了什么"一眼可查 —— 原来这些散落在 600 行文件里，
 * 中间夹着手势和状态机，很难确认某个浮层到底还会不会被显示。
 */

/** 顶部视频名。随控制条一起显隐 */
@Composable
internal fun PlayerTitleOverlay(visible: Boolean, title: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(0.65f))
                .padding(horizontal = 56.dp, vertical = 12.dp)
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * 倍速 HUD：画面右侧中部，竖排刻度 + 当前倍速。
 *
 * 原来放在左上角，32sp 裸数字（整数时 formatSpeed 返回 "3" 而不是 "3x"），
 * 还常驻一行 11sp 的「上下滑调速」，等于把调试信息压在画面上，看视频时视线
 * 每次都会被它拽走。现在改成靠右侧边缘的轻量刻度，遮挡更低，且松手即消失。
 */
@Composable
internal fun SpeedHud(visible: Boolean, speed: Float, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 5 档刻度，当前档位加粗变长
                listOf(5f, 4f, 3f, 2f, 1f).forEach { tick ->
                    val active = abs(speed - tick) < 0.25f
                    Box(
                        Modifier
                            .width(if (active) 22.dp else 12.dp)
                            .height(if (active) 3.dp else 2.dp)
                            .background(
                                if (active) Color.White else Color.White.copy(0.35f),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                formatSpeed(speed),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 横滑快进时的目标时间预览 */
@Composable
internal fun SeekPreviewOverlay(visible: Boolean, targetMs: Long, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                formatTime(targetMs),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text("← 拖动快进 →", color = Color.White.copy(0.6f), fontSize = 12.sp)
        }
    }
}

/** 音量条：调音量时出现 */
@Composable
internal fun VolumeOverlay(
    visible: Boolean,
    level: Int,
    maxLevel: Int,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            Modifier
                .background(Color.Black.copy(0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                val frac = if (maxLevel > 0) level.toFloat() / maxLevel else 0f
                Box(
                    Modifier
                        .width(60.dp)
                        .height(6.dp)
                        .background(Color.White.copy(0.3f), RoundedCornerShape(3.dp))
                ) {
                    Box(
                        Modifier
                            .width((60 * frac).dp)
                            .height(6.dp)
                            .background(Color.White, RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}

/** 暂停时的中央大播放按钮 */
@Composable
internal fun CenterPlayButton(visible: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        IconButton(
            onClick = onPlay,
            modifier = Modifier
                .size(72.dp)
                .background(Color.White.copy(0.3f), CircleShape)
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

/**
 * 播放失败提示。
 *
 * playerError 曾经只在 ViewModel 里写、全项目无人读：播放失败时用户看到的
 * 就是一整块纯黑画面 + 点了没反应的按钮，看起来和"手势全失效"一模一样。
 * 这个浮层就是那个缺口的补丁，让失败至少是"可见的失败"。
 */
@Composable
internal fun PlayerErrorOverlay(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            Modifier
                .padding(32.dp)
                .background(Color.Black.copy(0.85f), RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "播放失败",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    message.orEmpty(),
                    color = Color.White.copy(0.75f),
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) {
                    Text("知道了", color = Color.White)
                }
            }
        }
    }
}
