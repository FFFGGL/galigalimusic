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
import com.galigalimusic.whisper.WhisperLocal
import kotlinx.coroutines.CancellationException
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

    /** 一次性错误提示（播放器未就绪 / 解析失败等），UI 消费后调用 [clearError] */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun clearError() { _errorMessage.value = null }

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

    /** 歌词加载状态 */
    enum class LyricsState { LOADING, NONE, LOADED }

    // 歌词来源用 WhisperLocal.LyricSource（直接 import，不用嵌套 typealias：Room 的 KSP 处理器
    // 遇到嵌套 typealias 会抛无消息的 IllegalStateException，整条注解处理链失败）

    private val _lyricsSource = MutableStateFlow<WhisperLocal.LyricSource?>(null)
    val lyricsSource: StateFlow<WhisperLocal.LyricSource?> = _lyricsSource

    /** 播放模式：列表循环 / 随机 / 单曲循环（持久化，重启后恢复） */
    enum class PlayMode { REPEAT_ALL, SHUFFLE, REPEAT_ONE }

    private val _playMode = MutableStateFlow(
        runCatching { PlayMode.valueOf(prefs.getString("play_mode", null) ?: "") }
            .getOrDefault(PlayMode.REPEAT_ALL)
    )
    val playMode: StateFlow<PlayMode> = _playMode

    /** 播放历史（最近优先），作为上一首/下一首与首页「最近播放」的数据源 */
    val history: StateFlow<List<HistoryEntity>> = repo.history()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                        if (cur != null &&
                            player.playbackState == androidx.media3.common.Player.STATE_IDLE &&
                            !_resolving.value &&
                            (_playback.value.durationMs > 0 || _playback.value.positionMs > 0)
                        ) {
                            val key = "${cur.bvid}|${cur.cid}"
                            if (errorRetryKey != key) {
                                errorRetryKey = key
                                val resumeAt = _playback.value.positionMs
                                _resolving.value = true
                                viewModelScope.launch {
                                    runCatching {
                                        val resolved = repo.resolveAndRecord(
                                            cur.bvid, cur.cid, cur.title, cur.author,
                                            cur.coverUrl,
                                            (_playback.value.durationMs / 1000).toInt(),
                                        )
                                        svc.playUrl(resolved.url, cur.title, cur.author, cur.coverUrl, key)
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

    /** 用户在进度状态里主动取消识别（已识别的段落保留） */
    fun cancelRecognize() {
        if (recognizing) recognizeCancel = true
    }

    fun recognizeLocally() {
        val cur = _current.value ?: return
        if (recognizing || cur.bvid.isEmpty() || cur.cid <= 0) return
        recognizing = true
        recognizeCancel = false
        _recognizeState.value = RecognizeState.DOWNLOADING_MODEL
        _recognizeProgress.value = 0
        _recognizeMsg.value = ""
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var audioFile: java.io.File? = null
            var wavFile: java.io.File? = null
            val dbg: (String) -> Unit = { com.galigalimusic.data.remote.BiliSession.appendDebug("recognize: $it") }
            try {
                // 1. 模型：按设置页的选择（默认 base，中文比 tiny 准很多）
                val wl = com.galigalimusic.whisper.WhisperLocal
                val model = wl.currentModel(appContext)
                val language = wl.currentLanguage(appContext)
                BiliSession.appendDebug("recognize: model=${model.name} lang=$language")
                val modelReady = wl.isModelReady(appContext, model)
                if (!modelReady) {
                    wl.downloadModel(appContext, model) { p -> _recognizeProgress.value = p }
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
                var exportedOk = false
                if (cachedBytes > 512 * 1024) {
                    BiliSession.appendDebug("recognize: export from cache")
                    audioFile.delete()
                    val exported = com.galigalimusic.player.MediaCache.exportToFile(cache, cacheKey, audioFile)
                    BiliSession.appendDebug("recognize: exported $exported bytes")
                    // 关键校验：SimpleCache 拼出来的字节可能缺 MP4 容器头（m4s 分片），
                    // 这种文件 MediaExtractor 找不到音频轨、会解出全零 PCM，
                    // whisper 只会返回空 segments（实测踩过）。校验不过就走重下。
                    exportedOk = exported > 0 &&
                        com.galigalimusic.whisper.WhisperAudio.hasParsableAudioTrack(audioFile.absolutePath)
                    BiliSession.appendDebug("recognize: cache export parsable=$exportedOk")
                }
                if (!exportedOk) {
                    // 缓存不可用或不是完整容器：重新解析并下载完整响应体
                    // （dash 音频 URL 返回的是带 ftyp/moov 的完整 fMP4，MediaExtractor 能直读）
                    val resolved = repo.resolveAndRecord(
                        cur.bvid, cur.cid, composeHistoryTitle(cur.title, cur.pageLabel),
                        cur.author, cur.coverUrl, 0,
                    )
                    com.galigalimusic.whisper.WhisperLocal.downloadAudio(
                        resolved.url, audioFile
                    ) { p -> _recognizeProgress.value = p }
                    if (!com.galigalimusic.whisper.WhisperAudio.hasParsableAudioTrack(audioFile.absolutePath)) {
                        throw IllegalStateException("音频无法解析（缺少容器头），该视频可能不支持本地识别")
                    }
                }
                if (recognizeCancel) throw CancellationException("已取消")
                // 3. 解码+转写流水线：边解码边出句（每 30s 一段立即上屏），不再先解码整集
                _recognizeState.value = RecognizeState.DECODING
                _recognizeProgress.value = 0
                wavFile = java.io.File(appContext.cacheDir, "whisper_in.wav")
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val modelPath = wl.modelFile(appContext, model).path
                val ptr = com.galigalimusic.whisper.WhisperNative.initContext(modelPath)
                if (ptr == 0L) throw IllegalStateException("模型加载失败，请重装模型后再试")
                try {
                    val acc = mutableListOf<com.galigalimusic.data.remote.BilibiliApi.LrcLine>()
                    com.galigalimusic.whisper.WhisperAudio.decodeTranscribeStreaming(
                        audioFile!!.absolutePath,
                        wavFile!!,
                        sliceSec = 30,
                        transcribe = { samples ->
                            // 首段真正进入推理时把状态切到「识别中」
                            if (_recognizeState.value != RecognizeState.TRANSCRIBING) {
                                _recognizeState.value = RecognizeState.TRANSCRIBING
                            }
                            val json = com.galigalimusic.whisper.WhisperNative
                                .transcribeSamples(ptr, samples, language, threads)
                            BiliSession.appendDebug(
                                "whisper slice: jsonLen=${json.length} head=${json.take(120)}"
                            )
                            com.galigalimusic.whisper.WhisperLocal.parseNativeJson(json)
                        },
                        // lines 时间戳已由 WhisperAudio 换算成整集绝对毫秒，这里直接追加
                        onSlice = { lines, idx, _ ->
                            acc += lines
                            _lyrics.value = acc.toList()
                            _lyricsState.value = LyricsState.LOADED
                            _lyricsSource.value = WhisperLocal.LyricSource.WHISPER
                            _recognizeProgress.value = idx
                            // 增量落盘：每 3 段存一次，取消也不丢已识别内容
                            if (idx % 3 == 0) {
                                com.galigalimusic.whisper.WhisperLocal.saveLyricCache(
                                    appContext, cur.bvid, cur.cid,
                                    WhisperLocal.LyricSource.WHISPER, acc,
                                )
                            }
                        },
                        isCancelled = { recognizeCancel },
                        debugSink = { dbg(it) },
                    )
                    // 统一收口：有结果就落盘（取消也保留），再按状态给用户明确反馈
                    if (acc.isNotEmpty()) {
                        com.galigalimusic.whisper.WhisperLocal.saveLyricCache(
                            appContext, cur.bvid, cur.cid,
                            WhisperLocal.LyricSource.WHISPER, acc,
                        )
                        _recognizeState.value = RecognizeState.DONE
                        _recognizeProgress.value = 100
                        _recognizeMsg.value = if (recognizeCancel) {
                            "已取消，保留已识别的 ${acc.size} 句"
                        } else {
                            "识别完成，共 ${acc.size} 句"
                        }
                    } else if (recognizeCancel) {
                        _recognizeState.value = RecognizeState.IDLE
                        _recognizeProgress.value = 0
                        _recognizeMsg.value = "已取消"
                    } else {
                        throw IllegalStateException("未识别出语音内容，可能是纯音乐或音量过低")
                    }
                } finally {
                    com.galigalimusic.whisper.WhisperNative.freeContext(ptr)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _recognizeState.value = RecognizeState.IDLE
                _recognizeProgress.value = 0
                _recognizeMsg.value = "已取消"
            } catch (e: Exception) {
                dbg("FAILED: $e")
                _recognizeState.value = RecognizeState.ERROR
                _recognizeMsg.value = friendlyRecognizeError(e)
            } finally {
                audioFile?.delete()
                wavFile?.delete()
                recognizing = false
                recognizeCancel = false
            }
        }
    }

    /** 把底层异常翻译成能看懂的一句话（原始堆栈只进调试日志） */
    private fun friendlyRecognizeError(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException || m.contains("Unable to resolve", true) ->
                "网络不可用，模型或音频下载失败"
            e is java.net.SocketTimeoutException -> "网络超时，请检查网络后重试"
            m.contains("HTTP 4") || m.contains("HTTP 5") -> "下载失败（$m），请稍后重试"
            m.contains("不完整") -> "模型文件下载不完整，请重试"
            m.contains("没有音频轨") -> "该音频没有可解码的音轨"
            m.contains("模型加载失败") -> "识别模型加载失败，请重试或清理后重下"
            else -> m.take(80).ifEmpty { "识别失败，请重试" }
        }
    }

    /**
     * 歌词切源：在有官方字幕和本地识别结果之间切换。
     *
     * 非破坏性：切回官方字幕时**不删** whisper 缓存，用户随时能再切回来。
     * 想彻底丢弃本地识别结果用 [discardWhisperLyrics]。
     *
     * @return true = 切换成功；false = 另一个来源不存在（由调用方提示）
     */
    fun switchLyricSource(): Boolean {
        val cur = _current.value ?: return false
        val wl = WhisperLocal
        val hasWhisper = wl.hasWhisperCache(appContext, cur.bvid, cur.cid)
        if (!hasWhisper) return false
        return if (_lyricsSource.value == WhisperLocal.LyricSource.WHISPER) {
            // 切回官方字幕（只换显示，不删缓存）
            reloadLyricsFromNetwork(cur)
            true
        } else {
            val lines = wl.loadLyricCache(appContext, cur.bvid, cur.cid, WhisperLocal.LyricSource.WHISPER)
                ?: return false
            _lyrics.value = lines
            _lyricsSource.value = WhisperLocal.LyricSource.WHISPER
            _lyricsState.value = LyricsState.LOADED
            true
        }
    }

    /** 是否有本地识别结果可切（决定 UI 是否显示切源按钮） */
    fun hasLocalLyrics(): Boolean {
        val cur = _current.value ?: return false
        return cur.cid > 0 && WhisperLocal.hasWhisperCache(appContext, cur.bvid, cur.cid)
    }

    /** 丢弃本地识别结果，让下次起播重新走官方字幕 */
    fun discardWhisperLyrics() {
        val cur = _current.value ?: return
        WhisperLocal.clearWhisperCache(appContext, cur.bvid, cur.cid)
        if (_lyricsSource.value == WhisperLocal.LyricSource.WHISPER) {
            reloadLyricsFromNetwork(cur)
        }
    }

    /** 重新按网络优先级拉一次歌词（官方字幕 → LRCLIB），不阻塞播放 */
    private fun reloadLyricsFromNetwork(cur: Current) {
        viewModelScope.launch {
            _lyricsState.value = LyricsState.LOADING
            _lyricsSource.value = null
            runCatching {
                val biliLines = BilibiliApi.getSubtitleLrc(cur.bvid, cur.cid)
                val lines = if (biliLines.isNotEmpty()) biliLines
                else BilibiliApi.getLrclibLyrics(cur.title, cur.author)
                _lyrics.value = lines
                if (lines.isEmpty()) {
                    _lyricsState.value = LyricsState.NONE
                } else {
                    _lyricsState.value = LyricsState.LOADED
                    val src = if (biliLines.isNotEmpty())
                        WhisperLocal.LyricSource.BILI
                    else WhisperLocal.LyricSource.EXT
                    _lyricsSource.value = src
                    com.galigalimusic.whisper.WhisperLocal.saveLyricCache(
                        appContext, cur.bvid, cur.cid, src, lines,
                    )
                }
            }.onFailure { _lyricsState.value = LyricsState.NONE }
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
        val next = when (_playMode.value) {
            PlayMode.REPEAT_ALL -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.REPEAT_ALL
        }
        _playMode.value = next
        prefs.edit().putString("play_mode", next.name).apply()
        applyPlayMode(next)
    }

    /** 把播放模式真正下发到播放器（单曲循环 = ExoPlayer repeatMode ONE） */
    private fun applyPlayMode(mode: PlayMode) {
        runCatching {
            PlaybackConnection.service.value?.setRepeatOne(mode == PlayMode.REPEAT_ONE)
        }
    }

    /** 从歌单曲目播放：cid>0 直接播该分P；cid=0（历史数据）退回 P1 */
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
            playPage1(bvid, title, author, coverUrl, durationSec)
        }
    }

    /** 搜索结果/BV 直连：默认播 P1，分P 列表随后可从选集切换 */
    fun play(song: BilibiliApi.Song) {
        playPage1(song.bvid, song.title, song.author, song.coverUrl, song.durationSec, song.cid)
    }

    /** 播 P1：先取 cid（免一次 view 请求用 song.cid，取不到再查） */
    private fun playPage1(
        bvid: String,
        title: String,
        author: String,
        coverUrl: String,
        durationSec: Int,
        knownCid: Long = 0L,
    ) {
        viewModelScope.launch {
            _resolving.value = true
            try {
                val cid = if (knownCid > 0L) knownCid else BilibiliApi.getCid(bvid)
                startPlayback(
                    bvid = bvid,
                    cid = cid,
                    title = title,
                    author = author,
                    coverUrl = coverUrl,
                    durationSec = durationSec,
                )
            } catch (e: Exception) {
                _current.value = null
                _lyricsState.value = LyricsState.NONE
                _errorMessage.value = "无法播放：${playErrorMessage(e)}"
            } finally {
                _resolving.value = false
            }
        }
    }

    /** 播放类网络异常的中文化 */
    private fun playErrorMessage(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException || m.contains("Unable to resolve", true) -> "网络不可用"
            e is java.net.SocketTimeoutException -> "网络超时，请重试"
            m.contains("视频不存在") -> m
            m.contains("解析") || m.contains("playurl") -> "获取播放地址失败，请重试"
            else -> m.take(60).ifEmpty { "解析失败" }
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
        val service = PlaybackConnection.service.value
        if (service == null) {
            _errorMessage.value = "播放器还没准备好，请稍后再试"
            return
        }
        val pseudoBvid = "ext:$uri"
        _current.value = Current(title, author, coverUrl ?: "", pseudoBvid, 0L, "")
        _lyricOffsetMs.value = prefs.getLong("lyric_offset_${pseudoBvid}_0", 0L)
        _lyrics.value = emptyList()
        _lyricsSource.value = null
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
                val wl = com.galigalimusic.whisper.WhisperLocal
                val cached = wl.loadPreferredLyricCache(appContext, pseudoBvid, 0L)
                if (cached != null) {
                    _lyrics.value = cached.first
                    _lyricsSource.value = cached.second
                    _lyricsState.value = LyricsState.LOADED
                } else {
                    val lines = BilibiliApi.getLrclibLyrics(title, author)
                    _lyrics.value = lines
                    if (lines.isEmpty()) {
                        _lyricsState.value = LyricsState.NONE
                        _lyricsSource.value = null
                    } else {
                        _lyricsState.value = LyricsState.LOADED
                        _lyricsSource.value = WhisperLocal.LyricSource.EXT
                        wl.saveLyricCache(appContext, pseudoBvid, 0L, WhisperLocal.LyricSource.EXT, lines)
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
        val service = PlaybackConnection.service.value
        if (service == null) {
            _resolving.value = false
            _errorMessage.value = "播放器还没准备好，请稍后再试"
            return
        }
        _resolving.value = true
        _current.value = Current(title, author, coverUrl, bvid, cid, pageLabel)
        _lyricOffsetMs.value = prefs.getLong("lyric_offset_${bvid}_${cid}", 0L)
        _lyrics.value = emptyList()
        _lyricsSource.value = null
        _lyricsState.value = LyricsState.LOADING
        // 切歌重置识别态（否则上一集的"识别完成"会挂在新曲目上）
        if (!recognizing) {
            _recognizeState.value = RecognizeState.IDLE
            _recognizeProgress.value = 0
            _recognizeMsg.value = ""
        }
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
                // 歌词：文件缓存优先（官方字幕 > 本地识别）→ B 站 AI 字幕 → LRCLIB。
                // 网络类异常在这里吞掉：播放已经开始了，断网不能把 UI 状态清掉
                runCatching {
                    val wl = com.galigalimusic.whisper.WhisperLocal
                    // 清掉旧版不分来源的单文件缓存（无法判断来源，宁可重拉）
                    wl.dropLegacyLyricCache(appContext, bvid, cid)
                    val cached = wl.loadPreferredLyricCache(appContext, bvid, cid)
                    if (cached != null) {
                        _lyrics.value = cached.first
                        _lyricsSource.value = cached.second
                        _lyricsState.value = LyricsState.LOADED
                    } else {
                        // 先 B 站 AI/普通字幕；没有时再查 LRCLIB 同步歌词
                        val biliLines = BilibiliApi.getSubtitleLrc(bvid, cid)
                        val lines = if (biliLines.isNotEmpty()) biliLines
                        else BilibiliApi.getLrclibLyrics(title, author)
                        _lyrics.value = lines
                        if (lines.isEmpty()) {
                            _lyricsState.value = LyricsState.NONE
                            _lyricsSource.value = null
                        } else {
                            _lyricsState.value = LyricsState.LOADED
                            val src = if (biliLines.isNotEmpty()) WhisperLocal.LyricSource.BILI
                            else WhisperLocal.LyricSource.EXT
                            _lyricsSource.value = src
                            wl.saveLyricCache(appContext, bvid, cid, src, lines)
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
                _errorMessage.value = "无法播放：${e.message ?: "解析失败"}"
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

    /** 音量增益（+60%）开关：持久化，播放页与设置页共用同一状态 */
    private val _volumeBoost = MutableStateFlow(prefs.getBoolean("volume_boost", false))
    val volumeBoost: StateFlow<Boolean> = _volumeBoost

    fun setVolumeBoost(on: Boolean) {
        _volumeBoost.value = on
        prefs.edit().putBoolean("volume_boost", on).apply()
        runCatching { PlaybackConnection.service.value?.setGain(if (on) 1.6f else 1.0f) }
    }

    fun isVolumeBoostOn(): Boolean = _volumeBoost.value

    /** 播放页面打开时与服务对齐一次（服务可能比 VM 先/后就绪） */
    fun syncState() {
        runCatching {
            _isPlaying.value = PlaybackConnection.service.value?.currentPlayer()?.isPlaying ?: false
        }
        runCatching { PlaybackConnection.service.value?.setSpeed(_speed.value) }
        applyPlayMode(_playMode.value)
        runCatching {
            PlaybackConnection.service.value?.setGain(if (_volumeBoost.value) 1.6f else 1.0f)
        }
    }
}
