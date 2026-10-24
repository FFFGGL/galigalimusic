package com.galigalimusic.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

@Database(
    entities = [HistoryEntity::class, PlaylistEntity::class, PlaylistSongEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistSongDao(): PlaylistSongDao

    companion object {
        /** v4→v5：play_history 增加 listenedSec（播放统计） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN listenedSec INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "galigali.db"
                )
                    // 历史版本（v1 单键历史/v2 复合键/v3 歌单两表）只在开发期存在，
                    // 从这些版本升级允许清一次数据；
                    // **v4 起禁止破坏性迁移**：以后每次 bump version 必须配 Migration
                    // （schema 已导出到 app/schemas/，写迁移前先 diff 新旧 json）。
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
                    .build().also { INSTANCE = it }
            }
    }
}
