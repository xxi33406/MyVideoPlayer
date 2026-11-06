package com.example.myvideoplayer.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myvideoplayer.data.entity.PlaylistEntity
import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.data.repository.VideoRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = VideoRepository(application)

    val allVideos: StateFlow<List<VideoEntity>> = repo.getAllVideos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists: StateFlow<List<PlaylistEntity>> = repo.getAllPlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false); val isScanning = _isScanning.asStateFlow()
    private val _scanResult = MutableSharedFlow<ScanResult>(); val scanResult = _scanResult.asSharedFlow()
    private val _selectedVideos = MutableStateFlow<Set<Long>>(emptySet()); val selectedVideos = _selectedVideos.asStateFlow()
    private val _playingVideos = MutableStateFlow<List<VideoEntity>>(emptyList()); val playingVideos = _playingVideos.asStateFlow()

    /** UI 提示消息 */
    private val _uiMessage = MutableSharedFlow<String>(); val uiMessage = _uiMessage.asSharedFlow()

    /** 根文件夹名 -> SAF URI 映射，用于单文件夹刷新 */
    private val _folderUris = mutableMapOf<String, Uri>()

    fun scanFolder(uri: Uri) = viewModelScope.launch {
        _isScanning.value = true
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val (rootName, cnt) = repo.scanAndSaveFolder(getApplication(), uri)
            _folderUris[rootName] = uri
            _scanResult.emit(ScanResult.Success(cnt))
        } catch (e: Exception) { _scanResult.emit(ScanResult.Error(e.message ?: "失败")) }
        finally { _isScanning.value = false }
    }

    /** 用已授权 URI 重新扫描单个文件夹（不会弹出 SAF 选择器） */
    fun rescanFolder(name: String) {
        _folderUris[name]?.let { scanFolder(it) }
    }

    fun createPlaylist(name: String, cb: (Boolean) -> Unit = {}) = viewModelScope.launch { try { repo.createPlaylist(name); cb(true) } catch (_: Exception) { cb(false) } }
    fun renamePlaylist(id: Long, n: String) = viewModelScope.launch { repo.renamePlaylist(id, n) }
    fun deletePlaylist(pl: PlaylistEntity) = viewModelScope.launch { repo.deletePlaylist(pl) }
    fun deleteVideoFromLocal(v: VideoEntity) = viewModelScope.launch { repo.deleteVideoFromLocal(getApplication(), v) }
    fun addVideosToPlaylist(ids: List<Long>, pid: Long, playlistName: String = "") = viewModelScope.launch {
        repo.addVideosToPlaylist(ids, pid)
        _selectedVideos.value = emptySet()
        if (ids.isNotEmpty()) {
            _uiMessage.emit("已添加 ${ids.size} 个视频至「${playlistName}」")
        }
    }
    fun removeVideoFromPlaylist(vid: Long, pid: Long) = viewModelScope.launch { repo.removeVideoFromPlaylist(vid, pid) }

    /** 获取指定播放列表的视频 Flow（响应式更新） */
    fun getPlaylistVideos(playlistId: Long): Flow<List<VideoEntity>> = repo.getVideosByPlaylist(playlistId)

    /** 一次性获取指定播放列表视频（用于比对等场景） */
    suspend fun getVideosByPlaylistOnce(playlistId: Long): List<VideoEntity> {
        var result = emptyList<VideoEntity>()
        val r = withTimeoutOrNull(3000L) {
            repo.getVideosByPlaylist(playlistId).first()
        }
        return r ?: emptyList()
    }

    /** 获取包含指定视频的播放列表ID集合（用于过滤已添加的列表） */
    fun getExistingPlaylistIds(videoIds: List<Long>, cb: (Set<Long>) -> Unit) = viewModelScope.launch {
        cb(repo.getPlaylistIdsContainingVideos(videoIds))
    }

    /** 同步版（在 LaunchedEffect 中调用） */
    suspend fun getExistingPlaylistIdsSync(videoIds: List<Long>): Set<Long> =
        repo.getPlaylistIdsContainingVideos(videoIds)

    fun setPlayingVideos(v: List<VideoEntity>) { _playingVideos.value = v }
    fun toggleSelection(id: Long) { _selectedVideos.update { c -> if (c.contains(id)) c - id else c + id } }
    fun clearSelection() { _selectedVideos.value = emptySet() }
}

sealed class ScanResult {
    data class Success(val count: Int) : ScanResult()
    data class Error(val msg: String) : ScanResult()
}
