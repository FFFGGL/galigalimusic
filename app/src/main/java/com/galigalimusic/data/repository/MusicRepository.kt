package com.galigalimusic.data.repository

import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.HistoryEntity
import com.galigalimusic.data.remote.BilibiliApi

class MusicRepository(private val db: AppDatabase) {

    /** 解析结果：播放直链 + cid（歌词接口需要） */
    data class ResolvedTrack(val url: String, val cid: Long)

    suspend fun search(keyword: String) = BilibiliApi.searchVideos(keyword)

    /**
     * 取指定分P（bvid+cid）的音频直链，并把该分P 写入历史。
     * cid 维度入库 ⇒ 同一 BV 的不同分P 进度互相独立。
     */
    suspend fun resolveAndRecord(
        bvid: String,
        cid: Long,
        title: String,
        author: String,
        coverUrl: String,
        durationSec: Int,
    ): ResolvedTrack {
        val url = BilibiliApi.getAudioUrl(bvid, cid)
        // 保留累计收听时长：upsert 是 REPLACE 语义，直接覆盖会把统计清零
        val prev = db.historyDao().getByKey(bvid, cid)
        db.historyDao().upsert(
            HistoryEntity(
                bvid = bvid,
                cid = cid,
                title = title,
                author = author,
                coverUrl = coverUrl,
                durationSec = durationSec,
                listenedSec = prev?.listenedSec ?: 0L,
            )
        )
        return ResolvedTrack(url, cid)
    }

    fun history() = db.historyDao().observeHistory()

    /** 外部音源（本地/AList）直接写历史，不走 B 站解析 */
    suspend fun upsertHistory(entity: HistoryEntity) = db.historyDao().upsert(entity)

    suspend fun clearHistory() = db.historyDao().clear()

    /** 累加收听秒数（播放心跳定期刷入） */
    suspend fun addListenTime(bvid: String, cid: Long, deltaSec: Long) =
        db.historyDao().addListenTime(bvid, cid, deltaSec, System.currentTimeMillis())

    suspend fun deleteHistoryOne(bvid: String, cid: Long) = db.historyDao().deleteOne(bvid, cid)

    suspend fun getHistoryKey(bvid: String, cid: Long) = db.historyDao().getByKey(bvid, cid)
}
