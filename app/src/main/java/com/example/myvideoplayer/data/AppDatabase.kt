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
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun videoDao(): VideoDao
    abstract fun playlistVideoDao(): PlaylistVideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "video_player.db"
                )
                    .fallbackToDestructiveMigration()
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
