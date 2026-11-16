package com.example.myvideoplayer.ui.screen.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.media3.ui.compose.PlayerSurface
import com.example.myvideoplayer.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

// 手势判定结果：还没决定 / 横滑快进 / 纵向调亮度音量 / 长按倍速
private const val MODE_UNDECIDED = 0
private const val MODE_SEEK = 1
private const val MODE_VERTICAL = 2
private const val MODE_LONGPRESS = 3

/** 亮度下限：全黑和纯白都不给，留一点看得见/不刺眼的余地 */
private const val MIN_BRIGHTNESS = 0.05f

/**
 * 视频画面层：PlayerSurface 本体 + 手势检测。
 *
 * 独立成一个文件，是因为这一层有两条容易被改坏的不变量，值得与按钮、侧栏分开看：
 *
 * 1. **player 必须从 StateFlow 订阅**。播放器归 Service 所有、连上是异步的，且
 *    随播放路由销毁重建。曾用 AndroidView 包 PlayerView 手工补绑（factory 一次性
 *    绑死实例的历史 bug 见 2026-11-12-speed-and-memory-retro.md），现已换成
 *    androidx.media3.ui.compose.PlayerSurface——它内部自带同样的
 *    LaunchedEffect(player) 重绑逻辑（旧实例自动 clearVideoSurfaceView），
 *    这个坑类从源头消除，不再需要手写 update 块。
 *
 * 2. **pointerInput 的 key 只留 density / 音量上限**。原来把 duration 也当 key，
 *    视频时长从 0 变成真实值时（准备完成那一刻）会重建整个手势检测器，正在进行的
 *    拖动被凭空取消，用户表现为"滑到一半没反应了"。实时进度与音量改在回调里
 *    即时读取，不进 key。
 *
 * 手势分区（单 handler + 轴锁定，不再是"左 80% / 右 20%"两套）：
 * - 长按（按下后 500ms 内没有成型的拖动也没有抬指）：倍速，**全屏生效**——
 *   原来只在左 80% 生效，右半屏按下去没反应；
 * - 横滑：快进，两侧都生效；
 * - 纵滑：左半屏调亮度，右半屏调音量——先动哪个轴就锁哪个轴，之后不再换；
 * - 单击：切换控制条；350ms 内两次：播放/暂停。原来右 20% 音量区的单击双击
 *   直接被丢弃，用户点那儿什么都不会发生。
 */
