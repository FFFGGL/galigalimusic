package com.galigalimusic.player

import android.content.Context
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.Download
import java.io.File

/**
 * 播放媒体缓存（全局唯一实例，SimpleCache 禁止同目录多实例）。
 *
 * 播放过的内容自动落盘：
 * 1. 本地识别直接读缓存，不再重复下载音频（当前这首听过的，字节早就全在本地）；
 * 2. 缓存键 = bvid|cid（与直链无关），URL 过期重解析后同一键继续累积；
 * 3. DownloadService 下载的内容也进同一缓存（键相同），播放自动离线命中；
 * 4. NoOp 驱逐：已下载内容不会被自动清掉，占用管理走设置页「清理缓存」（保留已下载）。
 */
object MediaCache {

    @Volatile
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_cache")
        val db = StandaloneDatabaseProvider(context)
        val c = SimpleCache(dir, NoOpCacheEvictor(), db)
        cache = c
        return c
    }

    /** 播放缓存占用（字节） */
    fun usageBytes(context: Context): Long {
        val c = get(context)
        var total = 0L
        for (key in c.keys) {
            for (span in c.getCachedSpans(key)) total += span.length
        }
        return total
    }

    /** 将缓存中某个 key 的完整字节按 span 顺序导出为文件（供本地识别）。 */
    fun exportToFile(c: SimpleCache, key: String, dest: File): Long {
        val spans = c.getCachedSpans(key).sortedBy { it.position }
        if (spans.isEmpty()) return 0L
        var total = 0L
        dest.outputStream().use { out ->
            for (span in spans) {
                val file = span.file ?: continue
                file.inputStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var remaining = span.length
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                        remaining -= n
                    }
                }
            }
        }
        return total
    }

    /** 已下载（DownloadService 完成态）的键集合 */
    fun completedKeys(context: Context): Set<String> {
        val cursor = Downloads.get(context).downloadIndex.getDownloads()
        return cursor.use {
            val keys = mutableSetOf<String>()
            while (it.moveToNext()) {
                if (it.download.state == Download.STATE_COMPLETED) keys += it.download.request.id
            }
            keys
        }
    }

    /** 清理「未被下载占用」的缓存条目（保留已下载内容），返回清理条数 */
    fun clearNonDownloaded(context: Context): Int {
        val c = get(context)
        val keep = completedKeys(context)
        var removed = 0
        for (key in c.keys.toList()) {
            if (key !in keep) {
                runCatching { c.removeResource(key) }
                removed++
            }
        }
        return removed
    }
}
