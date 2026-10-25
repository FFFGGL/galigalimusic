package com.galigalimusic.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

@Database(
    entities = [
        HistoryEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        QueueEntity::class,
        SubscriptionEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistSongDao(): PlaylistSongDao
    abstract fun queueDao(): QueueDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        /** v4→v5：play_history 增加 listenedSec（播放统计） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN listenedSec INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5→v6：play_history 增加 resumePositionMs（续播进度） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN resumePositionMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6→v7：新增 play_queue 表（播放队列） */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS play_queue (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "position INTEGER NOT NULL, " +
                        "bvid TEXT NOT NULL, " +
                        "cid INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "author TEXT NOT NULL, " +
                        "coverUrl TEXT NOT NULL, " +
                        "durationSec INTEGER NOT NULL)"
                )
            }
        }

        /** v7→v8：新增 subscriptions 表（追更订阅） */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS subscriptions (" +
                        "bvid TEXT NOT NULL PRIMARY KEY, " +
                        "title TEXT NOT NULL, " +
                        "author TEXT NOT NULL, " +
                        "coverUrl TEXT NOT NULL, " +
                        "pageCount INTEGER NOT NULL, " +
                        "notifiedCount INTEGER NOT NULL, " +
                        "subscribedAt INTEGER NOT NULL, " +
                        "lastCheckedAt INTEGER NOT NULL)"
                )
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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
                    .build().also { INSTANCE = it }
            }
    }
}
