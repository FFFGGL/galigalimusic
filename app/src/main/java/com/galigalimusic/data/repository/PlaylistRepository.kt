package com.galigalimusic.data.repository

import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.PlaylistEntity
import com.galigalimusic.data.local.PlaylistSongEntity
import com.galigalimusic.data.remote.BilibiliApi
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class PlaylistRepository(private val db: AppDatabase) {

    fun observePlaylists(): Flow<List<PlaylistEntity>> = db.playlistDao().observeAll()

    fun observeCounts() = db.playlistSongDao().observeCounts()

    fun observeSongs(playlistId: String): Flow<List<PlaylistSongEntity>> =
        db.playlistSongDao().observeSongs(playlistId)

    suspend fun create(title: String): String {
        val id = UUID.randomUUID().toString()
        db.playlistDao().upsert(PlaylistEntity(id = id, title = title.trim()))
        return id
    }

    suspend fun rename(id: String, title: String) = db.playlistDao().rename(id, title.trim())

    suspend fun delete(id: String) {
        db.playlistSongDao().clear(id)
        db.playlistDao().delete(id)
    }

    suspend fun addSong(playlistId: String, song: BilibiliApi.Song, cid: Long = 0L) {
        val pos = db.playlistSongDao().maxPosition(playlistId) + 1
        db.playlistSongDao().upsertAll(
            listOf(
                PlaylistSongEntity(
                    playlistId = playlistId,
                    bvid = song.bvid,
                    cid = cid,
                    title = song.title,
                    author = song.author,
                    coverUrl = song.coverUrl,
                    durationSec = song.durationSec,
                    position = pos,
                )
            )
        )
    }

    suspend fun removeSong(playlistId: String, bvid: String, cid: Long) =
        db.playlistSongDao().remove(playlistId, bvid, cid)

    /**
     * 导入 B 站收藏夹：新建歌单并拉取收藏夹内全部视频。
     * 返回歌单 id 与导入条数。
     */
    suspend fun importFav(mediaId: Long, favTitle: String): Pair<String, Int> {
        val existing = db.playlistDao().getByFavMediaId(mediaId)
        val id = existing?.id ?: create(favTitle)
        val songs = BilibiliApi.getFavVideos(mediaId)
        val known = db.playlistSongDao().getSongs(id).map { it.bvid }.toHashSet()
        val fresh = songs.filter { it.bvid !in known }
        var base = db.playlistSongDao().maxPosition(id) + 1
        if (fresh.isNotEmpty()) {
            db.playlistSongDao().upsertAll(fresh.mapIndexed { i, s ->
                PlaylistSongEntity(
                    playlistId = id, bvid = s.bvid,
                    // 带上接口返回的 cid：否则多P 有声书从歌单点播永远回到 P1
                    cid = s.cid,
                    title = s.title, author = s.author, coverUrl = s.coverUrl,
                    durationSec = s.durationSec, position = base + i,
                )
            })
            base += fresh.size
        }

        // 老数据补全：历史导入的 cid 全是 0，而主键含 cid 不能原地改 →
        // 先记下原 position，删掉 cid=0 的行，再按补好的 cid 以原 position 重插
        runCatching {
            val stale = db.playlistSongDao().getSongs(id).filter { it.cid <= 0L }
            if (stale.isNotEmpty()) {
                val cidByBvid = songs.associate { it.bvid to it.cid }
                val fixed = stale.map { row ->
                    val c = cidByBvid[row.bvid] ?: 0L
                    if (c > 0L) row.copy(cid = c)
                    else row.copy(position = base + row.position) // 查不到 cid 的保序挪到末尾
                }
                db.playlistSongDao().deleteZeroCidRows(id)
                // 补到 cid 的按原 position 插回；查不到的（已失效）本来就该留在原位，一并插回
                db.playlistSongDao().upsertAll(fixed)
            }
        }

        db.playlistDao().markFavSynced(id, mediaId, System.currentTimeMillis())
        return id to fresh.size
    }
}
