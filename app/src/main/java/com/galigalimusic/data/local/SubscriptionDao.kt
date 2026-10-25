package com.galigalimusic.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY subscribedAt DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY subscribedAt DESC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE bvid = :bvid LIMIT 1")
    suspend fun getByBvid(bvid: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE bvid = :bvid")
    suspend fun deleteByBvid(bvid: String)

    @Query("SELECT bvid FROM subscriptions")
    fun observeBvidSet(): Flow<List<String>>
}
