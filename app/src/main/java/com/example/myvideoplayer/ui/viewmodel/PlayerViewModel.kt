package com.example.myvideoplayer.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.myvideoplayer.AppContainer
import com.example.myvideoplayer.data.entity.VideoEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 播放器 ViewModel
 * 管理播放状态、速度控制、循环模式
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "PlayerViewModel"

        /** 短于这个时长的进度不值得恢复，从头播即可 */
        const val MIN_RESUME_MS = 5_000L

        /** 超过片长这个比例视为已看完，下次进入从头开始 */
        const val RESUME_END_RATIO = 0.95

        /** 进度落库的最小间隔，避免播放时每 500ms 写一次数据库 */
        const val SAVE_INTERVAL_MS = 5_000L
    }

    private val repository = AppContainer.of(application).videoRepository

    /** 上次把进度写进数据库的时间戳，用于 [SAVE_INTERVAL_MS] 限频 */
    private var lastSavedAt = 0L

    /** 暂停是否由"切到后台"触发；只有这种暂停才在回前台时自动恢复 */
    private var pausedByBackground = false

    /** ExoPlayer 实例 */
    var exoPlayer: ExoPlayer? = null
        private set

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
        initPlayer()
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        val app = getApplication<Application>()
        val dataSourceFactory = DefaultDataSource.Factory(app)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 解码器兜底：默认关闭。遇到不支持的编码时 ExoPlayer 会直接抛
        // DecoderInitializationException，而不是尝试别的解码器（含软解）。
        // 打开的代价是极端情况下多一次初始化，换来的是少一次"这个视频打不开"。
        val renderersFactory = DefaultRenderersFactory(app)
            .setEnableDecoderFallback(true)

        exoPlayer = ExoPlayer.Builder(app, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                // 播放期间需要持有 CPU 唤醒锁。不设的话系统在息屏/低电时可以挂起进程，
                // 表现为音频断续、画面卡住然后被反复唤醒重试——比正常播放更费电。
                setWakeMode(C.WAKE_MODE_NETWORK)
                // 拔耳机/断蓝牙时自动暂停。原来会继续用扬声器外放，既扰人又费电。
                setHandleAudioBecomingNoisy(true)

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) handlePlaybackEnded()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        // 打日志：此前只写 StateFlow，排查时 logcat 里什么都看不到
                        Log.e(TAG, "播放失败: ${error.errorCodeName} - ${error.message}", error)
                        _playerError.value = error.localizedMessage ?: "播放出错"
                    }
                })
                playWhenReady = true
            }
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
        val player = exoPlayer ?: return
        val videos = _playlist.value
        if (videos.isEmpty()) return

        val index = _currentIndex.value.coerceIn(0, videos.lastIndex)
        val video = videos[index]
        // 切换视频时先清掉上一个视频残留的错误提示
        _playerError.value = null
        viewModelScope.launch { repository.updateLastPlayedAt(video.id) }
        try {
            Log.i(TAG, "开始播放: ${video.name} (${video.path})")
            val mediaItem = MediaItem.fromUri(Uri.parse(video.path))
            player.setMediaItem(mediaItem)

            // 断点续播。数据库里一直存着 position，但这条链路此前从未接上：
            // repository.updatePlaybackPosition() 全项目零调用，playCurrent() 也不读 position，
            // 所以"上次看到哪"根本不存在。
            // 两条边界：太长（超过 95%）视为已看完，从头开始；太短（不到 5 秒）不值得续。
            val resumeMs = video.position
            val knownDuration = video.duration
            val canResume = resumeMs > MIN_RESUME_MS &&
                (knownDuration <= 0 || resumeMs < knownDuration * RESUME_END_RATIO)
            if (canResume) {
                player.seekTo(resumeMs)
                Log.i(TAG, "断点续播: ${video.name} @ ${resumeMs}ms")
            }

            player.prepare()
            player.play()
        } catch (e: Exception) {
            Log.e(TAG, "无法播放: ${video.name} (${video.path})", e)
            _playerError.value = "无法播放：${video.name}"
        }
    }

    fun playPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /**
     * 切到后台时暂停。
     *
     * 为什么不能只靠 UI 层停轮询：切后台后 Composable 仍在组合中，ExoPlayer
     * 依然全速解码。实测（模拟器 Medium_Phone，切后台 30 秒）：CPU 时间增加
     * 9.17 秒（约 30%），top -H 显示 MediaCodec_loop 11.1% +
     * ExoPlayer:PlaybackThread 7.4% —— 画面看不见，解码器却满负荷。
     *
     * 用 pause() 而非 stop()：保留 MediaItem 与播放进度，切回来自动续上。
     * 同时记一个标记，只有"我们主动暂停的"才在回前台时自动恢复，
     * 用户在后台之前自己按了暂停的，回来后保持暂停。
     */
    fun pauseForBackground() {
        val player = exoPlayer ?: return
        if (!player.isPlaying) return
        pausedByBackground = true
        player.pause()
        Log.i(TAG, "切到后台，暂停解码")
    }

    /** 切回前台时恢复播放，仅当暂停是本类在后台触发的 */
    fun resumeFromBackground() {
        if (!pausedByBackground) return
        pausedByBackground = false
        val player = exoPlayer ?: return
        if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
            player.play()
            Log.i(TAG, "回到前台，恢复播放")
        }
    }

    /** 停止播放，用于退出播放器时调用 */
    fun stop() {
        // 退出播放页时会经 onDispose 调用；若此时 player 已 release（如 Activity 重建、
        // ViewModel 已 onCleared），ExoPlayer 内部会抛 IllegalStateException。
        // 原来没有任何保护，异常会中断 onDispose 后续语句，导致方向复位被跳过。
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        } catch (e: Exception) {
            Log.w(TAG, "stop() 时播放器已不可用，忽略", e)
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
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
        exoPlayer?.setPlaybackSpeed(clampedSpeed)
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
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
            }
            else -> next()
        }
    }

    fun updatePosition() {
        val player = exoPlayer ?: return
        _currentPosition.value = player.currentPosition
        _duration.value = player.duration.coerceAtLeast(0)
        maybeSavePosition()
    }

    /**
     * 把当前进度写回数据库，但限频。
     * 这个方法被 500ms 的进度循环调用，直接写库会变成每秒两次磁盘写；
     * 每 [SAVE_INTERVAL_MS] 落一次盘，对"下次从哪继续"完全够用。
     */
    private fun maybeSavePosition() {
        val player = exoPlayer ?: return
        val video = _playlist.value.getOrNull(_currentIndex.value) ?: return
        val now = System.currentTimeMillis()
        if (now - lastSavedAt < SAVE_INTERVAL_MS) return
        val pos = player.currentPosition
        // 播到片尾时不要留下"接近结尾"的进度，否则下次进来会续在最后几秒
        val total = player.duration
        val toSave = if (total > 0 && pos >= total * RESUME_END_RATIO) 0L else pos
        lastSavedAt = now
        viewModelScope.launch { repository.updatePlaybackPosition(video.id, toSave) }
    }

    /**
     * 立即落盘一次进度。退出播放页、进入后台时调用——
     * 这种情况下等不到下一次 5 秒节流窗口，不写就等于丢掉最后几秒的观看进度。
     */
    fun savePositionNow() {
        val player = exoPlayer ?: return
        val video = _playlist.value.getOrNull(_currentIndex.value) ?: return
        val pos = player.currentPosition
        val total = player.duration
        val toSave = if (total > 0 && pos >= total * RESUME_END_RATIO) 0L else pos
        lastSavedAt = System.currentTimeMillis()
        viewModelScope.launch { repository.updatePlaybackPosition(video.id, toSave) }
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer?.release()
        exoPlayer = null
    }
}

enum class LoopMode {
    PLAYLIST,  // 列表循环
    SINGLE     // 单集循环
}
