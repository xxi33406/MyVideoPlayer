package com.example.myvideoplayer.ui.screen.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.compose.state.rememberPresentationState
import com.example.myvideoplayer.playback.PlaybackUiState
import com.example.myvideoplayer.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放页的全部生命周期副作用。
 *
 * 抽成单独文件不是为了好看，而是因为这里有**四条互相独立、都必须正确**的
 * 资源策略，任何一条写错都只会在特定场景暴露（横屏残留、后台耗电、屏幕
 * 不灭、进度丢失），改别的功能时完全不会碰到它们，很容易一路烂下去。
 * 集中在一处后，读这个文件就等于读完了播放页所有"非 UI 逻辑"。
 *
 * | 副作用 | 写错的表现 |
 * |---|---|
 * | 方向切换与复位 | 退出播放页仍停在横屏 |
 * | 屏幕常亮 | 看视频时屏幕自己黑掉 / 暂停后屏幕永不熄灭 |
 * | 进度轮询 | 后台每 500ms 醒一次 CPU，或进度条不跟手 |
 * | 后台暂停解码 | 看不见画面但解码器满负荷跑，发热耗电 |
 */
@Composable
internal fun PlayerLifecycleEffects(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    isFastForward: Boolean,
    showControls: Boolean,
    showVolIndicator: Boolean,
    /** 播放页亮度（0..1）；-1 表示未调过、跟随系统 */
    brightness: Float,
    onHideControls: () -> Unit,
    onHideVolumeIndicator: () -> Unit
) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // ---- 方向：跟随视频比例，退出复位 ----
    //
    // 方向依据的优先级：
    // 1. **DB 里的宽高**（扫描时提取入库）——进播放页第一帧还没合成就知道方向，
    //    这是消除「横屏视频先竖屏闪一下」的关键。原来只靠 videoSizeDp（首帧解出），
    //    时序必然是 进页(竖屏)→首帧→尺寸解出→跳横屏。
    // 2. videoSizeDp（当前视频首帧的实际显示尺寸）——DB 没存到宽高的旧记录、
    //    或切集换了不同比例视频时的兜底。
    // 都没有时保持竖屏不动（等首帧）。
    //
    // 注意方向 effect 不能挂任何清理、更不能在这里 stop()：videoSize 从 null 变有值
    // 会重跑 effect，历史上把 detach/stop 放进它的 onDispose 曾把刚播 1.9 秒的
    // 播放器直接停掉（「进播放页默认暂停」的根因，见 2026-11-18-playback-resume-retro.md）。
    val currentVideo by viewModel.currentVideo.collectAsState()
    val dbIsLandscape = currentVideo?.let { it.width > 0 && it.height > 0 && it.width > it.height } == true
    val dbIsPortrait = currentVideo?.let { it.width > 0 && it.height > 0 && it.width <= it.height } == true
    val player by viewModel.player.collectAsState()
    val presentation = rememberPresentationState(player ?: return)
    val videoSize = presentation.videoSizeDp

    // 期望方向：DB 明确给了就用 DB；否则等首帧尺寸；首帧也没到就竖屏不动。
    // 折算成 Int 作为 DisposableEffect 的 key，让「DB 说横屏、首帧没到」这种
    // 组合不触发多余重跑。
    val desiredOrientation = when {
        dbIsLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        dbIsPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        videoSize == null -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> if (videoSize.width > videoSize.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // ---- 退出清理：只跑一次 ----
    // 这个 effect 的 key 必须是 Unit。曾经错误地用 videoSize 当 key：视频尺寸解出
    // （null -> Size）那一刻 Compose 会先 dispose 旧 effect，onDispose 里的
    // viewModel.stop() 把刚播了 1.9 秒的播放器直接停掉——用户看到的就是
    // "进播放页默认暂停，点播放还播错视频"。清理逻辑属于页面生命周期，
    // 不是视频属性，两者绝不能共用一个 key。
    DisposableEffect(Unit) {
        // 标记播放页活着：Service 靠这个判断能不能在空闲时自停。
        // 必须和 detach 成对，所以放这里。
        PlaybackUiState.attach()
        onDispose {
            PlaybackUiState.detach()
            // 方向复位必须在最前，且与 stop() 互相独立。
            // 原来写成 onDispose { viewModel.stop(); PORTRAIT }，一旦 stop() 抛异常
            // （播放器已 release），复位那句永远执行不到，表现就是"退出后仍横屏"。
            runCatching {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }.onFailure { Log.w("PlayerScreen", "方向复位失败", it) }
            // 进度循环有 5 秒节流，不补这一次会丢最后几秒
            viewModel.savePositionNow()
            viewModel.stop()
        }
    }

    // ---- 方向跟随视频比例 ----
    // 只设方向，不挂任何清理。key 是算好的期望方向：DB 宽高、首帧尺寸任一变化
    // 且导致方向翻转时才重跑；方向没变（如同为横屏）不重复设值。
    DisposableEffect(desiredOrientation) {
        activity.requestedOrientation = desiredOrientation
        onDispose { }
    }

    // ---- 亮度：跟随手势值覆盖本窗口，退出播放页恢复系统值 ----
    // 用 WindowManager.LayoutParams.screenBrightness 而不是 Settings.System：
    // 前者只影响本 Activity 的窗口，退出即自动还原；后者是全局设置，会改掉
    // 整个系统的亮度并触发系统设置动画。-1 是 BRIGHTNESS_OVERRIDE_NONE（跟随系统）。
    DisposableEffect(brightness) {
        val oldBrightness = activity.window.attributes.screenBrightness
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = brightness
        }
        onDispose {
            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = oldBrightness
            }
        }
    }

    // ---- 屏幕常亮：播放中常亮，暂停后再撑 30 秒 ----
    // 用 window 的 FLAG_KEEP_SCREEN_ON 而不是 Modifier 的 keepScreenOn：
    // 这里需要"暂停后延迟 30 秒再释放"，Modifier 那套只有布尔开关。
    DisposableEffect(isPlaying) {
        if (isPlaying) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } else {
            val keepAliveJob = scope.launch {
                delay(30_000)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                keepAliveJob.cancel()
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ---- 控制条自动隐藏：播放中 3 秒后收起 ----
    // isFastForward 是 key 之一：长按调速期间不能让控制条消失，
    // 否则用户一边调倍速一边看着界面闪。
    LaunchedEffect(showControls, isPlaying, isFastForward) {
        if (showControls && isPlaying && !isFastForward) {
            delay(3000)
            onHideControls()
        }
    }

    // ---- 音量提示：出现 800ms 后自动消失 ----
    LaunchedEffect(showVolIndicator) {
        if (showVolIndicator) {
            delay(800)
            onHideVolumeIndicator()
        }
    }

    // ---- 进度轮询：只在前台且播放中跑 ----
    // 原来只判断 isPlaying：切到后台后 Composable 仍在组合中，这个 while 循环
    // 会继续每 500ms 唤醒一次 CPU，是"后台耗电"的直接来源之一。
    LaunchedEffect(isPlaying, lifecycleOwner) {
        if (isPlaying) {
            try {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        viewModel.updatePosition()
                        delay(500)
                    }
                }
            } finally {
                // 切后台或暂停都会退出循环，补一次落盘，
                // 否则续播点会落后最多 5 秒（节流窗口）。
                viewModel.savePositionNow()
            }
        }
    }

    // ---- 前后台切换：不再主动暂停 ----
    //
    // P0-3 时这里写的是「切后台就 pauseForBackground()」，理由是当时播放器归
    // PlayerViewModel 所有：切到后台看不见画面，解码器却仍满负荷跑，实测 30 秒
    // 烧掉 9.17 秒 CPU（约 30%），主动暂停降到 0.17%。
    //
    // P2-1 之后播放器搬进 PlaybackService（前台服务 + MediaSession），这个判断
    // 要反过来：**继续播才是正确行为**。
    //   - 用户能听到声音，知道自己没关，不是"看不见的浪费"；
    //   - 通知栏与锁屏有完整的播放控制，不想听随时能停；
    //   - 从最近任务划掉 App 时 PlaybackService.onTaskRemoved 会收尾；
    //   - 播完最后一个视频，Service 自然停下。
    // 如果这里继续强行暂停，Service 就只剩下"进程不被随手杀掉"这一点用处，
    // 锁屏/通知栏控制也变得没有意义（放不出声音）。
    //
    // 只保留一件事：离开播放页时把进度存一次，别丢最后几秒。
    //
    // 后台省电的另一半在下面：切到后台时把视频轨道关掉。
    // 前台播放实测 24%、后台仍 15%，差的那 9% 是屏幕合成；而剩下 15% 里
    // 绝大部分是 MediaCodec 在解一个没人看得到的视频流。只解音频能把这
    // 部分省下来，回到前台再打开视频轨道，时间轴不会断。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    viewModel.savePositionNow()
                    viewModel.setVideoTrackEnabled(false)
                }
                Lifecycle.Event.ON_START -> viewModel.setVideoTrackEnabled(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
