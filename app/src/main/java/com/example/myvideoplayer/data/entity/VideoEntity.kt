package com.example.myvideoplayer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 视频实体
 * path 设为唯一索引，避免重复扫描
 */
@Entity(
    tableName = "videos",
    indices = [Index(value = ["path"], unique = true)]
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "path")
    val path: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "duration")
    val duration: Long = 0,

    @ColumnInfo(name = "position")
    val position: Long = 0,

    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long = 0,

    /** 所属根文件夹名（用于分组 + 刷新定位） */
    @ColumnInfo(name = "folder_name")
    val folderName: String = "",

    /** 文件大小（字节），用于排序 */
    @ColumnInfo(name = "size")
    val size: Long = 0
)