@Composable
internal fun PlayerContent(
    viewModel: PlayerViewModel,
    /** 当前音量，由父层持有，这里只读 */
    volume: Int,
    /** 当前窗口亮度（0..1）；-1 表示还没调过、跟随系统 */
    brightness: Float,
    onVolumeChange: (Int) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeTouched: () -> Unit,
    onBrightnessTouched: () -> Unit,
    onSeekPreview: (targetMs: Long, visible: Boolean) -> Unit,
    onSpeedIndicatorChange: (visible: Boolean) -> Unit,
    onToggleControls: () -> Unit
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val audioManager = context.getSystemService(AudioManager::class.java)
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    // 手势 lambda 在 pointerInput 里被捕获，直接读 volume / brightness 会读到捕获
    // 那一刻的旧值。rememberUpdatedState 让它每次都读到最新值，同时不把它们放进
    // key（放进去会导致每调一次音量/亮度就重建手势检测器，拖动直接断掉）。
    val currentVolume by rememberUpdatedState(volume)
    val currentBrightness by rememberUpdatedState(brightness)

    // 手势里要读"最新"的进度，但不能为了它们订阅 StateFlow（那会把整页拉进
    // 每 500ms 一次的重组）。StateFlow.value 是线程安全的即时快照。
    fun latestPosition(): Long = viewModel.currentPosition.value
    fun latestDuration(): Long = viewModel.duration.value

    // 横滑灵敏度按时长自适应。
    //
    // 原来固定 1dp≈1s。这在本机的横屏（满屏约 914dp）上意味着「滑满一屏走 914 秒」：
    // 十分钟的片子滑一屏早就过头，短片手感没问题；但 30 分钟只走 51%，1 小时只走 25%，
    // 2 小时只走 13%——想去片尾得来回滑十几次，手势等于废掉。
    //
    // 路线图原本写「<10min 用 1dp/s，10min~1h 满屏跳 1/4，>1h 满屏跳 1/2」。
    // 实测算下来这个分档有硬伤：满屏跳 1/4 在分界点正好算出 984 ms/dp，
    // 被 1000 的下限钳住，所以 10 分钟到 1 小时这一段实际完全没有自适应；
    // 而跨过 1 小时时又从 984 突增到 1968，手感一次翻倍。
    //
    // 改成连续映射：满屏跳「全片时长的 1/4」是固定比例，
    //   msPerDp = duration / 4 / fullWidthDp
    // 但用下限 1000 兜住短片——低于 61 分钟的视频算出来不足 1000，全部落回基线，
    // 等价于「短片保持原来的固定手感」。所以单条公式就能覆盖全部时长，
    // 没有跳档，也不需要 SHORT/LONG 两个常量。
    fun seekMsPerDp(fullWidthPx: Float): Long {
        val durationMs = latestDuration()
        if (durationMs <= 0L) return DEFAULT_SEEK_MS_PER_DP
        val fullWidthDp = with(density) { fullWidthPx.toDp().value }.toFloat()
        if (fullWidthDp <= 0f) return DEFAULT_SEEK_MS_PER_DP
        return (durationMs / 4.0 / fullWidthDp).toLong()
            .coerceAtLeast(DEFAULT_SEEK_MS_PER_DP)
    }

    var lastTapTime by remember { mutableLongStateOf(0L) }

    // 播放器实例由 Service 持有，连上是异步的。订阅它：连上那一刻触发重组，
    // PlayerSurface 内部的 LaunchedEffect(player) 会把 surface 绑到新实例上，
    // 并对旧实例调 clearVideoSurfaceView（media3 1.10.1 源码 PlayerSurfaceInternal）。
    val mediaPlayer by viewModel.player.collectAsState()

    PlayerSurface(
        player = mediaPlayer,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(density, maxVolume) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val startX = down.position.x
                    val startY = down.position.y
                    val w = size.width.toFloat()
                    // 右半屏纵向调音量，左半屏纵向调亮度；横滑快进两侧都管
                    val isRightZone = startX >= w * 0.5f
                    var mode = MODE_UNDECIDED
                    var seekTargetMs = 0L

                    while (true) {
                        // 每个事件各自带 500ms 超时：手势还没定性且一直没动静，
                        // 就是长按倍速（全屏生效）。注意超时只取消这一次 await、
                        // 不取消整个手势循环，拖到一半停住也不会被误判成长按——
                        // mode 已锁定后超时分支什么都不做，继续等下一个事件。
                        val ev = withTimeoutOrNull(500L) { awaitPointerEvent() }
                        if (ev == null) {
                            if (mode == MODE_UNDECIDED) {
                                mode = MODE_LONGPRESS
                                // 走 startFastForward/stopFastForward 而不是直接
                                // setSpeed —— 只有经过这一对方法 isFastForward
                                // 才会被置位，控制条"长按期间不自动隐藏"的逻辑
                                // 才有判断依据。
                                viewModel.startFastForward()
                                onSpeedIndicatorChange(true)
                            }
                            continue
                        }
                        val ch = ev.changes.firstOrNull() ?: break
                        if (ch.changedToUp()) { ch.consume(); break }

                        if (mode == MODE_LONGPRESS) {
                            val dpDy = with(density) { (startY - ch.position.y).toDp().value }
                            // 默认 3x，上滑加速下滑减速，20dp 一档 0.5x，范围 2x~5x
                            val raw = 3f + (dpDy / 20f) * 0.5f
                            val speed = ((raw.coerceIn(2f, 5f) * 2f).roundToInt() / 2f)
                                .toFloat().coerceIn(2f, 5f)
                            viewModel.setSpeed(speed)
                            continue
                        }

                        val dx = ch.position.x - startX
                        val dy = ch.position.y - startY
                        if (mode == MODE_UNDECIDED) {
                            // 先动哪个轴就锁哪个轴，之后不再换——
                            // 避免"斜着滑又想快进又想调音量"的抖动
                            if (abs(dx) > 30f) mode = MODE_SEEK
                            else if (abs(dy) > 30f) mode = MODE_VERTICAL
                        }
                        when (mode) {
                            MODE_SEEK -> {
                                // 横滑距离换算成跳转量，比例按视频时长自适应
                                val dpDx = with(density) { dx.toDp().value }
                                val msPerDp = seekMsPerDp(w)
                                seekTargetMs = (latestPosition() + (dpDx * msPerDp).toLong())
                                    .coerceIn(0, latestDuration().coerceAtLeast(0))
                                onSeekPreview(seekTargetMs, true)
                            }
                            MODE_VERTICAL -> {
                                if (isRightZone) {
                                    // 20px 一档：再灵敏一点就会一滑到底
                                    val newV = (currentVolume -
                                        ((ch.position.y - ch.previousPosition.y) / 20f).roundToInt())
                                        .coerceIn(0, maxVolume)
                                    if (newV != currentVolume) {
                                        onVolumeChange(newV)
                                        audioManager.setStreamVolume(
                                            AudioManager.STREAM_MUSIC, newV, 0
                                        )
                                        onVolumeTouched()
                                    }
                                } else {
                                    // 满屏行程：从按下位置上滑满一屏 = 最亮，下滑满一屏 = 最暗
                                    val base = if (currentBrightness < 0f) {
                                        systemBrightness(context)
                                    } else {
                                        currentBrightness
                                    }
                                    val newB = (base + (startY - ch.position.y) / size.height)
                                        .coerceIn(MIN_BRIGHTNESS, 1f)
                                    if (newB != currentBrightness) {
                                        onBrightnessChange(newB)
                                        onBrightnessTouched()
                                    }
                                }
                            }
                        }
                    }

                    // 手势收尾：各模式各回各家
                    when (mode) {
                        MODE_SEEK -> {
                            viewModel.seekTo(seekTargetMs)
                            onSeekPreview(0L, false)
                        }
                        MODE_LONGPRESS -> {
                            onSpeedIndicatorChange(false)
                            viewModel.stopFastForward()
                        }
                    }
                    if (mode == MODE_UNDECIDED) {
                        // 点击：350ms 内第二次算双击
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
    )
}

/**
 * 系统亮度（0..1）。
 *
 * 窗口还没被覆盖过亮度时（BRIGHTNESS_OVERRIDE_NONE = -1），第一次调亮度需要
 * 一个起点，就从系统设置读真实值；部分设备读不到时退回中间值。
 */
private fun systemBrightness(context: Context): Float = try {
    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        .coerceIn(8, 255) / 255f
} catch (e: Exception) {
    0.5f
}

/**
 * 横滑 1dp 对应的跳转毫秒数，短片保底下限。
 *
 * 1dp 换 1 秒是手感基线：十分钟以内的片子滑满一屏正好到头，符合直觉。
 * 同时也是自适应公式的下限——低于 61 分钟的视频算出来的值都小于它，落回基线。
 */
private const val DEFAULT_SEEK_MS_PER_DP = 1_000L
