package com.example.myvideoplayer.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移登记处。
 *
 * 规则：每次改动实体（加字段 / 改类型 / 加索引），必须
 * 1. 把 [AppDatabase] 的 version 加一
 * 2. 在这里追加一条 `MIGRATION_旧版本_新版本`
 * 3. 构建一次，确认 `app/schemas/` 下导出了新版本 JSON 并一起提交
 *
 * 没有登记 Migration 就升级版本，App 启动时会直接抛
 * `IllegalStateException: A migration from X to Y was required but not found`。
 */
object DatabaseMigrations {

    /** v4 → v5：videos 表新增 file_modified_at 列（文件修改时间，时间排序用） */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE videos ADD COLUMN file_modified_at INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * 全部迁移，按顺序传给 [androidx.room.RoomDatabase.Builder.addMigrations]。
     *
     * 加新迁移的步骤照 [MIGRATION_4_5] 的样子：
     * 1. 在这里写 `private val MIGRATION_旧_新`
     * 2. 加进下面的 [ALL] 数组
     * 3. 构建一次，确认 `app/schemas/` 导出了新版本 JSON 并一起提交
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_4_5)
}
