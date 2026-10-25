package com.galigalimusic.data.repository

import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.QueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * 播放队列仓储：「稍后播放」追加队尾，「下一首播放」插队首。
 * skipNext / 自然播完时由 PlayerViewModel 弹队首播放。
 */
class QueueRepository(private val db: AppDatabase) {

    fun observeQueue(): Flow<List<QueueEntity>> = db.queueDao().observeAll()

    suspend fun getAll(): List<QueueEntity> = db.queueDao().getAll()

    /** 追加到队尾（稍后播放） */
    suspend fun append(item: QueueEntity) {
        val pos = db.queueDao().maxPosition() + 1
        db.queueDao().insertAll(listOf(item.copy(position = pos)))
    }

    /** 插入队首（下一首播放） */
    suspend fun prepend(item: QueueEntity) {
        val all = db.queueDao().getAll()
        // 整体后移一位，新项占 position 0
        db.queueDao().insertAll(all.mapIndexed { i, e -> e.copy(position = i + 1) })
        db.queueDao().insertAll(listOf(item.copy(position = 0)))
    }

    suspend fun remove(id: Long) = db.queueDao().deleteById(id)

    suspend fun clear() = db.queueDao().clear()

    /** 上移/下移一位并重排 position */
    suspend fun move(id: Long, delta: Int) {
        val all = db.queueDao().getAll()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, all.lastIndex)
        if (target == idx) return
        val moved = all.toMutableList()
        val item = moved.removeAt(idx)
        moved.add(target, item)
        db.queueDao().insertAll(moved.mapIndexed { i, e -> e.copy(position = i) })
    }
}
