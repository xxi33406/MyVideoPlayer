package com.example.myvideoplayer.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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
    }

    private val repository = AppContainer.of(application).videoRepository

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

        exoPlayer = ExoPlayer.Builder(app)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
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
        setSpeed(3f)
        _isFastForward.value = true
    }

    /** 松开恢复原速 */
    fun stopFastForward() {
        setSpeed(1f)
        _isFastForward.value = false
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
