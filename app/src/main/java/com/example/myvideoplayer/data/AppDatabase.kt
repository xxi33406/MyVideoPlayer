package com.example.myvideoplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myvideoplayer.data.dao.PlaylistDao
import com.example.myvideoplayer.data.dao.PlaylistVideoDao
import com.example.myvideoplayer.data.dao.VideoDao
import com.example.myvideoplayer.data.entity.PlaylistEntity
import com.example.myvideoplayer.data.entity.PlaylistVideoCrossRef
import com.example.myvideoplayer.data.entity.VideoEntity

@Database(
    entities = [PlaylistEntity::class, VideoEntity::class, PlaylistVideoCrossRef::class],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun videoDao(): VideoDao
    abstract fun playlistVideoDao(): PlaylistVideoDao

    companion object {
        private const val DB_NAME = "video_player.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                // 显式登记迁移，不再 fallbackToDestructiveMigration：
                // 后者遇到版本变化会直接删库重建，扫描结果与播放列表全丢。
                .addMigrations(*DatabaseMigrations.ALL)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
