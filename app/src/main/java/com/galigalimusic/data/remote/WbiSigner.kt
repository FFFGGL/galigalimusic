package com.galigalimusic.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/**
 * Bilibili WBI 签名工具（纯 Kotlin 实现）。
 *
 * 流程：
 * 1. 从 /x/web-interface/nav 取 img_url / sub_url 的文件名（各 32 位 hex）。
 * 2. 拼接后按固定表位重排，得到 32 位 mixinKey。
 * 3. 对请求参数按 key 排序，追加 wts，再 md5(query + mixinKey) 得到 w_rid。
 *
 * 参考：B 站公开 WBI 签名算法。
 */
object WbiSigner {

    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

    // 固定重排表（64 项，取前 32 位）
    private val MIXIN_KEY_ENC_TABLE = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(BiliSession.jar)
        .build()
    private val mutex = Mutex()
    private var cachedMixinKey: String? = null
    private var cachedAt: Long = 0L
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 小时

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun baseName(url: String): String =
        url.substringAfterLast("/").substringBefore(".")

    private suspend fun fetchMixinKey(): String {
        val resp = client.newCall(
            Request.Builder().url(NAV_URL).addHeader("User-Agent", UA).build()
        ).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        val data = json.getJSONObject("data")
        val wbi = data.getJSONObject("wbi_img")
        val img = baseName(wbi.getString("img_url"))
        val sub = baseName(wbi.getString("sub_url"))
        val raw = img + sub
        return buildString {
            for (i in 0 until 32) {
                append(raw[MIXIN_KEY_ENC_TABLE[i]])
            }
        }
    }

    private suspend fun getMixinKey(): String {
        val now = System.currentTimeMillis()
        if (cachedMixinKey != null && now - cachedAt < CACHE_TTL_MS) {
            return cachedMixinKey!!
        }
        return mutex.withLock {
            if (cachedMixinKey != null && now - cachedAt < CACHE_TTL_MS) {
                cachedMixinKey!!
            } else {
                val key = fetchMixinKey()
                cachedMixinKey = key
                cachedAt = now
                key
            }
        }
    }

    /**
     * 对传入参数追加 wts 与 w_rid，返回可直接用于请求的完整 Query 参数。
     * 用于签名的 query 串按服务端规则对 value 做 URL 编码，避免中文/特殊字符导致签名不一致。
     */
    suspend fun sign(params: Map<String, String>): Map<String, String> {
        val mixinKey = getMixinKey()
        val ts = (System.currentTimeMillis() / 1000).toString()
        val merged = params.toMutableMap().apply { put("wts", ts) }
        val query = merged.entries.sortedBy { it.key }
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val wbiSign = md5(query + mixinKey)
        return merged.apply { put("w_rid", wbiSign) }
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
}
