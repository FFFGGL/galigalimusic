package com.galigalimusic.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 追更订阅：以 bvid（多P合集/有声书）为单位。
 *
 * pageCount = 订阅时记录的分P总数；定时任务发现实际分P变多即通知。
 * notifiedCount = 已通知过的新增集数，避免重复打扰。
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val bvid: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val pageCount: Int,
    val notifiedCount: Int = 0,
    val subscribedAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long = 0L,
)
