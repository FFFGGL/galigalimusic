package com.galigalimusic.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galigalimusic.PlaybackConnection
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.HistoryEntity
import com.galigalimusic.data.remote.BiliSession
import com.galigalimusic.data.remote.BilibiliApi
import com.galigalimusic.data.repository.MusicRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val repo: MusicRepository,
    db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences("playback", Context.MODE_PRIVATE)

    /** 历史标题组合：多P 时附加「· P标签」 */
    private fun composeHistoryTitle(title: String, pageLabel: String): String =
        if (pageLabel.isEmpty()) title else "$title · $pageLabel"

    data class Current(
        val title: String,
        val author: String,
        val coverUrl: String,
        val bvid: String = "",
        val cid: Long = 0L,
        /** 分P 名（多P 视频当前播放的分P；单P 为空） */
        val pageLabel: String = "",
    )

    data class Playback(
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
    )

    private val _current = MutableStateFlow<Current?>(null)
    val current: StateFlow<Current?> = _current

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving

    private val _playback = MutableStateFlow(Playback())
    val playback: StateFlow<Playback> = _playback

    /** 当前视频的分P 列表（单P 视频为 1 项或空） */
    private val _pages = MutableStateFlow<List<BilibiliApi.VideoPage>>(emptyList())
    val pages: StateFlow<List<BilibiliApi.VideoPage>> = _pages

    /** 当前歌词（B 站 AI 字幕优先）；空列表 = 加载中或无字幕 */
    private val _lyrics = MutableStateFlow<List<BilibiliApi.LrcLine>>(emptyList())
    val lyrics: StateFlow<List<BilibiliApi.LrcLine>> = _lyrics

    private val _lyricsState = MutableStateFlow(LyricsState.LOADING)
    val lyricsState: StateFlow<LyricsState> = _lyricsState

    /** 歌词整体偏移（毫秒）：正值=歌词延后，持久化到当前 bvid+cid */
    private val _lyricOffsetMs = MutableStateFlow(0L)
    val lyricOffsetMs: StateFlow<Long> = _lyricOffsetMs

    fun adjustLyricOffset(deltaMs: Long) {
        val cur = _current.value ?: return
        val next = (_lyricOffsetMs.value + deltaMs).coerceIn(-30_000L, 30_000L)
        _lyricOffsetMs.value = next
        prefs.edit().putLong("lyric_offset_${cur.bvid}_${cur.cid}", next).apply()
    }

    fun resetLyricOffset() {
        val cur = _current.value ?: return
        _lyricOffsetMs.value = 0L
        prefs.edit().remove("lyric_offset_${cur.bvid}_${cur.cid}").apply()
    }

    enum class LyricsState { LOADING, NONE, LOADED }

    /** 播放模式：列表循环 / 随机 / 单曲循环 */
    enum class PlayMode { REPEAT_ALL, SHUFFLE, REPEAT_ONE }

    private val _playMode = MutableStateFlow(PlayMode.REPEAT_ALL)
    val playMode: StateFlow<PlayMode> = _playMode

    /** 播放历史（最近优先），作为上一首/下一首与首页「最近播放」的数据源 */
    val history: StateFlow<List<HistoryEntity>> = repo.history()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var volumeBoostOn = false
    private var pagesFetchedFor: String? = null
    private var endedHandled = false

    // ===== 倍速播放 =====
    companion object {
        val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }

    private val _speed = MutableStateFlow(prefs.getFloat("playback_speed", 1.0f))
    val speed: StateFlow<Float> = _speed

    fun cycleSpeed() {
        val idx = SPEEDS.indexOf(_speed.value).coerceAtLeast(0)
        val next = SPEEDS[(idx + 1) % SPEEDS.size]
        _speed.value = next
        prefs.edit().putFloat("playback_speed", next).apply()
        runCatching { PlaybackConnection.service.value?.setSpeed(next) }
    }

    // ===== 定时关闭（睡前听书） =====
    @Volatile
    private var sleepEndAt = 0L

    private val _sleepRemainingSec = MutableStateFlow(-1L)
    val sleepRemainingSec: StateFlow<Long> = _sleepRemainingSec

    fun setSleepTimer(minutes: Int) {
        sleepEndAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepRemainingSec.value = minutes * 60L
    }

    fun clearSleepTimer() {
        sleepEndAt = 0
        _sleepRemainingSec.value = -1
    }

    // ===== 播放失败自愈（URL 过期等 Source error → 重解析续播一次） =====
    private var errorRetryKey: String? = null

    // ===== AB 循环 =====
    private val _abPointA = MutableStateFlow<Long?>(null)
    val abPointA: StateFlow<Long?> = _abPointA
    private val _abPointB = MutableStateFlow<Long?>(null)
    val abPointB: StateFlow<Long?> = _abPointB

    /** AB 三段式：无→设A→设B(进入循环)→清除 */
    fun abStep() {
        val pos = _playback.value.positionMs
        if (_abPointA.value == null) {
            _abPointA.value = pos
            _abPointB.value = null
        } else if (_abPointB.value == null) {
            _abPointB.value = maxOf(pos, _abPointA.value ?: pos)
        } else {
            _abPointA.value = null
            _abPointB.value = null
        }
    }

    // ===== 跳过头尾（全局秒数，持久化；有声书片头曲/片尾曲场景） =====
    private val _skipHeadSec = MutableStateFlow(prefs.getInt("skip_head_sec", 0))
    val skipHeadSec: StateFlow<Int> = _skipHeadSec
    private val _skipTailSec = MutableStateFlow(prefs.getInt("skip_tail_sec", 0))
    val skipTailSec: StateFlow<Int> = _skipTailSec

    fun setSkipHeadTail(headSec: Int, tailSec: Int) {
        headSkipSec = headSec
        tailSkipSec = tailSec
        prefs.edit().putInt("skip_head_sec", headSec).putInt("skip_tail_sec", tailSec).apply()
        _skipHeadSec.value = headSec
        _skipTailSec.value = tailSec
    }

    @Volatile
    private var headSkipSec = prefs.getInt("skip_head_sec", 0)

    @Volatile
    private var tailSkipSec = prefs.getInt("skip_tail_sec", 0)

    init {
        // 进度心跳：500ms 轮询播放器（同进程直读，成本低）；顺带检测「播完」驱动循环/随机切集
        viewModelScope.launch {
            while (isActive) {
                val svc = PlaybackConnection.service.value
                if (svc != null && _current.value != null) {
                    runCatching {
                        val player = svc.currentPlayer()
                        _playback.value = Playback(
                            positionMs = player.currentPosition.coerceAtLeast(0),
                            durationMs = player.duration.coerceAtLeast(0),
                            isPlaying = player.isPlaying,
                        )
                        _isPlaying.value = player.isPlaying
                        // AB 循环：过了 B 点回 A 点（AB 生效时优先于跳尾/切集）
                        val abA = _abPointA.value
                        val abB = _abPointB.value
                        val pos = _playback.value.positionMs
                        if (abA != null && abB != null && pos >= abB) {
                            runCatching { svc.seekTo(abA) }
                        }

                        // 跳过片尾：距结尾 tail 秒即视为播完（AB 循环时不触发）
                        val tailSec = tailSkipSec
                        if ((abA == null || abB == null) && tailSec > 0) {
                            val dur = _playback.value.durationMs
                            if (dur > tailSec * 1000L + 3000L &&
                                pos >= dur - tailSec * 1000L && !endedHandled
                            ) {
                                endedHandled = true
                                onTrackEnded()
                            }
                        }

                        val ended = player.playbackState ==
                            androidx.media3.common.Player.STATE_ENDED
                        if (ended && !endedHandled) {
                            endedHandled = true
                            onTrackEnded()
                        } else if (!ended) {
                            endedHandled = false
                        }

                        // 播放失败自愈：出错进入 IDLE 且此前确有播放进度时，
                        // 重解析当前曲目并跳回原进度续播（每曲目只自愈一次，防死循环）
                        val cur = _current.value
                        if (player.playbackState == androidx.media3.common.Player.STATE_IDLE &&
                            !_resolving.value &&
                            (_playback.value.durationMs > 0 || _playback.value.positionMs > 0)
                        ) {
                            val key = "${cur?.bvid}|${cur?.cid}"
                            if (errorRetryKey != key) {
                                errorRetryKey = key
                                val resumeAt = _playback.value.positionMs
                                _resolving.value = true
                                viewModelScope.launch {
                                    runCatching {
                                        val resolved = repo.resolveAndRecord(
                                            cur!!.bvid, cur.cid, cur.title, cur.author,
                                            cur.coverUrl,
                                            (_playback.value.durationMs / 1000).toInt(),
                                        )
                                        svc.playUrl(resolved.url, cur.title, cur.author, cur.coverUrl, "${cur.bvid}|${cur.cid}")
                                        svc.seekTo(resumeAt)
                                    }
                                    _resolving.value = false
                                }
                            }
                        } else if (player.playbackState ==
                            androidx.media3.common.Player.STATE_READY
                        ) {
                            errorRetryKey = null
                        }
                    }
                } else {
                    _playback.value = Playback(isPlaying = _isPlaying.value)
                }

                // 定时关闭
                if (sleepEndAt > 0) {
                    val remain = (sleepEndAt - System.currentTimeMillis()) / 1000
                    if (remain <= 0) {
                        runCatching {
                            PlaybackConnection.service.value?.currentPlayer()?.pause()
                        }
                        sleepEndAt = 0
                        _sleepRemainingSec.value = -1
                    } else {
                        _sleepRemainingSec.value = remain
                    }
                }
                delay(500)
            }
        }
    }

    // ===== 本地识别（whisper.cpp 端上推理，完全离线） =====
    enum class RecognizeState { IDLE, DOWNLOADING_MODEL, DOWNLOADING_AUDIO, DECODING, TRANSCRIBING, DONE, ERROR }

    private val _recognizeState = MutableStateFlow(RecognizeState.IDLE)
    val recognizeState: StateFlow<RecognizeState> = _recognizeState
    private val _recognizeProgress = MutableStateFlow(0)
    val recognizeProgress: StateFlow<Int> = _recognizeProgress
    private val _recognizeMsg = MutableStateFlow("")
    val recognizeMsg: StateFlow<String> = _recognizeMsg

    private var recognizing = false

    @Volatile
    private var recognizeCancel = false

    fun cancelRecognize() {
        recognizeCancel = true
    }

    fun recognizeLocally() {
        val cur = _current.value ?: return
        if (recognizing || cur.bvid.isEmpty() || cur.cid <= 0) return
        recognizing = true
        _recognizeState.value = RecognizeState.DOWNLOADING_MODEL
        _recognizeProgress.value = 0
        _recognizeMsg.value = ""
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var audioFile: java.io.File? = null
            var wavFile: java.io.File? = null
            val dbg: (String) -> Unit = { com.galigalimusic.data.remote.BiliSession.appendDebug("recognize: $it") }
            try {
                // 1. 模型（tiny，约 78MB，hf-mirror，之后完全离线）
                if (!com.galigalimusic.whisper.WhisperLocal.isModelReady(appContext, com.galigalimusic.whisper.WhisperLocal.TINY)) {
                    com.galigalimusic.whisper.WhisperLocal.downloadModel(
                        appContext, com.galigalimusic.whisper.WhisperLocal.TINY
                    ) { p -> _recognizeProgress.value = p }
                }
                // 2. 音频：优先从播放缓存导出（播放过的字节都在本地，不再重复下载）
                _recognizeState.value = RecognizeState.DOWNLOADING_AUDIO
                _recognizeProgress.value = 0
                val cacheKey = "${cur.bvid}|${cur.cid}"
                val cache = com.galigalimusic.player.MediaCache.get(appContext)
                val cachedBytes = runCatching {
                    cache.getCachedBytes(cacheKey, 0, Long.MAX_VALUE)
                }.getOrDefault(0L)
                BiliSession.appendDebug("recognize: cache bytes=$cachedBytes for $cacheKey")
                audioFile = java.io.File(appContext.cacheDir, "whisper_audio.m4a")
                if (cachedBytes > 512 * 1024) {
                    BiliSession.appendDebug("recognize: export from cache")
                    audioFile.delete()
                    val exported = com.galigalimusic.player.MediaCache.exportToFile(cache, cacheKey, audioFile)
                    BiliSession.appendDebug("recognize: exported $exported bytes")
                    if (exported <= 0) throw IllegalStateException("缓存导出失败")
                } else {
                    // 缓存不足（刚切过来的歌）：重新解析下载，同时回填缓存供下次使用
                    val resolved = repo.resolveAndRecord(
                        cur.bvid, cur.cid, composeHistoryTitle(cur.title, cur.pageLabel),
                        cur.author, cur.coverUrl, 0,
                    )
                    com.galigalimusic.whisper.WhisperLocal.downloadAudio(
                        resolved.url, audioFile
                    ) { p -> _recognizeProgress.value = p }
                }
                // 3. 解码+转写流水线：边解码边出句（每 30s 一段立即上屏），不再先解码整集
                _recognizeState.value = RecognizeState.TRANSCRIBING
                _recognizeProgress.value = 0
                wavFile = java.io.File(appContext.cacheDir, "whisper_in.wav")
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val modelPath = com.galigalimusic.whisper.WhisperLocal
                    .modelFile(appContext, com.galigalimusic.whisper.WhisperLocal.TINY).path
                val ptr = com.galigalimusic.whisper.WhisperNative.initContext(modelPath)
                if (ptr == 0L) throw IllegalStateException("模型加载失败")
                try {
                    val acc = mutableListOf<com.galigalimusic.data.remote.BilibiliApi.LrcLine>()
                    val totalLines = com.galigalimusic.whisper.WhisperAudio.decodeTranscribeStreaming(
                        audioFile!!.absolutePath,
                        wavFile!!,
                        sliceSec = 30,
                        transcribe = { samples ->
                            val json = com.galigalimusic.whisper.WhisperNative
                                .transcribeSamples(ptr, samples, "zh", threads)
                            BiliSession.appendDebug(
                                "whisper slice: jsonLen=${json.length} head=${json.take(120)}"
                            )
                            com.galigalimusic.whisper.WhisperLocal.parseNativeJson(json)
                        },
                        onSlice = { lines, idx, _ ->
                            acc += lines
                            _lyrics.value = acc.toList()
                            _lyricsState.value = LyricsState.LOADED
                            _recognizeProgress.value = idx
                            // 增量落盘：每 3 段存一次，取消也不丢已识别内容
                            if (idx % 3 == 0) {
                                com.galigalimusic.whisper.WhisperLocal.saveLyricCache(appContext, cur.bvid, cur.cid, acc)
                            }
                        },
                        isCancelled = { recognizeCancel },
                    )
                    if (acc.isEmpty() && !recognizeCancel) throw IllegalStateException("未识别出语音内容")
                    if (acc.isNotEmpty()) {
                        com.galigalimusic.whisper.WhisperLocal.saveLyricCache(appContext, cur.bvid, cur.cid, acc)
                        _recognizeState.value = RecognizeState.DONE
                        _recognizeProgress.value = 100
                    } else {
                        _recognizeState.value = RecognizeState.IDLE
                    }
                } finally {
                    com.galigalimusic.whisper.WhisperNative.freeContext(ptr)
                }
            } catch (e: Exception) {
                dbg("FAILED: $e")
                _recognizeState.value = RecognizeState.ERROR
                _recognizeMsg.value = e.message ?: "识别失败"
            } finally {
                audioFile?.delete()
                wavFile?.delete()
                recognizing = false
            }
        }
    }

    /** 播完一首：单曲循环由 ExoPlayer repeatMode 原生处理；其余按模式切集 */
    private fun onTrackEnded() {
        when (_playMode.value) {
            PlayMode.REPEAT_ONE -> Unit
            PlayMode.SHUFFLE -> {
                val pages = _pages.value
                val cur = _current.value
                if (pages.size > 1) {
                    val others = pages.filter { it.cid != cur?.cid }
                    others.randomOrNull()?.let { playPage(it) }
                } else {
                    history.value.randomOrNull()?.let { playFromHistory(it) }
                }
            }
            PlayMode.REPEAT_ALL -> skipPage(+1, wrap = true)
        }
    }

    /** 切换播放模式（循环 → 随机 → 单曲循环），单曲循环走 ExoPlayer 原生 repeatMode */
    fun cyclePlayMode() {
        _playMode.value = when (_playMode.value) {
            PlayMode.REPEAT_ALL -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.REPEAT_ALL
        }
        runCatching {
            PlaybackConnection.service.value
                ?.setRepeatOne(_playMode.value == PlayMode.REPEAT_ONE)
        }
    }

    /** 从歌单曲目播放（cid=0 表示未解析，走默认解析路径） */
    fun playPlaylistTrack(
        bvid: String,
        cid: Long,
        title: String,
        author: String,
        coverUrl: String,
        durationSec: Int,
    ) {
        if (cid > 0) {
            startPlayback(bvid, cid, title, author, coverUrl, durationSec)
        } else {
            play(BilibiliApi.Song(bvid, title, author, coverUrl, durationSec))
        }
    }

    /** 搜索结果/BV 直连：默认播 P1，分P 列表随后可从选集切换 */
    fun play(song: BilibiliApi.Song) {
        viewModelScope.launch {
            _resolving.value = true
            try {
                val cid = BilibiliApi.getCid(song.bvid)
                startPlayback(
                    bvid = song.bvid,
                    cid = cid,
                    title = song.title,
                    author = song.author,
                    coverUrl = song.coverUrl,
                    durationSec = song.durationSec,
                )
            } catch (e: Exception) {
                _current.value = null
                _lyricsState.value = LyricsState.NONE
            } finally {
                _resolving.value = false
            }
        }
    }

    /** 从历史续播：cid 精确还原到当时听的分P；标题形如「视频标题 · P2 F 6-10」，解析还原分P 标签 */
    fun playFromHistory(item: HistoryEntity) {
        // 外部音源（本地文件/AList）在历史里以 ext:<uri> 存 bvid，回放走免解析直连
        if (item.bvid.startsWith("ext:")) {
            playExternal(
                uri = item.bvid.removePrefix("ext:"),
                title = item.title,
                author = item.author,
                coverUrl = item.coverUrl.ifEmpty { null },
                sourceTag = "resume",
            )
            return
        }
        val m = Regex("^(.*) · (P\\d+.*)$").find(item.title)
        val videoTitle = m?.groupValues?.get(1) ?: item.title
        val label = m?.groupValues?.get(2).orEmpty()
        startPlayback(
            bvid = item.bvid,
            cid = item.cid,
            title = videoTitle,
            author = item.author,
            coverUrl = item.coverUrl,
            durationSec = item.durationSec,
            pageLabel = label,
        )
    }

    /**
     * 播放外部音源（本地文件 / AList 网盘）。
     * bvid 以 "ext:<uri>" 形式进历史与当前态，不触发任何 B 站解析；
     * 歌词优先 whisper 缓存，其次 LRCLIB 按标题/作者匹配，失败静默。
     */
    fun playExternal(
        uri: String,
        title: String,
        author: String,
        coverUrl: String?,
        sourceTag: String,
    ) {
        val service = PlaybackConnection.service.value ?: return
        val pseudoBvid = "ext:$uri"
        _current.value = Current(title, author, coverUrl ?: "", pseudoBvid, 0L, "")
        _lyricOffsetMs.value = prefs.getLong("lyric_offset_${pseudoBvid}_0", 0L)
        _lyrics.value = emptyList()
        _lyricsState.value = LyricsState.LOADING
        _pages.value = emptyList()
        _abPointA.value = null
        _abPointB.value = null
        viewModelScope.launch {
            service.playUrl(uri, title, author, coverUrl, null)
            runCatching { service.setSpeed(_speed.value) }
            _isPlaying.value = true
            runCatching {
                repo.upsertHistory(
                    HistoryEntity(
                        bvid = pseudoBvid, cid = 0L, title = title, author = author,
                        coverUrl = coverUrl ?: "", durationSec = 0,
                    )
                )
            }
            runCatching {
                val cached = com.galigalimusic.whisper.WhisperLocal.loadLyricCache(appContext, pseudoBvid, 0L)
                if (cached != null) {
                    _lyrics.value = cached
                    _lyricsState.value = LyricsState.LOADED
                } else {
                    val lines = BilibiliApi.getLrclibLyrics(title, author)
                    _lyrics.value = lines
                    _lyricsState.value = if (lines.isEmpty()) LyricsState.NONE else LyricsState.LOADED
                    if (lines.isNotEmpty()) {
                        com.galigalimusic.whisper.WhisperLocal.saveLyricCache(appContext, pseudoBvid, 0L, lines)
                    }
                }
            }.onFailure { _lyricsState.value = LyricsState.NONE }
        }
    }

    /** 播放指定分P */
    fun playPage(page: BilibiliApi.VideoPage) {
        val cur = _current.value ?: return
        val label = if (_pages.value.size > 1) "P${page.page} ${page.part}" else ""
        startPlayback(
            bvid = cur.bvid,
            cid = page.cid,
            title = cur.title,
            author = cur.author,
            coverUrl = cur.coverUrl,
            durationSec = page.durationSec,
            pageLabel = label,
        )
    }

    /**
     * 统一播放入口：解析直链 → 起播 → 歌词 → 分P 列表。
     * 所有路径（搜索/历史/选集）都汇到这里。
     */
    private fun startPlayback(
        bvid: String,
        cid: Long,
        title: String,
        author: String,
        coverUrl: String,
        durationSec: Int,
        pageLabel: String = "",
    ) {
        val service = PlaybackConnection.service.value ?: return
        _resolving.value = true
        _current.value = Current(title, author, coverUrl, bvid, cid, pageLabel)
        _lyricOffsetMs.value = prefs.getLong("lyric_offset_${bvid}_${cid}", 0L)
        _lyrics.value = emptyList()
        _lyricsState.value = LyricsState.LOADING
        viewModelScope.launch {
            try {
                // 历史标题带分P 标识：93 集的有声书在历史里能看出听到哪集
                val cacheKey = "$bvid|$cid"
                // 完整下载时直接使用下载记录里的 URI，CacheDataSource 全程离线命中，
                // 不再调用 B 站 API（也不需要有效期已过的原始直链）。
                val downloaded = com.galigalimusic.player.Downloads.getDownload(cacheKey)
                val playUrl = if (downloaded?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) {
                    downloaded.request.uri.toString()
                } else {
                    val resolved = repo.resolveAndRecord(
                        bvid, cid, composeHistoryTitle(title, pageLabel), author, coverUrl, durationSec
                    )
                    resolved.url
                }
                // 缓存键稳定（bvid|cid）：直链过期换新 URL 后，同一键继续累积
                service.playUrl(playUrl, title, author, coverUrl, cacheKey)
                // 恢复用户倍速（ExoPlayer 换曲会保留参数，但进程重启后需重设）
                runCatching { service.setSpeed(_speed.value) }
                // 跳过片头
                if (headSkipSec > 0) runCatching { service.seekTo(headSkipSec * 1000L) }
                // 切歌清空 AB 循环（AB 是曲目级状态）
                _abPointA.value = null
                _abPointB.value = null
                _current.value = Current(title, author, coverUrl, bvid, cid, pageLabel)
                _isPlaying.value = true
                // 歌词：文件缓存优先 → B 站 AI 字幕 → 无则由用户触发本地识别。
                // 网络类异常在这里吞掉：播放已经开始了，断网不能把 UI 状态清掉
                runCatching {
                    val cached = com.galigalimusic.whisper.WhisperLocal.loadLyricCache(appContext, bvid, cid)
                    if (cached != null) {
                        _lyrics.value = cached
                        _lyricsState.value = LyricsState.LOADED
                    } else {
                        // 先 B 站 AI/普通字幕；没有时再查 LRCLIB 同步歌词
                        val biliLines = BilibiliApi.getSubtitleLrc(bvid, cid)
                        val lines = if (biliLines.isNotEmpty()) biliLines
                        else BilibiliApi.getLrclibLyrics(title, author)
                        _lyrics.value = lines
                        _lyricsState.value = if (lines.isEmpty()) LyricsState.NONE else LyricsState.LOADED
                        if (lines.isNotEmpty()) {
                            com.galigalimusic.whisper.WhisperLocal.saveLyricCache(appContext, bvid, cid, lines)
                        }
                    }
                }.onFailure { _lyricsState.value = LyricsState.NONE }
                // 分P 列表（同一视频只拉一次；断网时静默跳过）
                runCatching {
                    if (pagesFetchedFor != bvid) {
                        _pages.value = BilibiliApi.getVideoPages(bvid)
                        pagesFetchedFor = bvid
                    }
                }
            } catch (e: Exception) {
                _current.value = null
                _lyricsState.value = LyricsState.NONE
            } finally {
                _resolving.value = false
            }
        }
    }

    /** 下一首/上一首：多P 视频 = 下一集/上一集（循环）；单P = 历史顺序导航 */
    fun skipNext() {
        if (_pages.value.size > 1) skipPage(+1, wrap = true) else historySkip(+1)
    }

    fun skipPrevious() {
        if (_pages.value.size > 1) skipPage(-1, wrap = true) else historySkip(-1)
    }

    private fun skipPage(delta: Int, wrap: Boolean) {
        val pages = _pages.value
        val cur = _current.value
        if (pages.isEmpty()) {
            historySkip(delta)
            return
        }
        val idx = pages.indexOfFirst { it.cid == cur?.cid }
        val target = when {
            idx == -1 -> pages[0]
            idx + delta in pages.indices -> pages[idx + delta]
            wrap -> pages[((idx + delta) % pages.size + pages.size) % pages.size]
            else -> return
        }
        playPage(target)
    }

    private fun historySkip(delta: Int) {
        val list = history.value
        if (list.isEmpty()) return
        val cur = _current.value
        val idx = list.indexOfFirst { it.bvid == cur?.bvid && it.cid == cur?.cid }
        val target = if (idx == -1) list[0] else {
            list[((idx + delta) % list.size + list.size) % list.size]
        }
        playFromHistory(target)
    }

    fun toggle() {
        val service = PlaybackConnection.service.value ?: return
        runCatching { service.currentPlayer().let { if (it.isPlaying) it.pause() else it.play() } }
    }

    fun seekTo(positionMs: Long) {
        runCatching { PlaybackConnection.service.value?.seekTo(positionMs) }
    }

    /** 从下载页点播：已下载条目走离线分支（startPlayback 内部判定），无网也能播 */
    fun playDownload(bvid: String, cid: Long, title: String, author: String, coverUrl: String) {
        startPlayback(bvid, cid, title, author, coverUrl, durationSec = 0)
    }

    /** 下载当前分P：先解析一次新鲜直链，再交给 DownloadService 后台下载 */
    fun downloadCurrent(onMessage: (String) -> Unit) {
        val cur = _current.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val resolved = repo.resolveAndRecord(
                    cur.bvid, cur.cid, composeHistoryTitle(cur.title, cur.pageLabel),
                    cur.author, cur.coverUrl, (_playback.value.durationMs / 1000).toInt(),
                )
                com.galigalimusic.player.Downloads.start(
                    appContext, cur.bvid, cur.cid, cur.title, cur.author, cur.coverUrl, resolved.url,
                )
                onMessage("已加入离线下载")
            }.onFailure { onMessage("下载失败：${it.message ?: "解析失败"}") }
        }
    }

    fun setVolumeBoost(on: Boolean) {
        volumeBoostOn = on
        runCatching { PlaybackConnection.service.value?.setGain(if (on) 1.6f else 1.0f) }
    }

    fun volumeBoostEnabled() = volumeBoostOn

    fun syncState() {
        runCatching {
            _isPlaying.value = PlaybackConnection.service.value?.currentPlayer()?.isPlaying ?: false
        }
    }
}
