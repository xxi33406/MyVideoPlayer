package com.example.myvideoplayer.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.myvideoplayer.AppContainer
import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放器 ViewModel
 *
 * P2-1 之前这里直接 new ExoPlayer，播放器生命周期跟着播放路由走：退出播放页就
 * release，切后台再回来只要 Activity 被回收播放就断。现在播放器搬到
 * [PlaybackService]，这里改成 MediaController —— 只发命令、不持有播放器。
 *
 * 两个直接收益：
 * 1. 切后台能继续播（Service 是前台服务，进程不会被随手杀掉），通知栏和锁屏可控；
 * 2. 原来为了省电只好在切后台时主动暂停（Composable 还在组合里、解码器满负荷，
 *    实测切后台 30 秒烧 9.17 秒 CPU），现在这件事交给 Service 的前台身份解决。
 *
 * 代价是连接是异步的：MediaController 建出来那一刻 player 还没连上，
 * 因此所有"进入页面就要播"的操作都走 [runWhenConnected] 排队。
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "PlayerViewModel"

        /** 短于这个时长的进度不值得恢复，从头播即可 */
        const val MIN_RESUME_MS = 5_000L

        /** 超过片长这个比例视为已看完，下次进入从头开始 */
        const val RESUME_END_RATIO = 0.95
    }

    private val repository = AppContainer.of(application).videoRepository

    /** MediaController，连上之前为 null */
    private var controller: MediaController? = null

    /** 连接中就需要执行的操作，连上后按序执行 */
    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()

    /** 播放器接口，供 PlayerView 绑定。连上之前为 null，PlayerView 会显示黑屏 */
    private val _player = MutableStateFlow<Player?>(null)

    /**
     * 给 UI 用的播放器引用。
     *
     * 必须是 StateFlow 而不是普通 getter：PlayerView 通过 `update` 块绑定它，
     * 而 update 只在重组时执行。用普通 getter 的话，"组合时 controller 还没连上"
     * 这种情况永远不会补绑，画面就一直是黑的。
     */
    val player: StateFlow<Player?> = _player.asStateFlow()

    // 当前播放列表
    private val _playlist = MutableStateFlow<List<VideoEntity>>(emptyList())
    val playlist: StateFlow<List<VideoEntity>> = _playlist.asStateFlow()

    // 当前播放索引
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // 是否正在播放
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // 当前播放位置（毫秒）
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    // 视频总时长
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    // 播放速度
    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // 是否正在3倍速（长按状态）
    private val _isFastForward = MutableStateFlow(false)
    val isFastForward: StateFlow<Boolean> = _isFastForward.asStateFlow()

    // 是否保持倍速（松手不回 1x）。默认关，避免误触后忘了自己在 3 倍速里。
    private val _keepSpeed = MutableStateFlow(false)
    val keepSpeed: StateFlow<Boolean> = _keepSpeed.asStateFlow()

    // 循环模式
    private val _loopMode = MutableStateFlow(LoopMode.PLAYLIST)
    val loopMode: StateFlow<LoopMode> = _loopMode.asStateFlow()

    // 错误状态
    private val _playerError = MutableStateFlow<String?>(null)
    val playerError: StateFlow<String?> = _playerError.asStateFlow()

    init {
        connect()
    }

    /**
     * 连到 [PlaybackService]。
     *
     * 连接成功前 [controller] 为 null，所有播放操作进 [pendingActions] 排队。
     * 用主线程 executor 回调：MediaController 是给 UI 用的，回调本来就在主线程。
     */
    private fun connect() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()

        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    // 先发布给 UI（PlayerView 靠这个补绑），再补做排队的操作：
                    // 排队的操作里可能有 play()，先有画面再出声音比反过来直观。
                    _player.value = c
                    attachListener(c)
                    // 按序补做排队中的操作
                    while (pendingActions.isNotEmpty()) {
                        pendingActions.removeFirst().invoke(c)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "连接播放服务失败", e)
                    _playerError.value = "连接播放服务失败：${e.message}"
                }
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    private fun attachListener(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) handlePlaybackEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 打日志：此前只写 StateFlow，排查时 logcat 里什么都看不到
                Log.e(TAG, "播放失败: ${error.errorCodeName} - ${error.message}", error)
                _playerError.value = error.localizedMessage ?: "播放出错"
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                _playbackSpeed.value = playbackParameters.speed
            }
        })
    }

    /** 连上就立刻执行，没连上就排队 */
    private fun runWhenConnected(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pendingActions.addLast(action)
    }

    // ==================== 播放列表管理 ====================

    fun setPlaylist(videos: List<VideoEntity>, startIndex: Int = 0) {
        _playlist.value = videos
        _currentIndex.value = startIndex
        playCurrent()
    }

    fun addToQueue(video: VideoEntity) {
        _playlist.update { it + video }
    }

    /** 播放指定索引的视频 */
    fun playVideoAt(index: Int) {
        val size = _playlist.value.size
        if (size == 0 || index < 0 || index >= size) return
        _currentIndex.value = index
        playCurrent()
    }

    // ==================== 播放控制 ====================

    fun playCurrent() {
        val videos = _playlist.value
        if (videos.isEmpty()) return

        val index = _currentIndex.value.coerceIn(0, videos.lastIndex)
        val video = videos[index]
        // 切换视频时先清掉上一个视频残留的错误提示
        _playerError.value = null
        viewModelScope.launch { repository.updateLastPlayedAt(video.id) }

        runWhenConnected { c ->
            try {
                Log.i(TAG, "开始播放: ${video.name} (${video.path})")
                // mediaId 存主键：进度落库在 Service 侧做，那边要靠它认视频。
                // mediaTitle 给通知栏和锁屏用。
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(video.path))
                    .setMediaId(video.id.toString())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(video.name)
                            .build()
                    )
                    .build()
                c.setMediaItem(mediaItem)

                // 断点续播。数据库里一直存着 position，但这条链路此前从未接上：
                // repository.updatePlaybackPosition() 全项目零调用，playCurrent() 也不读 position，
                // 所以"上次看到哪"根本不存在。
                // 两条边界：太长（超过 95%）视为已看完，从头开始；太短（不到 5 秒）不值得续。
                val resumeMs = video.position
                val knownDuration = video.duration
                val canResume = resumeMs > MIN_RESUME_MS &&
                    (knownDuration <= 0 || resumeMs < knownDuration * RESUME_END_RATIO)
                if (canResume) {
                    c.seekTo(resumeMs)
                    Log.i(TAG, "断点续播: ${video.name} @ ${resumeMs}ms")
                }

                c.prepare()
                c.play()
            } catch (e: Exception) {
                Log.e(TAG, "无法播放: ${video.name} (${video.path})", e)
                _playerError.value = "无法播放：${video.name}"
            }
        }
    }

    fun playPause() {
        runWhenConnected { c ->
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    /**
     * 停止播放，用于退出播放器时调用。
     *
     * 现在只停播放器、不释放实例——实例归 Service，退出播放页只是"不再看画面"。
     * 若此时 UI 层还持有 controller，[onCleared] 时释放的是 controller，不是播放器。
     */
    fun stop() {
        try {
            runWhenConnected { c ->
                c.stop()
                c.clearMediaItems()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop() 时播放器已不可用，忽略", e)
        }
    }

    fun seekTo(positionMs: Long) {
        runWhenConnected { it.seekTo(positionMs) }
    }

    fun next() {
        val size = _playlist.value.size
        if (size == 0) return
        _currentIndex.update { (it + 1) % size }
        playCurrent()
    }

    fun previous() {
        val size = _playlist.value.size
        if (size == 0) return
        _currentIndex.update { if (it > 0) it - 1 else size - 1 }
        playCurrent()
    }

    // ==================== 速度控制 ====================

    /** 切换循环模式 */
    fun toggleLoopMode() {
        _loopMode.update {
            when (it) {
                LoopMode.PLAYLIST -> LoopMode.SINGLE
                LoopMode.SINGLE -> LoopMode.PLAYLIST
            }
        }
    }

    /** 清除错误状态（用户点"知道了"时调用） */
    fun clearError() {
        _playerError.value = null
    }

    /** 设置任意播放速度 */
    fun setSpeed(speed: Float) {
        val clampedSpeed = speed.coerceIn(0.5f, 5f)
        _playbackSpeed.value = clampedSpeed
        runWhenConnected { it.setPlaybackSpeed(clampedSpeed) }
    }

    /** 长按开始3倍速（兼容旧操作） */
    fun startFastForward() {
        // 长按手势原先直接调 setSpeed(3f)，从不走这里，导致 _isFastForward 恒为 false。
        // 而播放页的"3 秒自动隐藏控制条"逻辑依赖它判断"是否正在长按"，
        // 于是控制条会在长按调速的过程中自己消失。现在统一从这里进。
        _isFastForward.value = true
        setSpeed(3f)
    }

    /** 松开恢复原速（除非开了「保持倍速」） */
    fun stopFastForward() {
        _isFastForward.value = false
        if (!_keepSpeed.value) setSpeed(1f)
    }

    /** 「保持倍速」开关：开着时松手不回 1x，倍速一直生效到下次长按或手动复位 */
    fun setKeepSpeed(on: Boolean) {
        _keepSpeed.value = on
    }

    /** 手动把倍速复位到 1x */
    fun resetSpeed() {
        _keepSpeed.value = false
        setSpeed(1f)
    }

    // ==================== 内部处理 ====================

    private fun handlePlaybackEnded() {
        // 播放列表只有一个视频时，PLAYLIST 模式走 next() 会 (0+1)%1 得到 0，
        // 也就是立刻重播同一个视频，配合 playWhenReady 形成无限循环：视频反复从头播，
        // 用户看到的是"播完又跳回开头、进度条永远在 00:0x"，很像播放卡死。
        // 单视频时按单曲循环处理更合理。
        val size = _playlist.value.size
        when {
            _loopMode.value == LoopMode.SINGLE || size <= 1 -> {
                runWhenConnected { c ->
                    c.seekTo(0)
                    c.play()
                }
            }
            else -> next()
        }
    }

    /**
     * 刷新进度快照，供 UI 轮询调用。
     *
     * 进度落库已经搬到 [PlaybackService]，这里只更新给界面看的两个值。
     */
    fun updatePosition() {
        val c = controller ?: return
        _currentPosition.value = c.currentPosition
        _duration.value = c.duration.coerceAtLeast(0)
    }

    // ==================== 前后台 ====================

    /** 是不是"我们主动因为切后台而暂停的"，用来区分用户自己按的暂停 */
    private var pausedByBackground = false

    /**
     * 切到后台时暂停解码。
     *
     * P2-1 之后播放器在 Service 里，技术上切后台完全可以继续播。这里仍然选择暂停，
     * 原因：这是本地视频播放器，切后台时用户看不见画面，继续解码只换来发热和耗电
     * ——正是用户最初提的那个问题。Service 的价值在这里体现为"进程不被随手杀掉、
     * 通知栏与锁屏可控、回到前台无缝接上"，而不是"故意在后台烧电"。
     *
     * 用 pause 而不是 stop：保留 MediaItem 与进度，回前台直接 play 就能接上。
     */
    fun pauseForBackground() {
        val c = controller ?: return
        if (!c.isPlaying) return
        pausedByBackground = true
        c.pause()
        Log.i(TAG, "切到后台，暂停解码")
    }

    /** 回到前台恢复播放——只恢复我们自己暂停的那次 */
    fun resumeFromBackground() {
        if (!pausedByBackground) return
        pausedByBackground = false
        val c = controller ?: return
        if (c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING) {
            c.play()
            Log.i(TAG, "回到前台，恢复播放")
        }
    }

    /**
     * 立即落盘一次进度。
     *
     * Service 那边有自己的 5 秒节流，退出播放页时等不到那个窗口，
     * 这里补一次：把 controller 上的当前位置直接写库。
     */
    fun savePositionNow() {
        val c = controller ?: return
        val video = _playlist.value.getOrNull(_currentIndex.value) ?: return
        val pos = c.currentPosition
        val total = c.duration
        val toSave = if (total > 0 && pos >= total * RESUME_END_RATIO) 0L else pos
        viewModelScope.launch { repository.updatePlaybackPosition(video.id, toSave) }
    }

    override fun onCleared() {
        super.onCleared()
        pendingActions.clear()
        // 只释放 controller（断开与本 UI 的连接），播放器本体归 Service。
        // 这样退出播放页不会打断正在后台继续的播放。
        _player.value = null
        controller?.release()
        controller = null
    }
}

enum class LoopMode {
    PLAYLIST,  // 列表循环
    SINGLE     // 单集循环
}
