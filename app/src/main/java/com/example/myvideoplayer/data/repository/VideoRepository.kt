package com.example.myvideoplayer.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.myvideoplayer.data.AppDatabase
import com.example.myvideoplayer.data.entity.PlaylistEntity
import com.example.myvideoplayer.data.entity.PlaylistVideoCrossRef
import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.util.FileScanner
import com.example.myvideoplayer.util.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VideoRepository(context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val playlistDao = db.playlistDao()
    private val videoDao = db.videoDao()
    private val pvDao = db.playlistVideoDao()

    /** 扫描目录并入库，返回 (根文件夹名, 视频数) */
    suspend fun scanAndSaveFolder(context: Context, folderUri: Uri): Pair<String, Int> =
        withContext(Dispatchers.IO) {
            val result = FileScanner.scanFolder(context, folderUri)
            // 扫描阶段顺带取宽高：播放页方向判断要靠它，若等首帧渲染出来再切方向，
            // 横屏视频必然"先竖屏闪一下"。失败的视频按 0 处理，方向回落首帧尺寸。
            val withDims = VideoMetadata.fillDimensions(context, result.videos)
            videoDao.insertVideos(
                withDims.map {
                    VideoEntity(
                        path = it.path, name = it.name, folderName = result.rootName,
                        size = it.size, fileModifiedAt = it.lastModified,
                        width = it.width, height = it.height
                    )
                }
            )
            result.rootName to result.videos.size
        }

    fun getAllVideos(): Flow<List<VideoEntity>> = videoDao.getAllVideos()
    fun getVideosByPlaylist(playlistId: Long): Flow<List<VideoEntity>> = videoDao.getVideosByPlaylist(playlistId)

    // 播放列表
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()
    suspend fun createPlaylist(name: String): Long = playlistDao.insertPlaylist(PlaylistEntity(name = name.trim()))
    suspend fun renamePlaylist(id: Long, n: String) { playlistDao.renamePlaylist(id, n.trim()) }
    suspend fun deletePlaylist(pl: PlaylistEntity) {
        pvDao.removeAllForPlaylist(pl.id)
        playlistDao.deletePlaylist(pl)
    }

    // 多对多
    suspend fun addVideosToPlaylist(videoIds: List<Long>, playlistId: Long) {
        pvDao.addToPlaylist(videoIds.map { PlaylistVideoCrossRef(playlistId, it) })
    }

    suspend fun removeVideoFromPlaylist(videoId: Long, playlistId: Long) {
        pvDao.removeVideoFromPlaylist(videoId, playlistId)
    }

    /** 获取包含任一指定视频的播放列表ID集合 */
    suspend fun getPlaylistIdsContainingVideos(videoIds: List<Long>): Set<Long> =
        pvDao.getPlaylistIdsContainingVideos(videoIds).toSet()

    // 播放位置
    suspend fun updatePlaybackPosition(videoId: Long, pos: Long) {
        videoDao.updatePlaybackPosition(videoId, pos)
    }

    /** 更新最后播放时间（用于排序） */
    suspend fun updateLastPlayedAt(videoId: Long) {
        videoDao.updateLastPlayedAt(videoId, System.currentTimeMillis())
    }

    // 删除本地
    suspend fun deleteVideoFromLocal(context: Context, video: VideoEntity): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(video.path)
                DocumentsContract.deleteDocument(context.contentResolver, uri) ||
                    context.contentResolver.delete(uri, null, null) > 0
            } catch (_: Exception) {
                true
            } finally {
                pvDao.removeVideoFromAllPlaylists(video.id)
                videoDao.deleteVideo(video.id)
            }
        }
}
