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

    @Query("SELECT * FROM play_history WHERE bvid = :bvid LIMIT 1")
    suspend fun getByBvid(bvid: String): HistoryEntity?
}
