package com.galigalimusic.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.galigalimusic.MainActivity

/**
 * 前台播放服务：持有 ExoPlayer + MediaSession，提供后台播放与系统媒体通知。
 * UI 通过 LocalBinder 直接拿到播放器实例进行控制（同进程，简单可靠）。
 */
class MusicService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val volumeBoost = VolumeBoostProcessor(1.0f)

    inner class LocalBinder : Binder() {
        fun getPlayer(): ExoPlayer = getOrCreatePlayer()
        fun getService(): MusicService = this@MusicService
    }

    companion object {
        const val ACTION_LOCAL = "com.galigalimusic.LOCAL_BIND"
    }

    private val binder = LocalBinder()

    private fun getOrCreatePlayer(): ExoPlayer {
        if (player == null) {
            // 音效处理器经 RenderersFactory→AudioSink 注入（ExoPlayer.Builder 无 setAudioProcessors）
            val renderersFactory = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(volumeBoost))
                        .build()
            }
            // B 站 upos CDN 校验 Referer（缺失 → 403）；跨协议重定向：CDN 常 302 到真实节点
            val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(
                    mapOf(
                        "Referer" to "https://www.bilibili.com/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
                    )
                )
                .setAllowCrossProtocolRedirects(true)
            // AList WebDAV 需要逐请求 Basic 认证：在数据源层按 host 动态注入（设置随时可改）。
            // 底座用 DefaultDataSource（content:// 本地文件也走这里，http 才落到 httpFactory）
            val routedFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
            val authedHttpFactory = androidx.media3.datasource.DataSource.Factory {
                androidx.media3.datasource.ResolvingDataSource(
                    routedFactory.createDataSource(),
                    androidx.media3.datasource.ResolvingDataSource.Resolver { dataSpec ->
                        val host = dataSpec.uri.host ?: return@Resolver dataSpec
                        val alistHost = runCatching {
                            java.net.URI(com.galigalimusic.data.remote.ExternalSources.getUrl(this@MusicService)).host
                        }.getOrNull()
                        if (host != alistHost || alistHost == null) return@Resolver dataSpec
                        val auth = com.galigalimusic.data.remote.ExternalSources.authHeaders(this@MusicService)
                        if (auth.isEmpty()) return@Resolver dataSpec
                        dataSpec.buildUpon()
                            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + auth)
                            .build()
                    },
                )
            }
            // 播放内容落盘缓存：本地识别直接读缓存不重复下载；缓存键 = bvid|cid（与直链无关）
            val dataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(MediaCache.get(applicationContext))
                .setUpstreamDataSourceFactory(authedHttpFactory)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val mediaSourceFactory =
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            player = ExoPlayer.Builder(this, renderersFactory, mediaSourceFactory).build()
            player?.playWhenReady = true
        }
        return player!!
    }

    fun setGain(g: Float) {
        volumeBoost.setGain(g)
    }

    /** 供 UI/VM 直读播放器状态（同进程，简单可靠） */
    fun currentPlayer(): ExoPlayer = getOrCreatePlayer()

    /** 单曲循环（列表循环/随机的播完切集逻辑由 VM 的播完检测处理） */
    fun setRepeatOne(on: Boolean) {
        getOrCreatePlayer().repeatMode =
            if (on) androidx.media3.common.Player.REPEAT_MODE_ONE
            else androidx.media3.common.Player.REPEAT_MODE_OFF
    }

    /** 倍速播放（变速不变调） */
    fun setSpeed(speed: Float) {
        getOrCreatePlayer().setPlaybackSpeed(speed)
    }

    override fun onCreate() {
        super.onCreate()
        val player = getOrCreatePlayer()
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    fun playUrl(url: String, title: String, author: String, coverUrl: String? = null, cacheKey: String? = null) {
        val player = getOrCreatePlayer()
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(author)
            .build()
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(metadata)
            .apply { if (cacheKey != null) setCustomCacheKey(cacheKey) }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun togglePlay() {
        val player = getOrCreatePlayer()
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) = getOrCreatePlayer().seekTo(positionMs)

    fun isPlaying() = getOrCreatePlayer().isPlaying

    fun currentPosition() = getOrCreatePlayer().currentPosition

    fun duration() = getOrCreatePlayer().duration

    override fun onBind(intent: Intent?): android.os.IBinder? {
        // 应用内本地绑定走自定义 action；系统媒体会话（通知/锁屏）交由父类处理
        if (intent?.action == ACTION_LOCAL) return binder
        return super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
