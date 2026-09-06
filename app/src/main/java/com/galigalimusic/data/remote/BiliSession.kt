package com.galigalimusic.data.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * B 站会话：Cookie 持久化（SharedPreferences，name→value 平铺）+ 全局 CookieJar。
 *
 * 作用：
 * 1. 登录后（扫码/导入）SESSDATA 等自动落盘，所有 api.bilibili.com 请求自动携带；
 * 2. 匿名访客先 warm-up 领 buvid3/buvid4（搜索、playurl 的风控要求）；
 * 3. 登录态暴露为 StateFlow，设置页 UI 订阅。
 *
 * 全部 Cookie 都属于 *.bilibili.com，因此不做 host 区分，统一按 name→value 存储。
 */
object BiliSession {

    private const val PREFS = "bili_session"
    private const val KEY_SESSDATA = "SESSDATA"
    private const val KEY_MID = "DedeUserID"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    /** 登录态（DedeUserID，空串=未登录） */
    private val _mid = MutableStateFlow("")
    val mid: StateFlow<String> = _mid

    fun init(appContext: Context) {
        if (prefs != null) return
        this.appContext = appContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mid.value = prefs!!.getString(KEY_MID, "").orEmpty()
    }

    /**
     * 诊断日志追加到外部私有目录（华为 EMUI 压制第三方 logcat，文件是可靠通道）。
     * adb 读取：/sdcard/Android/data/<pkg>/files/lyric_debug.log
     */
    fun appendDebug(line: String) {
        val ctx = appContext ?: return
        runCatching {
            val f = java.io.File(ctx.getExternalFilesDir(null), "lyric_debug.log")
            f.appendText(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " " + line + "\n"
            )
        }
    }

    private fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val p = prefs ?: return
        val e = p.edit()
        block(e)
        e.apply()
    }

    /** OkHttp 全局 CookieJar：读=注入全部已存 Cookie；写=落盘（含二维码轮询返回的登录 Set-Cookie） */
    val jar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            edit { e ->
                for (c in cookies) e.putString(c.name, c.value)
            }
            cookies.find { it.name == KEY_MID }?.let { _mid.value = it.value }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val p = prefs ?: return emptyList()
            return p.all.mapNotNull { (name, value) ->
                val v = value as? String ?: return@mapNotNull null
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(v)
                        .domain("bilibili.com")
                        .path("/")
                        .build()
                }.getOrNull()
            }
        }
    }

    private val client = OkHttpClient()

    /**
     * 访客暖身：领 buvid3（主站 Set-Cookie）与 buvid4（finger/spi）。
     * 应用启动时调用一次即可；失败静默（未登录核心链路 view/playurl 仍可用）。
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val p = prefs ?: return@withContext
        if (p.getString("buvid3", null) != null) return@withContext
        runCatching {
            val ua = Request.Builder().url("https://www.bilibili.com/").header("User-Agent", UA).build()
            client.newCall(ua).execute().use { resp -> resp.body?.string() }
            val spi = Request.Builder()
                .url("https://api.bilibili.com/x/frontend/finger/spi")
                .header("User-Agent", UA)
                .build()
            client.newCall(spi).execute().use { resp ->
                val json = org.json.JSONObject(resp.body?.string().orEmpty())
                val data = json.optJSONObject("data") ?: return@use
                edit { e ->
                    e.putString("buvid3", data.optString("b_3"))
                    e.putString("buvid4", data.optString("b_4"))
                }
            }
        }
    }

    /** 给 Media3 原生 DataSource 使用的 Cookie 头（OkHttp CookieJar 不会自动传过去）。 */
    fun cookieHeader(): String {
        val p = prefs ?: return ""
        return p.all.mapNotNull { (name, value) ->
            val v = value as? String ?: return@mapNotNull null
            "$name=$v"
        }.joinToString("; ")
    }

    fun isLoggedIn(): Boolean = _mid.value.isNotEmpty()

    fun logout() {
        edit { e ->
            e.remove(KEY_SESSDATA)
            e.remove(KEY_MID)
            e.remove("bili_jct")
        }
        _mid.value = ""
    }

    /** SESSDATA 粘贴兜底登录（jct 可空；mid 可空则无法展示，仅凭 SESSDATA 请求） */
    fun importSession(sessdata: String, jct: String?, mid: String) {
        edit { e ->
            e.putString(KEY_SESSDATA, sessdata.trim())
            if (!jct.isNullOrBlank()) e.putString("bili_jct", jct.trim())
            e.putString(KEY_MID, mid.trim())
        }
        _mid.value = mid.trim()
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
}
