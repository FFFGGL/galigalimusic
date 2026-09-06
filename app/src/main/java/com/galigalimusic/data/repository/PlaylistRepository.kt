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
        val base = db.playlistSongDao().maxPosition(id) + 1
        if (fresh.isNotEmpty()) {
            db.playlistSongDao().upsertAll(fresh.mapIndexed { i, s ->
                PlaylistSongEntity(
                    playlistId = id, bvid = s.bvid, cid = 0L,
                    title = s.title, author = s.author, coverUrl = s.coverUrl,
                    durationSec = s.durationSec, position = base + i,
                )
            })
        }
        db.playlistDao().markFavSynced(id, mediaId, System.currentTimeMillis())
        return id to fresh.size
    }
}
