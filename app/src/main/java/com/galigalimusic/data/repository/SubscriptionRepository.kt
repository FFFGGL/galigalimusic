package com.galigalimusic.data.repository

import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.SubscriptionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 追更订阅仓储：订阅/退订/集数快照。
 * 网络检查与通知在 UpdateCheckWorker 里做，这里只落库。
 */
class SubscriptionRepository(private val db: AppDatabase) {

    private val dao = db.subscriptionDao()

    val subscriptions: Flow<List<SubscriptionEntity>> = dao.observeAll()

    /** 已订阅的 bvid 集合（播放页图标高亮用） */
    val subscribedBvids: Flow<Set<String>> =
        dao.observeBvidSet().map { it.toSet() }

    suspend fun subscribe(
        bvid: String,
        title: String,
        author: String,
        coverUrl: String,
        pageCount: Int,
    ) {
        if (pageCount <= 0) return
        val existing = dao.getByBvid(bvid)
        dao.upsert(
            SubscriptionEntity(
                bvid = bvid,
                title = title,
                author = author,
                coverUrl = coverUrl,
                pageCount = pageCount,
                // 重复订阅不重置已通知数
                notifiedCount = existing?.notifiedCount ?: 0,
                subscribedAt = existing?.subscribedAt ?: System.currentTimeMillis(),
            )
        )
    }

    suspend fun unsubscribe(bvid: String) = dao.deleteByBvid(bvid)

    suspend fun all(): List<SubscriptionEntity> = dao.getAll()

    /** 检查完成后回写集数与通知进度 */
    suspend fun markChecked(bvid: String, pageCount: Int, notifiedCount: Int) {
        val cur = dao.getByBvid(bvid) ?: return
        dao.upsert(
            cur.copy(
                pageCount = pageCount,
                notifiedCount = notifiedCount,
                lastCheckedAt = System.currentTimeMillis(),
            )
        )
    }
}
