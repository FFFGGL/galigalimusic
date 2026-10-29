package com.galigalimusic.whisper

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * whisper 模型下载 + 歌词文件缓存 + 识别编排的纯函数部分。
 */
object WhisperLocal {

    data class Model(val name: String, val file: String, val url: String, val sizeBytes: Long)

    /** 多语种 tiny 模型（中文可用），hf-mirror 国内可直连 */
    val TINY = Model("tiny", "ggml-tiny.bin", "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin", 78L * 1024 * 1024)
    val BASE = Model("base", "ggml-base.bin", "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-base.bin", 148L * 1024 * 1024)

    /**
     * 识别模型选择（持久化到 prefs）。
     *
     * · tiny：78MB，快，但中文人名/术语错得多（实测「瞎子」识别成「虾子」）
     * · base：148MB，约 1.5 倍耗时，中文明显更准
     *
     * 默认 base —— 听书场景对准确率敏感，宁可多等一会儿。
     * 用户可在设置页切回 tiny 省空间。
     */
    private const val PREFS = "whisper_prefs"
    private const val KEY_MODEL = "model"

    fun currentModel(context: Context): Model {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, BASE.name)
        return if (name == TINY.name) TINY else BASE
    }

    fun setModel(context: Context, model: Model) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, model.name).apply()
    }

    /**
     * 语言设置。
     *
     * 默认 "zh"（不是 "auto"）：实测 "auto" 会让整段识别返回空
     * —— 每段独立调用 whisper_full 时，检测只看当前 30 秒，
     * 遇到片头音乐就会判成别的语言，中文内容被当外语过滤掉。
     * 用户在设置页可以改，但默认值必须稳。
     */
    fun currentLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("language", "zh") ?: "zh"

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("language", lang).apply()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelFile(context: Context, model: Model): File {
        val dir = File(context.getExternalFilesDir(null), "whisper")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, model.file)
    }

    fun isModelReady(context: Context, model: Model): Boolean {
        val f = modelFile(context, model)
        return f.exists() && f.length() > model.sizeBytes * 90 / 100
    }

    /** 下载模型（流式，onProgress 0-100；中断的半截文件下次重下） */
    fun downloadModel(context: Context, model: Model, onProgress: (Int) -> Unit): File {
        val dest = modelFile(context, model)
        val tmp = File(dest.parentFile, dest.name + ".part")
        val req = Request.Builder().url(model.url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("模型下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("模型下载空响应")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    var acc = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        acc += read
                        if (total > 0) onProgress((acc * 100 / total).toInt())
                    }
                }
            }
        }
        if (tmp.length() < model.sizeBytes * 50 / 100) {
            tmp.delete()
            throw IllegalStateException("模型下载不完整")
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        return dest
    }

    // ===== 歌词文件缓存（按来源分离，避免互相覆盖） =====

    /** 歌词来源：B 站官方字幕 / 本地 whisper 识别 / 外部匹配（LRCLIB）/ 手工校正 */
    enum class LyricSource(val suffix: String) {
        BILI("bili"),
        WHISPER("whisper"),
        EXT("ext"),
        /** 手工校正后的整份歌词（优先于所有自动来源） */
        EDITED("edited"),
    }

    fun lyricCacheFile(context: Context, bvid: String, cid: Long, source: LyricSource): File {
        val dir = File(context.filesDir, "lyrics")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${bvid}_${cid}.${source.suffix}.lrc")
    }

    fun loadLyricCache(
        context: Context,
        bvid: String,
        cid: Long,
        source: LyricSource,
    ): List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>? {
        val f = lyricCacheFile(context, bvid, cid, source)
        if (!f.exists()) return null
        return runCatching {
            f.readLines().filter { it.isNotBlank() }.mapNotNull { l ->
                val p = l.split("\t")
                val s = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                com.galigalimusic.data.remote.BilibiliApi.LrcLine(
                    startMs = s, endMs = p.getOrElse(1) { "0" }.toLongOrNull() ?: s,
                    text = p.getOrElse(2) { "" },
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun saveLyricCache(
        context: Context,
        bvid: String,
        cid: Long,
        source: LyricSource,
        lines: List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>,
    ) {
        runCatching {
            lyricCacheFile(context, bvid, cid, source).writeText(
                lines.joinToString("\n") { "${it.startMs}\t${it.endMs}\t${it.text}" }
            )
        }
    }

    /**
     * 按优先级取缓存：手工校正 > 官方字幕 > 本地识别 > 外部匹配。
     * 播放起播时用这个；用户手动切源时按 [LyricSource] 精确读取。
     */
    fun loadPreferredLyricCache(
        context: Context,
        bvid: String,
        cid: Long,
    ): Pair<List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>, LyricSource>? {
        loadLyricCache(context, bvid, cid, LyricSource.EDITED)?.let { return it to LyricSource.EDITED }
        loadLyricCache(context, bvid, cid, LyricSource.BILI)?.let { return it to LyricSource.BILI }
        loadLyricCache(context, bvid, cid, LyricSource.WHISPER)?.let { return it to LyricSource.WHISPER }
        loadLyricCache(context, bvid, cid, LyricSource.EXT)?.let { return it to LyricSource.EXT }
        return null
    }

    /** 是否已有手工校正版 */
    fun hasEditedCache(context: Context, bvid: String, cid: Long): Boolean =
        lyricCacheFile(context, bvid, cid, LyricSource.EDITED).exists()

    /** 删掉手工校正版，回退到自动来源 */
    fun clearEditedCache(context: Context, bvid: String, cid: Long) {
        runCatching { lyricCacheFile(context, bvid, cid, LyricSource.EDITED).delete() }
    }

    /** 是否已经识别过（有 whisper 缓存） */
    fun hasWhisperCache(context: Context, bvid: String, cid: Long): Boolean =
        lyricCacheFile(context, bvid, cid, LyricSource.WHISPER).exists()

    /** 清掉 whisper 结果（让下次起播重新走官方字幕） */
    fun clearWhisperCache(context: Context, bvid: String, cid: Long) {
        runCatching { lyricCacheFile(context, bvid, cid, LyricSource.WHISPER).delete() }
    }

    /** 下载音频直链到本地（B 站 CDN 需要 Referer/UA，长直链会 403） */
    fun downloadAudio(url: String, dest: File, onProgress: (Int) -> Unit) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("音频下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("音频下载空响应")
            val total = body.contentLength()
            val acc = java.util.concurrent.atomic.AtomicLong(0)
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        acc.addAndGet(read.toLong())
                        if (total > 0) onProgress((acc.get() * 100 / total).toInt())
                    }
                }
            }
            // 关键：断流时 read() 会提前返回 -1，不校验就会把「半截文件」当完整文件用。
            // 而容器头里声明的时长仍是完整的，解码时会在数据末尾挂住（华为 CCodec 不回 EOS）。
            // 这里发现短了就重下一次，仍短则明确报错，别让用户等十来分钟才失败。
            if (total > 0 && acc.get() < total * 99 / 100) {
                dest.delete()
                val got = acc.get()
                val req2 = req.newBuilder().build()
                client.newCall(req2).execute().use { r2 ->
                    if (!r2.isSuccessful) throw IllegalStateException("音频下载失败 HTTP ${r2.code}")
                    val b2 = r2.body ?: throw IllegalStateException("音频下载空响应")
                    val t2 = b2.contentLength()
                    val a2 = java.util.concurrent.atomic.AtomicLong(0)
                    b2.byteStream().use { i2 ->
                        dest.outputStream().use { o2 ->
                            val buf2 = ByteArray(256 * 1024)
                            var rd: Int
                            while (i2.read(buf2).also { rd = it } != -1) {
                                o2.write(buf2, 0, rd)
                                a2.addAndGet(rd.toLong())
                                if (t2 > 0) onProgress((a2.get() * 100 / t2).toInt())
                            }
                        }
                    }
                    if (t2 > 0 && a2.get() < t2 * 99 / 100) {
                        throw IllegalStateException(
                            "音频下载不完整（$got/${total}B，重试后 ${a2.get()}/${t2}B），请检查网络后重试"
                        )
                    }
                }
            }
        }
    }

    /** 旧版单文件缓存（`${bvid}_${cid}.lrc`，不分来源）的清理与迁移 */
    private fun legacyLyricFile(context: Context, bvid: String, cid: Long): File =
        File(File(context.filesDir, "lyrics"), "${bvid}_${cid}.lrc")

    /**
     * 一次性迁移：旧的单一缓存无法区分是官方字幕还是 whisper 结果，
     * 保守起见直接删掉，让它重新按新逻辑拉取（宁可重下一次也不留错源数据）。
     */
    fun dropLegacyLyricCache(context: Context, bvid: String, cid: Long) {
        runCatching {
            val f = legacyLyricFile(context, bvid, cid)
            if (f.exists()) f.delete()
        }
    }

    /** 解析 native 返回的 JSON → 歌词行（whisper t0/t1 已是 ms） */
    fun parseNativeJson(json: String): List<com.galigalimusic.data.remote.BilibiliApi.LrcLine> {
        val obj = JSONObject(json)
        obj.optString("error").takeIf { it.isNotEmpty() }?.let { throw IllegalStateException(it) }
        val segs = obj.getJSONArray("segments")
        val out = mutableListOf<com.galigalimusic.data.remote.BilibiliApi.LrcLine>()
        for (i in 0 until segs.length()) {
            val s = segs.getJSONObject(i)
            val text = s.optString("text").trim()
            if (text.isEmpty()) continue
            out.add(
                com.galigalimusic.data.remote.BilibiliApi.LrcLine(
                    startMs = s.optLong("t0"), endMs = s.optLong("t1"), text = text,
                )
            )
        }
        return out
    }
}
