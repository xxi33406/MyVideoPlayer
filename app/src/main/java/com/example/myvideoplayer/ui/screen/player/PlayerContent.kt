package com.example.myvideoplayer.ui.screen.player

import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myvideoplayer.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 视频画面层：PlayerView 本体 + 两套手势检测。
 *
 * 独立成一个文件，是因为这一层有两条容易被改坏的不变量，值得与按钮、侧栏分开看：
 *
 * 1. **AndroidView 必须有 update 块**。factory 只在首次组合时执行一次，写进去的
 *    `player = viewModel.exoPlayer` 会把 PlayerView 永久绑死在"那一刻"的实例上。
 *    P3 把 PlayerViewModel 收束到播放路由后，ExoPlayer 会随路由销毁重建，而
 *    PlayerView 仍指向已 release 的旧实例：画面停在最后一帧，旧 PlayerView 处于
 *    失效状态还会吞掉触摸事件，表现就是"所有按键手势都失效"。
 *
 * 2. **pointerInput 的 key 只留 density / 音量上限**。原来把 duration 也当 key，
 *    视频时长从 0 变成真实值时（准备完成那一刻）会重建整个手势检测器，正在进行的
 *    拖动被凭空取消，用户表现为"滑到一半没反应了"。实时进度与音量改在回调里
 *    即时读取，不进 key。
 *
 * 手势分区：左 80% 主区域（横滑快进 / 长按倍速 / 单击切控制条 / 双击播放暂停），
 * 右 20% 音量区（上下滑调 STREAM_MUSIC）。
 */
@Composable
internal fun PlayerContent(
    viewModel: PlayerViewModel,
    /** 当前音量，由父层持有，这里只读 */
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    onVolumeTouched: () -> Unit,
    onSeekPreview: (targetMs: Long, visible: Boolean) -> Unit,
    onSpeedIndicatorChange: (visible: Boolean) -> Unit,
    onToggleControls: () -> Unit
) {
    val density = LocalDensity.current
    val audioManager = LocalContext.current.getSystemService(AudioManager::class.java)
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    // 手势 lambda 在 pointerInput 里被捕获，直接读 volume 会读到捕获那一刻的旧值。
    // rememberUpdatedState 让它每次都读到最新值，同时不把 volume 放进 key
    // （放进去会导致每调一次音量就重建手势检测器，拖动直接断掉）。
    val currentVolume by rememberUpdatedState(volume)

    // 手势里要读"最新"的进度，但不能为了它们订阅 StateFlow（那会把整页拉进
    // 每 500ms 一次的重组）。StateFlow.value 是线程安全的即时快照。
    fun latestPosition(): Long = viewModel.currentPosition.value
    fun latestDuration(): Long = viewModel.duration.value

    var lastTapTime by remember { mutableLongStateOf(0L) }

    AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                useController = false
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        // 这一行不能少：把 PlayerView 重新绑到当前的 ExoPlayer 实例上
        update = { playerView -> playerView.player = viewModel.exoPlayer },
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 左 80%：横滑快进 + 长按倍速 + 单击/双击
            .pointerInput(density) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y
                    val w = size.width.toFloat()
                    if (startX > w * 0.8f) return@awaitEachGesture // 归音量区管
                    down.consume()

                    var isSeek = false
                    var seekTargetMs = 0L
                    // 500ms 内抬指算点击，超时算长按。
                    // 用"超时"区分而不是另起一个计时器并发判断，省掉一份状态。
                    val result = withTimeoutOrNull(500L) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (ch.changedToUp()) { ch.consume(); break }
                            val dx = ch.position.x - startX
                            if (abs(dx) > 30f) {
                                isSeek = true
                                // 1dp ≈ 1 秒：横滑距离直接换算成跳转量
                                val dpDx = with(density) { dx.toDp().value }
                                seekTargetMs = (latestPosition() + (dpDx * 1000).toLong())
                                    .coerceIn(0, latestDuration().coerceAtLeast(0))
                                onSeekPreview(seekTargetMs, true)
                            }
                        }
                    }

                    if (isSeek) {
                        viewModel.seekTo(seekTargetMs)
                        onSeekPreview(0L, false)
                    } else if (result == null) {
                        // 长按：倍速。走 startFastForward/stopFastForward 而不是直接
                        // setSpeed —— 只有经过这一对方法 isFastForward 才会被置位，
                        // 控制条"长按期间不自动隐藏"的逻辑才有判断依据。
                        viewModel.startFastForward()
                        onSpeedIndicatorChange(true)
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (ch.changedToUp()) { ch.consume(); break }
                                val dpDy = with(density) { (startY - ch.position.y).toDp().value }
                                // 默认 3x，上滑加速下滑减速，20dp 一档 0.5x，范围 2x~5x
                                val raw = 3f + (dpDy / 20f) * 0.5f
                                val speed = ((raw.coerceIn(2f, 5f) * 2f).roundToInt() / 2f)
                                    .toFloat().coerceIn(2f, 5f)
                                viewModel.setSpeed(speed)
                            }
                        } finally {
                            onSpeedIndicatorChange(false)
                            viewModel.stopFastForward()
                        }
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 350) {
                            viewModel.playPause() // 双击
                            lastTapTime = 0L
                        } else {
                            onToggleControls() // 单击切换控制条
                            lastTapTime = now
                        }
                    }
                }
            }
            // 右 20%：上下滑调音量
            .pointerInput(maxVolume) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    if (down.position.x < w * 0.8f) return@awaitEachGesture
                    down.consume()
                    var moved = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (ch.changedToUp()) { ch.consume(); break }
                            val dy = ch.position.y - ch.previousPosition.y
                            // 20px 一档：再灵敏一点就会一滑到底
                            val newV = (currentVolume - (dy / 20f).roundToInt())
                                .coerceIn(0, maxVolume)
                            if (newV != currentVolume) {
                                onVolumeChange(newV)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newV, 0)
                                onVolumeTouched()
                                moved = true
                            }
                        }
                    } finally {
                        // 松手后交给父层的 LaunchedEffect 延迟隐藏
                        if (moved) onVolumeTouched()
                    }
                }
            }
    )
}
