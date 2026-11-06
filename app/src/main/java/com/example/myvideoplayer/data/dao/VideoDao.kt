package com.example.myvideoplayer.data.dao

import androidx.room.*
import com.example.myvideoplayer.data.entity.PlaylistVideoCrossRef
import com.example.myvideoplayer.data.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query("SELECT * FROM videos ORDER BY last_played_at DESC")
    fun getAllVideos(): Flow<List<VideoEntity>>

    @Query("SELECT v.* FROM videos v WHERE v.id NOT IN (SELECT videoId FROM playlist_video_cross_ref)")
    fun getUnassignedVideos(): Flow<List<VideoEntity>>

    @Query("SELECT v.* FROM videos v INNER JOIN playlist_video_cross_ref pv ON v.id = pv.videoId WHERE pv.playlistId = :playlistId ORDER BY v.id ASC")
    fun getVideosByPlaylist(playlistId: Long): Flow<List<VideoEntity>>

    @Query("SELECT playlistId FROM playlist_video_cross_ref WHERE videoId = :videoId")
    suspend fun getPlaylistIdsForVideo(videoId: Long): List<Long>

    @Query("SELECT * FROM videos WHERE path = :path LIMIT 1")
    suspend fun getVideoByPath(path: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideos(videos: List<VideoEntity>)

    @Update
    suspend fun updateVideo(video: VideoEntity)

    @Query("UPDATE videos SET position = :position, last_played_at = :lastPlayedAt WHERE id = :id")
    suspend fun updatePlaybackPosition(id: Long, position: Long, lastPlayedAt: Long = System.currentTimeMillis())

    @Query("UPDATE videos SET last_played_at = :time WHERE id = :id")
    suspend fun updateLastPlayedAt(id: Long, time: Long)

    @Query("DELETE FROM videos WHERE id = :id")
    suspend fun deleteVideo(id: Long)
}

@Dao
interface PlaylistVideoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToPlaylist(refs: List<PlaylistVideoCrossRef>)

    @Query("DELETE FROM playlist_video_cross_ref WHERE videoId = :videoId AND playlistId = :playlistId")
    suspend fun removeVideoFromPlaylist(videoId: Long, playlistId: Long)

    @Query("DELETE FROM playlist_video_cross_ref WHERE videoId = :videoId")
    suspend fun removeVideoFromAllPlaylists(videoId: Long)

    @Query("SELECT videoId FROM playlist_video_cross_ref WHERE playlistId = :playlistId")
    suspend fun getVideoIdsForPlaylist(playlistId: Long): List<Long>

    @Query("DELETE FROM playlist_video_cross_ref WHERE playlistId = :playlistId")
    suspend fun removeAllForPlaylist(playlistId: Long)

    @Query("SELECT DISTINCT playlistId FROM playlist_video_cross_ref WHERE videoId IN (:videoIds)")
    suspend fun getPlaylistIdsContainingVideos(videoIds: List<Long>): List<Long>
}
