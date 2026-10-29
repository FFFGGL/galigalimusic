package com.galigalimusic.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galigalimusic.PlaybackConnection
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.HistoryEntity
import com.galigalimusic.data.local.QueueEntity
import com.galigalimusic.data.remote.BiliSession
import com.galigalimusic.data.remote.BilibiliApi
import com.galigalimusic.data.repository.MusicRepository
import com.galigalimusic.data.repository.QueueRepository
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
    private val queueRepo: QueueRepository,
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
        /** 已缓冲到的位置：配合缓存字节数反推码率，判断缓存够不够识别用 */
        val bufferedMs: Long = 0L,
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

        /** 本地识别只做前 N 秒：whisper base 在手机上约 3~5 倍实时，
         *  整集（有声书常见 40~60 分钟）要跑几十分钟，用户等不到。
         *  20 分钟 ≈ 40 段 ≈ 5~7 分钟出全词，是体验与覆盖的折中。 */
        const val RECOGNIZE_MAX_SEC = 1200
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

    // ===== 定时关闭（睡前听书）：按分钟 / 按集数两种模式，互斥 =====
    @Volatile
    private var sleepEndAt = 0L

    private val _sleepRemainingSec = MutableStateFlow(-1L)
    val sleepRemainingSec: StateFlow<Long> = _sleepRemainingSec

    /** 按集数定时：还剩几集（-1 = 未设置） */
    private val _sleepTracksRemaining = MutableStateFlow(-1)
    val sleepTracksRemaining: StateFlow<Int> = _sleepTracksRemaining
    @Volatile
    private var sleepTracksLeft = 0

    fun setSleepTimer(minutes: Int) {
        sleepTracksLeft = 0
        _sleepTracksRemaining.value = -1
        sleepEndAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepRemainingSec.value = minutes * 60L
    }

    /** 按集数定时：放完 N 集后暂停（不自动进下一集） */
    fun setSleepTimerTracks(tracks: Int) {
        sleepEndAt = 0
        _sleepRemainingSec.value = -1
        sleepTracksLeft = tracks
        _sleepTracksRemaining.value = tracks
    }

    fun clearSleepTimer() {
        sleepEndAt = 0
        _sleepRemainingSec.value = -1
        sleepTracksLeft = 0
        _sleepTracksRemaining.value = -1
    }

    // ===== 播放队列（稍后播放 / 下一首播放；skipNext 与播完续播优先弹队首） =====
    val queue: StateFlow<List<QueueEntity>> = queueRepo.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 稍后播放：追加队尾 */
    fun enqueueLater(
        bvid: String,
        cid: Long,
        title: String,
        author: String,
        coverUrl: String,
        durationSec: Int = 0,
    ) {
        viewModelScope.launch {
            queueRepo.append(
                QueueEntity(
                    position = 0, bvid = bvid, cid = cid, title = title,
                    author = author, coverUrl = coverUrl, durationSec = durationSec,
                )
            )
        }
    }

    /** 下一首播放：插入队首 */
    fun playNextInQueue(
        bvid: String,
        cid: Long,
        title: String,
        author: String,
        coverUrl: String,
        durationSec: Int = 0,
    ) {
        viewModelScope.launch {
            queueRepo.prepend(
                QueueEntity(
                    position = 0, bvid = bvid, cid = cid, title = title,
                    author = author, coverUrl = coverUrl, durationSec = durationSec,
                )
            )
        }
    }

    /** 点队列项立即播放（从队列移除，成为当前曲目） */
    fun playQueueItem(item: QueueEntity) {
        viewModelScope.launch {
            queueRepo.remove(item.id)
            startPlayback(item.bvid, item.cid, item.title, item.author, item.coverUrl, item.durationSec)
        }
    }

    fun removeFromQueue(id: Long) {
        viewModelScope.launch { queueRepo.remove(id) }
    }

    fun moveInQueue(id: Long, delta: Int) {
        viewModelScope.launch { queueRepo.move(id, delta) }
    }

    fun clearQueue() {
        viewModelScope.launch { queueRepo.clear() }
    }

    /**
     * 弹队首播放。队列非空返回 true（已接管切歌），空返回 false（调用方走原逻辑）。
     */
    private fun popQueueAndPlay(): Boolean {
        val head = queue.value.firstOrNull() ?: return false
        viewModelScope.launch {
            runCatching { queueRepo.remove(head.id) }
            startPlayback(
                head.bvid, head.cid, head.title, head.author, head.coverUrl, head.durationSec,
            )
        }
        return true
    }

    // ===== 播放失败自愈（URL 过期等 Source error → 重解析续播一次） =====
    private var errorRetryKey: String? = null

    // ===== 收听计时（播放统计） =====
    private var listenAccumMs = 0L
    private var lastListenTickAt = System.currentTimeMillis()

    // ===== 续播位置心跳 =====
    private var resumeAccumMs = 0L

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
                            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
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
                                            qn = effectiveAudioQn(),
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

                // 收听计时 + 续播位置：播放中按真实时间累计。
                // 收听每 15s 刷库；续播位置每 5s 刷库（杀进程最多丢 5 秒）
                val nowMs = System.currentTimeMillis()
                val playingNow = _isPlaying.value && _current.value != null
                if (playingNow) {
                    val delta = (nowMs - lastListenTickAt).coerceIn(0L, 5_000L)
                    listenAccumMs += delta
                    resumeAccumMs += delta
                }
                lastListenTickAt = nowMs
                if (playingNow) {
                    val cur = _current.value
                    if (cur != null) {
                        if (resumeAccumMs >= 5_000) {
                            resumeAccumMs = 0
                            val pos = _playback.value.positionMs
                            if (pos > 0) {
                                viewModelScope.launch {
                                    runCatching { repo.saveResumePosition(cur.bvid, cur.cid, pos) }
                                }
                            }
                        }
                        if (listenAccumMs >= 15_000) {
                            val sec = listenAccumMs / 1000
                            listenAccumMs %= 1000
                            viewModelScope.launch {
                                runCatching { repo.addListenTime(cur.bvid, cur.cid, sec) }
                            }
                        }
                    } else {
                        listenAccumMs = 0
                        resumeAccumMs = 0
                    }
                } else {
                    resumeAccumMs = 0
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
                // 关键：getCachedBytes 是稀疏 span 的总和，拖过进度条后中间有空档也会"看着很大"。
                // 只有「从 0 开始连续」的字节才是能拼出完整容器的，否则一律重下（否则解码出静音）。
                val contiguousBytes = runCatching {
                    com.galigalimusic.player.MediaCache.contiguousBytesFromZero(cache, cacheKey)
                }.getOrDefault(0L)
                val sparse = contiguousBytes < cachedBytes
                BiliSession.appendDebug(
                    "recognize: cache bytes=$cachedBytes contiguous=$contiguousBytes sparse=$sparse for $cacheKey"
                )
                audioFile = java.io.File(appContext.cacheDir, "whisper_audio.m4a")
                // 先清掉上次失败残留，避免重下分支读到旧坏文件
                runCatching { audioFile.delete() }
                var exportedOk = false
                if (contiguousBytes > 512 * 1024 && !sparse) {
                    BiliSession.appendDebug("recognize: export from cache")
                    val exported = com.galigalimusic.player.MediaCache.exportToFile(cache, cacheKey, audioFile)
                    BiliSession.appendDebug("recognize: exported $exported bytes")
                    // 关键校验：SimpleCache 拼出来的字节可能缺 MP4 容器头（m4s 分片），
                    // 这种文件 MediaExtractor 找不到音频轨、会解出全零 PCM，
                    // whisper 只会返回空 segments（实测踩过）。校验不过就走重下。
                    exportedOk = exported > 0 &&
                        com.galigalimusic.whisper.WhisperAudio.hasParsableAudioTrack(audioFile.absolutePath)
                    // 再校验数据量：容器头里的时长是「声明值」，缓存残缺时它照样报完整时长
                    // （实测 2MB 残文件也报 57:24），所以拿它比时长判断不出完整性。
                    // 改为：用「连续字节 ÷ 已缓冲秒数」反推码率，乘以识别需要的秒数，
                    // 看从 0 连续缓存够不够。拖过进度条的缓存往往只有开头几十秒，
                    // 这种文件解到数据末尾会在部分机型上挂死（华为 CCodec 不回 EOS）。
                    if (exportedOk) {
                        val trackDur = _playback.value.durationMs
                        val bufferedMs = _playback.value.bufferedMs
                        val needSec = (if (trackDur > 0L) {
                            minOf(trackDur / 1000L, RECOGNIZE_MAX_SEC.toLong())
                        } else {
                            RECOGNIZE_MAX_SEC.toLong()
                        }).coerceAtLeast(1L)
                        val needBytes = if (bufferedMs > 1_000L) {
                            contiguousBytes.toDouble() * needSec * 1_000.0 / bufferedMs
                        } else {
                            0.0
                        }
                        val enough = needBytes > 0.0 && contiguousBytes >= needBytes * 0.95
                        BiliSession.appendDebug(
                            "recognize: cache export trackDur=$trackDur buffered=$bufferedMs " +
                            "needSec=$needSec needBytes=${needBytes.toLong()} enough=$enough"
                        )
                        if (!enough) exportedOk = false
                    }
                    BiliSession.appendDebug("recognize: cache export parsable=$exportedOk")
                } else if (sparse) {
                    BiliSession.appendDebug("recognize: cache sparse (seeked), skip export → redownload")
                }
                if (!exportedOk) {
                    // 缓存不可用或不是完整容器：重新解析并下载完整响应体
                    // （dash 音频 URL 返回的是带 ftyp/moov 的完整 fMP4，MediaExtractor 能直读）
                    val resolved = repo.resolveAndRecord(
                        cur.bvid, cur.cid, composeHistoryTitle(cur.title, cur.pageLabel),
                        cur.author, cur.coverUrl, 0,
                        qn = effectiveAudioQn(),
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
                    // 进度百分比的分母：本次实际要转写的秒数（长音频按上限截断）
                    val trackSec = _playback.value.durationMs / 1000L
                    val targetSec = (if (trackSec > 0L) minOf(trackSec, RECOGNIZE_MAX_SEC.toLong())
                    else RECOGNIZE_MAX_SEC.toLong()).coerceAtLeast(1L)
                    com.galigalimusic.whisper.WhisperAudio.decodeTranscribeStreaming(
                        audioFile!!.absolutePath,
                        wavFile!!,
                        sliceSec = 30,
                        maxSeconds = RECOGNIZE_MAX_SEC,
                        transcribe = { samples ->
                            // 首段真正进入推理时把状态切到「识别中」
                            if (_recognizeState.value != RecognizeState.TRANSCRIBING) {
                                _recognizeState.value = RecognizeState.TRANSCRIBING
                            }
                            val t0 = android.os.SystemClock.elapsedRealtime()
                            val json = com.galigalimusic.whisper.WhisperNative
                                .transcribeSamples(ptr, samples, language, threads)
                            val cost = android.os.SystemClock.elapsedRealtime() - t0
                            BiliSession.appendDebug(
                                "whisper slice: ${cost}ms jsonLen=${json.length} head=${json.take(120)}"
                            )
                            // 单段超过 3 分钟 = 推理异常（正常 15~25s）。
                            // 记一笔，配合 CANCELLED 日志区分是推理卡住还是协程被外部取消
                            if (cost > 180_000) {
                                BiliSession.appendDebug("whisper WARN: slow slice ${cost}ms")
                            }
                            com.galigalimusic.whisper.WhisperLocal.parseNativeJson(json)
                        },
                        // lines 时间戳已由 WhisperAudio 换算成整集绝对毫秒，这里直接追加
                        onSlice = { lines, idx, fromSample ->
                            acc += lines
                            _lyrics.value = acc.toList()
                            _lyricsState.value = LyricsState.LOADED
                            _lyricsSource.value = WhisperLocal.LyricSource.WHISPER
                            // 进度按「已转写秒数 ÷ 目标秒数」算：以前直接拿段序号当百分比，
                            // 57 分钟的音频 100 多段，进度条基本不动，看着像卡死
                            val doneSec = (fromSample / com.galigalimusic.whisper.WhisperAudio.OUT_RATE +
                                30L).coerceAtMost(targetSec)
                            _recognizeProgress.value =
                                (doneSec * 100 / targetSec).toInt().coerceIn(1, 99)
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
                    dbg("stream done: lines=${acc.size} cancel=$recognizeCancel state=${_recognizeState.value}")
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
                            val capped = trackSec > RECOGNIZE_MAX_SEC
                            val tail = if (capped) "（仅前 ${RECOGNIZE_MAX_SEC / 60} 分钟）" else ""
                            "识别完成，共 ${acc.size} 句$tail"
                        }
                        dbg("DONE: msg=${_recognizeMsg.value}")
                        // 提示挂 10 秒自动收起，别长期占着歌词区底部
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(10_000)
                            if (_recognizeState.value == RecognizeState.DONE) {
                                _recognizeState.value = RecognizeState.IDLE
                                _recognizeMsg.value = ""
                            }
                        }
                    } else if (recognizeCancel) {
                        _recognizeState.value = RecognizeState.IDLE
                        _recognizeProgress.value = 0
                        _recognizeMsg.value = "已取消"
                    } else {
                        throw IllegalStateException("未识别出语音内容，可能是纯音乐或音量过低")
                    }
                } finally {
                    // freeContext 内部走 JNI，native 侧异常会原样抛出来；
                    // 若不吞掉，它会在正常返回路径上制造一个新异常，把「识别完成」
                    // 变成无声失败（日志里连 FAILED 都没有，因为异常发生在 catch 之外）
                    runCatching { com.galigalimusic.whisper.WhisperNative.freeContext(ptr) }
                        .onFailure { dbg("freeContext failed: $it") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动取消 vs 协程被外部取消（ViewModel 被清、作用域取消）表现一样，
                // 但后者是 bug：日志里必须能区分，否则「识别无声消失」根本查不到原因
                dbg(
                    "CANCELLED: cancelFlag=$recognizeCancel " +
                    "state=${_recognizeState.value} slices=${_recognizeProgress.value} coroutineActive=${isActive}"
                )
                if (recognizeCancel) {
                    _recognizeState.value = RecognizeState.IDLE
                    _recognizeProgress.value = 0
                    _recognizeMsg.value = "已取消"
                } else {
                    _recognizeState.value = RecognizeState.ERROR
                    _recognizeMsg.value = "识别被中断，请重试"
                }
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
            // 下载音频断流（downloadAudio 校验出来的）——比模型那条更具体，先判
            m.contains("音频下载不完整") || m.contains("音频下载失败") ||
                m.contains("音频下载空响应") -> "音频下载中断，请检查网络后重试"
            m.contains("模型文件下载不完整") -> "模型文件下载不完整，请重试"
            m.contains("没有音频轨") -> "该音频没有可解码的音轨"
            // 拖过进度条后老版本会拼出「半真半假」的缓存文件，解码出静音落到这里。
            // 现在已改为自动重下完整音频，正常不会再触发，保留为兜底并给可执行建议。
            // 注意别用宽泛的「不完整」，否则会抢走下面「模型文件下载不完整」那条。
            m.contains("解码为空") || m.contains("缺少容器头") ->
                "音频数据不完整，已自动重下，请再点一次识别"
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

    // ===== B5 歌词手工校正 =====

    /** 当前歌曲是否已有手工校正版（UI 显示「恢复原始」按钮） */
    fun hasEditedLyrics(): Boolean {
        val cur = _current.value ?: return false
        return WhisperLocal.hasEditedCache(appContext, cur.bvid, cur.cid)
    }

    /**
     * 保存对某一行的校正（文字 + 起止时间），整份写入 EDITED 缓存。
     * 原自动来源缓存原样保留，随时可 [restoreOriginalLyrics] 还原。
     */
    fun saveLyricEdit(index: Int, text: String, startMs: Long, endMs: Long) {
        val cur = _current.value ?: return
        val lines = _lyrics.value.toMutableList()
        if (index !in lines.indices) return
        lines[index] = BilibiliApi.LrcLine(
            startMs = startMs.coerceAtLeast(0),
            endMs = endMs.coerceAtLeast(startMs + 1),
            text = text.trim(),
        )
        WhisperLocal.saveLyricCache(
            appContext, cur.bvid, cur.cid, WhisperLocal.LyricSource.EDITED, lines,
        )
        _lyrics.value = lines
        _lyricsSource.value = WhisperLocal.LyricSource.EDITED
        _lyricsState.value = LyricsState.LOADED
    }

    /** 恢复自动来源：删 EDITED 缓存后按优先级回落，都没有则重新联网拉 */
    fun restoreOriginalLyrics() {
        val cur = _current.value ?: return
        WhisperLocal.clearEditedCache(appContext, cur.bvid, cur.cid)
        val cached = WhisperLocal.loadPreferredLyricCache(appContext, cur.bvid, cur.cid)
        if (cached != null) {
            _lyrics.value = cached.first
            _lyricsSource.value = cached.second
            _lyricsState.value = LyricsState.LOADED
        } else {
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
        // 自然播完：续播位置归零，下次从头开始
        val ended = _current.value
        if (ended != null) {
            viewModelScope.launch {
                runCatching { repo.clearResumePosition(ended.bvid, ended.cid) }
            }
        }
        // 按集数定时：当前集放完后倒数，归零即停（不再自动下一集）
        if (sleepTracksLeft > 0) {
            sleepTracksLeft -= 1
            if (sleepTracksLeft <= 0) {
                sleepTracksLeft = 0
                _sleepTracksRemaining.value = -1
                runCatching { PlaybackConnection.service.value?.currentPlayer()?.pause() }
                return
            }
            _sleepTracksRemaining.value = sleepTracksLeft
        }
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
            PlayMode.REPEAT_ALL -> if (!popQueueAndPlay()) skipPage(+1, wrap = true)
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
                // 保留累计收听时长与续播位置（REPLACE 语义会整行覆盖）
                val prev = runCatching { repo.getHistoryKey(pseudoBvid, 0L) }.getOrNull()
                repo.upsertHistory(
                    HistoryEntity(
                        bvid = pseudoBvid, cid = 0L, title = title, author = author,
                        coverUrl = coverUrl ?: "", durationSec = 0,
                        listenedSec = prev?.listenedSec ?: 0L,
                        resumePositionMs = prev?.resumePositionMs ?: 0L,
                    )
                )
                // 外部音源同样续播
                val resumeMs = prev?.resumePositionMs ?: 0L
                if (resumeMs > 3_000L) runCatching { service.seekTo(resumeMs) }
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
                        bvid, cid, composeHistoryTitle(title, pageLabel), author, coverUrl, durationSec,
                        qn = effectiveAudioQn(),
                    )
                    resolved.url
                }
                // 缓存键稳定（bvid|cid）：直链过期换新 URL 后，同一键继续累积
                service.playUrl(playUrl, title, author, coverUrl, cacheKey)
                // 恢复用户倍速（ExoPlayer 换曲会保留参数，但进程重启后需重设）
                runCatching { service.setSpeed(_speed.value) }
                // 续播：有进度就从上次位置继续（优先于片头跳过）；没有才跳片头
                val resumeMs = runCatching {
                    repo.getHistoryKey(bvid, cid)?.resumePositionMs ?: 0L
                }.getOrDefault(0L)
                if (resumeMs > 3_000L) {
                    runCatching { service.seekTo(resumeMs) }
                } else if (headSkipSec > 0) {
                    runCatching { service.seekTo(headSkipSec * 1000L) }
                }
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
        // 队列优先：有待播队列时先弹队首
        if (popQueueAndPlay()) return
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
                    qn = effectiveAudioQn(),
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

    // ===== 歌词字号（全局，持久化；歌词页/播放页预览共用） =====
    private val _lyricFontScale = MutableStateFlow(prefs.getFloat("lyric_font_scale", 1.0f))
    val lyricFontScale: StateFlow<Float> = _lyricFontScale

    /** 循环切换字号：0.85(小) → 1.0(标准) → 1.2(大) → 1.45(特大) → 回小 */
    fun cycleLyricFontScale() {
        val options = floatArrayOf(0.85f, 1.0f, 1.2f, 1.45f)
        val next = options.firstOrNull { it > _lyricFontScale.value + 0.01f } ?: options.first()
        _lyricFontScale.value = next
        prefs.edit().putFloat("lyric_font_scale", next).apply()
    }

    fun lyricFontScaleLabel(): String = when (_lyricFontScale.value) {
        in 0f..0.9f -> "小"
        in 0.9f..1.05f -> "标准"
        in 1.05f..1.3f -> "大"
        else -> "特大"
    }

    // ===== 播放音质（全局，持久化；蜂窝下按「仅 Wi-Fi 高音质」降档） =====
    private val _audioQuality = MutableStateFlow(
        com.galigalimusic.data.remote.AudioQuality.selected(appContext)
    )
    val audioQuality: StateFlow<Int> = _audioQuality

    private val _wifiOnlyHighQuality = MutableStateFlow(
        com.galigalimusic.data.remote.AudioQuality.wifiOnlyHigh(appContext)
    )
    val wifiOnlyHighQuality: StateFlow<Boolean> = _wifiOnlyHighQuality

    fun setAudioQuality(qn: Int) {
        com.galigalimusic.data.remote.AudioQuality.setSelected(appContext, qn)
        _audioQuality.value = qn
    }

    fun setWifiOnlyHighQuality(on: Boolean) {
        com.galigalimusic.data.remote.AudioQuality.setWifiOnlyHigh(appContext, on)
        _wifiOnlyHighQuality.value = on
    }

    /** 当前网络下的生效档位 */
    fun effectiveAudioQn(): Int =
        com.galigalimusic.data.remote.AudioQuality.effective(appContext)

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

    // ===== B6 追更订阅 =====

    private val subRepo = com.galigalimusic.data.repository.SubscriptionRepository(db)

    /** 已订阅的 bvid 集合（播放页追更按钮高亮） */
    val subscribedBvids: StateFlow<Set<String>> = subRepo.subscribedBvids
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _subscribeMsg = MutableStateFlow<String?>(null)
    val subscribeMsg: StateFlow<String?> = _subscribeMsg

    fun consumeSubscribeMsg() { _subscribeMsg.value = null }

    /** 播放页追更按钮：对当前合集订阅/退订（拉最新分P数作基线） */
    fun toggleSubscribeCurrent() {
        val cur = _current.value ?: return
        if (cur.bvid.isEmpty()) return
        viewModelScope.launch {
            val existing = subscribedBvids.value.contains(cur.bvid)
            if (existing) {
                subRepo.unsubscribe(cur.bvid)
                _subscribeMsg.value = "已取消追更"
                return@launch
            }
            _subscribeMsg.value = "正在获取合集信息…"
            val meta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { BilibiliApi.getVideoMeta(cur.bvid) }.getOrNull()
            }
            if (meta == null || meta.pageCount <= 0) {
                _subscribeMsg.value = "获取合集信息失败，稍后重试"
                return@launch
            }
            subRepo.subscribe(
                bvid = meta.bvid,
                title = meta.title.ifBlank { cur.title },
                author = meta.author.ifBlank { cur.author },
                coverUrl = meta.coverUrl.ifBlank { cur.coverUrl },
                pageCount = meta.pageCount,
            )
            _subscribeMsg.value = "已追更（当前 ${meta.pageCount} 集）"
        }
    }

    /** 从追更列表播放：有历史则从最近听的一集续播，否则从第 1 集开始 */
    fun playSubscription(bvid: String) {
        viewModelScope.launch {
            val hist = runCatching { repo.getHistoryLatestForBvid(bvid) }.getOrNull()
            if (hist != null) {
                playFromHistory(hist)
                return@launch
            }
            val pages = runCatching { BilibiliApi.getVideoPages(bvid) }.getOrNull().orEmpty()
            if (pages.isEmpty()) {
                _errorMessage.value = "获取合集信息失败，请稍后重试"
                return@launch
            }
            val first = pages.first()
            val meta = runCatching { BilibiliApi.getVideoMeta(bvid) }.getOrNull()
            startPlayback(
                bvid = bvid,
                cid = first.cid,
                title = meta?.title?.ifBlank { null } ?: bvid,
                author = meta?.author.orEmpty(),
                coverUrl = meta?.coverUrl.orEmpty(),
                durationSec = 0,
                pageLabel = if (pages.size > 1) "P${first.page} ${first.part}".trim() else "",
            )
        }
    }
}
