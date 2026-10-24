package com.galigalimusic.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

/**
 * 离线下载中枢：DownloadManager 单例 + 已下载/下载中列表的响应式快照。
 *
 * 下载内容进 SimpleCache（键 = bvid|cid），播放用同键自动离线命中；
 * startPlayback 检查完成态即可无网播放（不需要重新解析直链）。
 */
object Downloads {

    private const val ACTION_DIR = "download_actions"
    private const val ACTION_FILE = "actions.bin"
    private const val TRACKER_DIR = "download_tracker"
    private const val RESULT_CHANNEL = "download_results"

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var manager: DownloadManager? = null

    private val _list = MutableStateFlow<List<Download>>(emptyList())
    /** 全部下载条目（含进行中/已完成/已失败） */
    val list: StateFlow<List<Download>> = _list

    private val _lastErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 失败任务的 key → 可读原因；重试/重新开始/完成后自动清除 */
    val lastErrors: StateFlow<Map<String, String>> = _lastErrors

    @Synchronized
    fun get(context: Context): DownloadManager {
        manager?.let { return it }
        val appContext = context.applicationContext
        appCtx = appContext
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.bilibili.com/",
                    "Origin" to "https://www.bilibili.com",
                    "Accept" to "*/*",
                    "Cookie" to com.galigalimusic.data.remote.BiliSession.cookieHeader(),
                    // B 站 upos CDN 封 Android UA（一律 403），必须用桌面 UA；
                    // 与 BilibiliApi/MusicService 保持一致
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
                )
            )
            .setAllowCrossProtocolRedirects(true)
        val upstream = androidx.media3.datasource.DefaultDataSource.Factory(appContext, http)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(MediaCache.get(appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val downloaderFactory = DefaultDownloaderFactory(cacheFactory)
        val m = DownloadManager(
            appContext,
            androidx.media3.exoplayer.offline.DefaultDownloadIndex(
                StandaloneDatabaseProvider(appContext)
            ),
            downloaderFactory,
        ).apply {
            maxParallelDownloads = 2
            minRetryCount = 3
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                    when (download.state) {
                        Download.STATE_FAILED -> {
                            _lastErrors.update { it + (download.request.id to readableError(finalException)) }
                            appCtx?.let { notifyResult(it, download, failed = true) }
                        }
                        Download.STATE_COMPLETED -> {
                            _lastErrors.update { it - download.request.id }
                            appCtx?.let { notifyResult(it, download, failed = false) }
                        }
                        else -> _lastErrors.update { it - download.request.id }
                    }
                    refresh(appContext)
                }
                override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                    _lastErrors.update { it - download.request.id }
                    refresh(appContext)
                }
            })
        }
        manager = m
        refresh(appContext)
        return m
    }

    /** 刷新快照（也可在 Download 状态变化后手动调用） */
    fun refresh(context: Context) {
        val m = manager ?: return
        runCatching {
            val cursor = m.downloadIndex.getDownloads()
            cursor.use {
                val result = mutableListOf<Download>()
                while (it.moveToNext()) result += it.download
                _list.value = result
            }
        }
    }

    fun getDownload(key: String): Download? = _list.value.firstOrNull { it.request.id == key }

    /** 发起下载（progressive 音频 → SimpleCache，键 bvid|cid） */
    fun start(context: Context, bvid: String, cid: Long, title: String, author: String, coverUrl: String, url: String) {
        val key = "$bvid|$cid"
        _lastErrors.update { it - key }
        val meta = org.json.JSONObject().apply {
            put("title", title)
            put("author", author)
            put("cover", coverUrl)
            put("duration", 0)
        }.toString()
        val request = DownloadRequest.Builder(key, android.net.Uri.parse(url))
            .setCustomCacheKey(key)
            .setData(meta.toByteArray(Charsets.UTF_8))
            .setMimeType(androidx.media3.common.MimeTypes.AUDIO_MP4)
            .build()
        androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
            context, DownloadService::class.java, request, false,
        )
    }

    fun remove(context: Context, key: String) {
        androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(
            context, DownloadService::class.java, key, false,
        )
    }

    /** 重试失败任务：按 bvid|cid 重新解析新鲜直链（B 站直链是临时鉴权地址，旧的常已过期），失败兜底用原 URL。 */
    suspend fun retry(context: Context, download: Download) {
        val old = download.request
        _lastErrors.update { it - old.id }
        val fresh = runCatching {
            val (bvid, cid) = parseKey(old.id)
            if (bvid.isEmpty() || cid <= 0L) return@runCatching null
            val url = com.galigalimusic.data.remote.BilibiliApi.getAudioUrl(bvid, cid)
            DownloadRequest.Builder(old.id, android.net.Uri.parse(url))
                .setCustomCacheKey(old.customCacheKey)
                .setData(old.data)
                .setMimeType(old.mimeType)
                .build()
        }.getOrNull() ?: old
        androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
            context, DownloadService::class.java, fresh, false,
        )
    }

    /** 解析键 → (bvid, cid) */
    fun parseKey(key: String): Pair<String, Long> {
        val i = key.lastIndexOf('|')
        if (i <= 0) return key to 0L
        return key.substring(0, i) to (key.substring(i + 1).toLongOrNull() ?: 0L)
    }

    /** 把底层异常翻译成用户能看懂的一句话 */
    private fun readableError(e: Exception?): String = when {
        e == null -> "未知错误"
        e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> when (e.responseCode) {
            403 -> "直链已过期（403），点重试自动重新解析"
            404 -> "资源不存在或已删除（404）"
            else -> "服务返回 HTTP ${e.responseCode}"
        }
        e.message?.contains("Unable to resolve", ignoreCase = true) == true -> "网络不可用"
        e is java.net.SocketTimeoutException -> "网络超时"
        e is java.net.ConnectException -> "网络连接失败"
        else -> e.message?.take(60) ?: e.javaClass.simpleName
    }

    /** 下载结果通知（完成/失败各一条，同任务复用同一 id） */
    private fun notifyResult(context: Context, download: Download, failed: Boolean) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(RESULT_CHANNEL, "下载结果", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val meta = runCatching {
                org.json.JSONObject(String(download.request.data ?: ByteArray(0), Charsets.UTF_8))
            }.getOrNull()
            val title = meta?.optString("title").takeUnless { it.isNullOrEmpty() } ?: "音频"
            val reason = _lastErrors.value[download.request.id]
            val text = if (failed) "$title · $reason" else title
            val notification = androidx.core.app.NotificationCompat.Builder(context, RESULT_CHANNEL)
                // 用应用自己的图标：系统 android.R.drawable 在部分 ROM 上显示成白方块
                .setSmallIcon(
                    if (failed) android.R.drawable.stat_notify_error
                    else com.galigalimusic.R.drawable.ic_stat_download
                )
                .setContentTitle(if (failed) "下载失败" else "下载完成 ✓")
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(download.request.id.hashCode() and 0x7fffffff, notification)
        }
    }
}
