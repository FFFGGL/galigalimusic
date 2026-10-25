package com.galigalimusic.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY lastPlayed DESC LIMIT 100")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Query("DELETE FROM play_history")
    suspend fun clear()

    @Query("DELETE FROM play_history WHERE bvid = :bvid AND cid = :cid")
    suspend fun deleteOne(bvid: String, cid: Long)

    // ===== 播放统计 =====
    @Query("UPDATE play_history SET listenedSec = listenedSec + :deltaSec, lastPlayed = :now WHERE bvid = :bvid AND cid = :cid")
    suspend fun addListenTime(bvid: String, cid: Long, deltaSec: Long, now: Long)

    /** 续播位置（不刷新 lastPlayed：仅进度心跳调用，避免历史排序被刷乱） */
    @Query("UPDATE play_history SET resumePositionMs = :positionMs WHERE bvid = :bvid AND cid = :cid")
    suspend fun saveResumePosition(bvid: String, cid: Long, positionMs: Long)

    /** 播完/手动重听：续播位置归零 */
    @Query("UPDATE play_history SET resumePositionMs = 0 WHERE bvid = :bvid AND cid = :cid")
    suspend fun clearResumePosition(bvid: String, cid: Long)

    @Query("SELECT COALESCE(SUM(listenedSec), 0) FROM play_history")
    fun observeTotalListened(): Flow<Long>

    @Query("SELECT COALESCE(SUM(listenedSec), 0) FROM play_history WHERE lastPlayed >= :since")
    fun observeListenedSince(since: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM play_history")
    fun observeTrackCount(): Flow<Int>

    @Query("SELECT * FROM play_history ORDER BY listenedSec DESC, lastPlayed DESC LIMIT 5")
    fun observeTopTracks(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM play_history WHERE bvid = :bvid LIMIT 1")
    suspend fun getByBvid(bvid: String): HistoryEntity?

    @Query("SELECT * FROM play_history WHERE bvid = :bvid AND cid = :cid LIMIT 1")
    suspend fun getByKey(bvid: String, cid: Long): HistoryEntity?
}
