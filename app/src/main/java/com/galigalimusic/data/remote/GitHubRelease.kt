package com.galigalimusic.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases 检查更新（release 流水线把签名 APK 挂到 release assets）。
 * 失败一律返回 null，由调用方决定提示文案；绝不抛异常打断 UI。
 */
object GitHubRelease {

    private const val REPO = "FFFGGL/galigalimusic"

    data class Info(
        /** 去掉前缀 v 的版本号，如 "1.2" */
        val version: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String?,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun latest(): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "galigalimusic-android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val tag = json.optString("tag_name").removePrefix("v").trim()
                if (tag.isEmpty()) return@use null
                val apkUrl = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length())
                        .mapNotNull { i -> arr.optJSONObject(i) }
                        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                        ?.optString("browser_download_url")
                        ?.takeIf { it.isNotEmpty() }
                }
                Info(
                    version = tag,
                    name = json.optString("name").ifEmpty { tag },
                    notes = json.optString("body").trim().take(2000),
                    pageUrl = json.optString("html_url"),
                    apkUrl = apkUrl,
                )
            }
        }.getOrNull()
    }

    /** remote 是否比 local 新（按点/连字符分段，能按数字比的按数字比） */
    fun isNewer(remote: String, local: String): Boolean {
        val rp = remote.split(Regex("[.\\-]"))
        val lp = local.split(Regex("[.\\-]"))
        for (i in 0 until maxOf(rp.size, lp.size)) {
            val r = rp.getOrNull(i).orEmpty()
            val l = lp.getOrNull(i).orEmpty()
            val rn = r.toIntOrNull()
            val ln = l.toIntOrNull()
            if (rn != null && ln != null) {
                if (rn != ln) return rn > ln
            } else if (r != l) {
                return r > l
            }
        }
        return false
    }
}
