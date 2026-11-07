package com.example.myvideoplayer.data

import androidx.room.migration.Migration

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

    /**
     * 全部迁移，按顺序传给 [androidx.room.RoomDatabase.Builder.addMigrations]。
     *
     * 目前为空：v4 是启用 schema 导出的基线版本，尚无 v5 及以上。
     * 以后照下面的样子加：
     *
     * ```
     * private val MIGRATION_4_5 = object : Migration(4, 5) {
     *     override fun migrate(db: SupportSQLiteDatabase) {
     *         db.execSQL("ALTER TABLE videos ADD COLUMN subtitle_path TEXT NOT NULL DEFAULT ''")
     *     }
     * }
     * val ALL = arrayOf(MIGRATION_4_5)
     * ```
     */
    val ALL: Array<Migration> = emptyArray()
}
