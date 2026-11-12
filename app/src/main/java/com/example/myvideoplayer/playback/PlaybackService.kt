package com.example.myvideoplayer.playback

import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.myvideoplayer.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 承载播放器的前台 Service。
 *
 * 为什么播放器要搬到这里：P2-1 之前 ExoPlayer 归 PlayerViewModel 所有，而
 * PlayerViewModel 挂在播放路由上——退出播放页就 release。这带来两个后果：
 *
 * 1. 切后台再回来，只要系统回收了 Activity，播放就断了，声音也断了；
 * 2. 更糟的是为了省电，P0-3 只能做成「切后台就暂停」。实测切后台 30 秒仍烧掉
 *    9.17 秒 CPU（约 30%），因为 Composable 还在组合里、解码器还在跑；改成
 *    主动暂停降到 0.17%，但代价是切后台听不了声音。
 *
 * Service + MediaSession 之后播放器归 Service，App 退到后台进程仍活着、仍能出声，
 * 通知栏与锁屏也能控制。ViewModel 那侧换成 MediaController，只发命令、不持有播放器。
 *
 * 进度落库也搬到这里：因为判断"当前在放哪个视频"需要 videoId，而 videoId 存在
 * MediaItem.mediaId 里（由 ViewModel 构造时写入），Service 直接读得到，
 * 不必再让 UI 层轮询着写数据库。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * 播放器相关的协程。**必须是主线程**：ExoPlayer 只允许创建它的线程访问，
     * 在别的线程读 position 会抛 IllegalStateException 并且是 FATAL。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 只用来写数据库，不碰播放器 */
    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 周期保存进度的计时协程；跟着播放状态起停 */
    private var progressJob: Job? = null

    private val repository by lazy { AppContainer.of(this).videoRepository }

    /** 上次落库时间，用于节流 */
    private var lastSavedAt = 0L

    /**
     * 空闲自停的计时协程。
     *
     * 播放器停下来（暂停、播完、出错）之后，这个 Service 连同它持有的
     * ExoPlayer、MediaCodec、MediaSession 会一直活着——实测退出播放页后
     * 进程 PSS 比"只启动不播放"高约 25MB，解码器线程也没释放。
     *
     * 用户只是退出播放页而不是要后台听，没有任何东西需要它继续活着。
     * 所以停够 [IDLE_STOP_DELAY_MS] 就自行 stopSelf()，把内存还给系统；
     * 下次播放时 ViewModel 重新 connect() 会拉起一个全新的 Service。
     */
    private var idleJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = DefaultDataSource.Factory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 解码器兜底：默认关闭，遇到不支持的编码会直接抛 DecoderInitializationException
        // 而不是换一个解码器（含软解）再试。代价是极端情况下多一次初始化。
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                // 播放期间持有 CPU 唤醒锁。不设的话系统在息屏/低电时可以挂起进程，
                // 表现为音频断续、画面卡住然后被反复唤醒重试——比正常播放更费电。
                setWakeMode(C.WAKE_MODE_NETWORK)
                // 拔耳机/断蓝牙时自动暂停，避免突然外放
                setHandleAudioBecomingNoisy(true)
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            cancelIdleStop()
                            startProgressSaver()
                        } else {
                            stopProgressSaver(save = true)
                            // 也调度一次：退出播放页时 onDispose 走的是
                            // stop() + clearMediaItems()，只触发 isPlaying=false 和
                            // STATE_IDLE，不触发 playWhenReady 变化、也不是 STATE_ENDED。
                            // 不在这里调度，那条最常见的退出路径就永远等不到自停。
                            scheduleIdleStop()
                        }
                    }

                    /**
                     * 用 playWhenReady 区分「用户还想播」与「用户不想播了」。
                     *
                     * 一开始只靠 isPlaying 判断，实测踩了两个坑：
                     * 1. 一个文件夹里连着几个十几秒的短视频，播完自动切下一集，
                     *    切集瞬间 isPlaying 短暂变 false，被当成"用户暂停"，倒计时
                     *    启动，几十秒后 Service 自杀——表现为"后台播着播着没声了"。
                     * 2. 用户在播放页主动暂停后退出，isPlaying 早就是 false，
                     *    不再有事件，倒计时永远等不到。
                     *
                     * 真正的判据放在 [scheduleIdleStop] 的循环里（每轮重新读
                     * playWhenReady 与 mediaItemCount），这里只负责"有事发生就调度"。
                     */
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (playWhenReady) cancelIdleStop() else scheduleIdleStop()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            stopProgressSaver(save = true)
                            scheduleIdleStop()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "播放失败: ${error.errorCodeName} - ${error.message}", error)
                        stopProgressSaver(save = true)
                        scheduleIdleStop()
                    }
                })
            }

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从最近任务列表划掉 App 时调用。
     *
     * 如果还在播就让它继续（这是音乐/视频播放器的常规行为，Service 会以
     * 前台服务身份活下去）；已经停了就彻底收掉，别留一个空 Service 占内存。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopProgressSaver(save = true)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopProgressSaver(save = true)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        dbScope.cancel()
        super.onDestroy()
    }

    // ==================== 进度落库 ====================

    private fun startProgressSaver() {
        if (progressJob?.isActive == true) return
        // 必须用主线程调度器：这个循环每 5 秒读一次 player 的位置，
        // 而 ExoPlayer 只允许创建它的线程访问，在 IO 线程读会抛
        // "Player is accessed on the wrong thread"。
        progressJob = serviceScope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                saveProgress(force = false)
            }
        }
    }

    private fun stopProgressSaver(save: Boolean) {
        progressJob?.cancel()
        progressJob = null
        if (save) saveProgress(force = true)
    }

    // ==================== 空闲自停 ====================

    /**
     * 播放器停下后开始倒计时，到期就停掉 Service。
     *
     * 为什么要延迟而不是立刻停：暂停后再播放（比如切去别的 App 看一眼又回来）
     * 应当立刻接着放，不该重新创建 ExoPlayer。延迟一段时间覆盖这种短暂离开。
     *
     * 判据是三个条件的组合，任何一个写错都会导致「该停的没停」或「不该停的停了」：
     * 1. `mediaItemCount == 0`：退出播放页时 PlayerLifecycleEffects 的 onDispose
     *    会调 `viewModel.stop()`，那里做了 `stop() + clearMediaItems()`，播放器
     *    被清空但 `playWhenReady` 仍为 true。只看 playWhenReady 会永远等下去。
     * 2. `playWhenReady == true`：用户还想播（含缓冲、切集间隙），不能停。
     * 3. [PlaybackUiState] 还挂着：播放页在前台、用户只是按了暂停，停了会让他
     *    按播放没反应。
     */
    private fun scheduleIdleStop() {
        if (idleJob?.isActive == true) return
        idleJob = serviceScope.launch {
            while (true) {
                delay(IDLE_STOP_DELAY_MS)
                val player = mediaSession?.player

                // 播放器已被清空：退出播放页时 stop() + clearMediaItems() 的结果，
                // 明确没有内容要播了，不必再等 UI
                if (player == null || player.mediaItemCount == 0) {
                    Log.i(TAG, "播放器已清空，停止 Service 释放播放器")
                    stopProgressSaver(save = true)
                    stopSelf()
                    return@launch
                }

                // 用户还想播（缓冲中、切集间隙都算），不需要停
                if (player.playWhenReady) return@launch

                // 播放页仍在前台（用户可能只是按了暂停），继续等，不释放
                if (PlaybackUiState.isUiAttached()) continue

                Log.i(TAG, "空闲 ${IDLE_STOP_DELAY_MS}ms 且播放页已退出，停止 Service 释放播放器")
                stopProgressSaver(save = true)
                stopSelf()
                return@launch
            }
        }
    }

    private fun cancelIdleStop() {
        idleJob?.cancel()
        idleJob = null
    }

    /**
     * 把当前进度写进数据库。
     *
     * **必须跑在主线程**：ExoPlayer 只允许创建它的线程（主线程）访问，
     * 在 IO 线程读 player.currentPosition 会抛
     * `IllegalStateException: Player is accessed on the wrong thread`，
     * 而且是 FATAL——整个进程直接挂掉。所以这里读播放器在主线程，
     * 只把"写库"这一句切到 [dbScope]。
     *
     * videoId 从 MediaItem.mediaId 读——ViewModel 构造 MediaItem 时会写入。
     * 读不到就跳过，不猜。
     *
     * 播到 95% 之后存 0：下次进来从头开始，而不是卡在最后几秒。
     */
    private fun saveProgress(force: Boolean) {
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0) return

        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < SAVE_INTERVAL_MS) return
        lastSavedAt = now

        // ---- 以下三行必须在主线程读 ----
        val videoId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val position = player.currentPosition
        val duration = player.duration
        val toSave = if (duration > 0 && position >= duration * END_RATIO) 0L else position

        dbScope.launch {
            runCatching { repository.updatePlaybackPosition(videoId, toSave) }
                .onFailure { Log.w(TAG, "保存进度失败: videoId=$videoId", it) }
        }
    }

    private companion object {
        const val TAG = "PlaybackService"

        /** 进度落库间隔 */
        const val SAVE_INTERVAL_MS = 5_000L

        /** 超过片长这个比例视为看完，下次从头 */
        const val END_RATIO = 0.95

        /**
         * 停播多久后自停 Service。
         *
         * 选 30 秒的依据：覆盖"暂停一下去回微信再回来"这类短暂离开（实测
         * 切走再切回一般在 10 秒内），又不会让一次正常退出后的 25MB 内存
         * 白白占太久。比这更短会频繁重建 ExoPlayer，更长则省不下内存。
         */
        const val IDLE_STOP_DELAY_MS = 30_000L
    }
}
