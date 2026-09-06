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

    // ===== 歌词文件缓存（AI 字幕与本地识别共用） =====

    fun lyricCacheFile(context: Context, bvid: String, cid: Long): File {
        val dir = File(context.filesDir, "lyrics")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${bvid}_${cid}.lrc")
    }

    fun loadLyricCache(context: Context, bvid: String, cid: Long): List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>? {
        val f = lyricCacheFile(context, bvid, cid)
        if (!f.exists()) return null
        return runCatching {
            f.readLines().filter { it.isNotBlank() }.map { l ->
                val p = l.split("\t")
                com.galigalimusic.data.remote.BilibiliApi.LrcLine(
                    startMs = p[0].toLong(), endMs = p.getOrElse(1) { "0" }.toLong(),
                    text = p.getOrElse(2) { "" },
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun saveLyricCache(context: Context, bvid: String, cid: Long, lines: List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>) {
        runCatching {
            lyricCacheFile(context, bvid, cid).writeText(
                lines.joinToString("\n") { "${it.startMs}\t${it.endMs}\t${it.text}" }
            )
        }
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
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
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
