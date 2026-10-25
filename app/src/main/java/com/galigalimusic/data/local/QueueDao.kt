package com.galigalimusic.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Query("SELECT * FROM play_queue ORDER BY position ASC")
    fun observeAll(): Flow<List<QueueEntity>>

    @Query("SELECT * FROM play_queue ORDER BY position ASC")
    suspend fun getAll(): List<QueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueEntity>)

    @Query("DELETE FROM play_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM play_queue")
    suspend fun clear()

    @Query("SELECT COALESCE(MAX(position), -1) FROM play_queue")
    suspend fun maxPosition(): Int
}
