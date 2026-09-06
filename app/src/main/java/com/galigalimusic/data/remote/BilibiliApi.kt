package com.galigalimusic.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Bilibili 音源解析（纯 Kotlin，基于 WBI 签名）。
 * 覆盖：搜索视频、获取 cid、获取音频直链、扫码登录。
 * 所有请求经 BiliSession.jar 携带登录/访客 Cookie（搜索与高清音频需要）。
 */
object BilibiliApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(BiliSession.jar)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com")
                .build()
            chain.proceed(req)
        }
        .build()

    private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/wbi/search/type"
    private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
    private const val PLAYURL_URL = "https://api.bilibili.com/x/player/wbi/playurl"
    private const val PLAYER_INFO_URL = "https://api.bilibili.com/x/player/wbi/v2"
    private const val QR_GENERATE_URL =
        "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
    private const val QR_POLL_URL =
        "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"

    private const val FAV_FOLDERS_URL =
        "https://api.bilibili.com/x/v3/fav/folder/created/list-all"
    private const val FAV_LIST_URL =
        "https://api.bilibili.com/x/v3/fav/resource/list"

    data class FavFolder(val id: Long, val title: String, val mediaCount: Int)

    /** 当前登录用户创建的收藏夹列表（需要登录）。 */
    suspend fun getFavFolders(mid: Long): List<FavFolder> = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder().url("$FAV_FOLDERS_URL?up_mid=$mid&rn=1").build()
        ).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        val list = json.getJSONObject("data").optJSONArray("list") ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { i ->
            val f = list.optJSONObject(i) ?: return@mapNotNull null
            FavFolder(f.optLong("id"), f.optString("title"), f.optInt("media_count", 0))
        }
    }

    /**
     * 拉取收藏夹内全部视频（自动翻页，上限 20 页兜底）。
     * 返回歌曲列表（cid 在播放时再解析）。
     */
    suspend fun getFavVideos(mediaId: Long): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        for (pn in 1..50) {
            val url = "$FAV_LIST_URL?media_id=$mediaId&pn=$pn&ps=20&keyword=&order=mtime&type=0&tid=0&platform=web"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            val json = JSONObject(resp.body?.string().orEmpty())
            if (json.optInt("code") != 0) break
            val data = json.optJSONObject("data") ?: break
            val medias = data.optJSONArray("medias") ?: break
            for (i in 0 until medias.length()) {
                val m = medias.optJSONObject(i) ?: continue
                if (m.optInt("type", 0) != 2) continue // 只收视频类
                val bvid = m.optString("bvid")
                if (bvid.isEmpty()) continue
                songs.add(
                    Song(
                        bvid = bvid,
                        title = stripHtml(m.optString("title")),
                        author = m.optJSONObject("upper")?.optString("name").orEmpty(),
                        coverUrl = normalizePic(m.optString("cover")),
                        durationSec = m.optInt("duration", 0),
                    )
                )
            }
            // has_more 在 B 站返回的是布尔值，optInt 会恒为 0 导致只拉到第一页
            if (!data.optBoolean("has_more", false)) break
        }
        songs
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    data class Song(
        val bvid: String,
        val title: String,
        val author: String,
        val coverUrl: String,
        val durationSec: Int,
    )

    data class SearchSuggestion(val value: String, val ref: String = "")

    /** B 站搜索建议；失败时返回空，不阻塞正式搜索。 */
    suspend fun getSearchSuggestions(keyword: String): List<SearchSuggestion> =
        withContext(Dispatchers.IO) {
            runCatching {
                val term = URLEncoder.encode(keyword, "UTF-8")
                val url = "https://s.search.bilibili.com/main/suggest?term=$term&func=suggest&sub_type=tag"
                val json = JSONObject(client.newCall(Request.Builder().url(url).build()).execute().body?.string().orEmpty())
                val tag = json.optJSONArray("tag") ?: return@runCatching emptyList()
                (0 until tag.length()).mapNotNull { i ->
                    val item = tag.optJSONObject(i) ?: return@mapNotNull null
                    val value = item.optString("value").trim()
                    value.takeIf { it.isNotEmpty() }?.let { SearchSuggestion(it, item.optString("ref")) }
                }.distinctBy { it.value }.take(8)
            }.getOrDefault(emptyList())
        }

    /**
     * 搜索关键词，返回视频型结果（取音频轨道播放）。
     *
     * 用官方视频页的 search/type 接口（WBI 签名 + 登录 Cookie）。
     * 实测结论（2026-08）：
     * · search/all/v2 登录态下 data 结构已变为非数组，恒解析为空；
     * · 匿名请求任何搜索端点都被风控拦（空结果/412 反爬页）；
     * · search/type + 登录态返回正常结果。
     */
    suspend fun searchVideos(keyword: String, page: Int = 1): List<Song> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "20",
            "duration" to "0",
            "tids" to "0",
            "order_type" to "totalrank",
        )
        val signed = WbiSigner.sign(params)
        val url = buildUrl(SEARCH_URL, signed)
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw IllegalStateException("搜索失败 (${code}: ${json.optString("message")})")
        }
        val result = json.optJSONObject("data")?.optJSONArray("result")
            ?: return@withContext emptyList()
        val songs = mutableListOf<Song>()
        for (i in 0 until result.length()) {
            val it = result.optJSONObject(i) ?: continue
            val bvid = it.optString("bvid").takeIf { b -> b.isNotEmpty() } ?: continue
            val title = stripHtml(it.optString("title"))
            val author = it.optString("author")
            val pic = normalizePic(it.optString("pic"))
            // search/type 的 duration 是字符串，可能是秒（"212"）或 mm:ss（"3:33"）
            val duration = it.optString("duration").let { d ->
                if (d.contains(':')) {
                    d.split(":").fold(0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: 0) }
                } else {
                    d.toIntOrNull() ?: 0
                }
            }
            songs.add(Song(bvid, title, author, pic, duration))
        }
        songs
    }

    /** 通过 BV 号直接取视频信息（搜索被风控时的直连播放路径）。 */
    suspend fun getVideoInfo(bvid: String): Song = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder().url("$VIEW_URL?bvid=$bvid").build()
        ).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        if (json.optInt("code") != 0) {
            throw IllegalStateException("视频不存在或不可播放 (${json.optInt("code")})")
        }
        val data = json.getJSONObject("data")
        Song(
            bvid = bvid,
            title = stripHtml(data.optString("title")),
            author = data.optJSONObject("owner")?.optString("name").orEmpty(),
            coverUrl = normalizePic(data.optString("pic")),
            durationSec = data.optInt("duration", 0),
        )
    }

    /** 分P（多P）信息：page 从 1 起，cid 是该分P 的取流键 */
    data class VideoPage(
        val page: Int,
        val cid: Long,
        val part: String,
        val durationSec: Int,
    )

    /** 取视频全部分P 列表（单P 视频返回 1 项）。 */
    suspend fun getVideoPages(bvid: String): List<VideoPage> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.newCall(
                Request.Builder().url("$VIEW_URL?bvid=$bvid").build()
            ).execute()
            val json = JSONObject(resp.body?.string().orEmpty())
            if (json.optInt("code") != 0) return@runCatching emptyList()
            val pages = json.getJSONObject("data").optJSONArray("pages")
                ?: return@runCatching emptyList()
            (0 until pages.length()).mapNotNull { i ->
                val p = pages.optJSONObject(i) ?: return@mapNotNull null
                VideoPage(
                    page = p.optInt("page", i + 1),
                    cid = p.optLong("cid", 0L),
                    part = stripHtml(p.optString("part")),
                    durationSec = p.optInt("duration", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 获取视频的 cid（音频所在分 P 的 id）。 */
    suspend fun getCid(bvid: String): Long = withContext(Dispatchers.IO) {
        val params = mapOf("bvid" to bvid)
        val signed = WbiSigner.sign(params)
        val url = buildUrl(VIEW_URL, signed)
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        json.getJSONObject("data").getLong("cid")
    }

    /** 获取音频直链（优先最高码率）。 */
    suspend fun getAudioUrl(bvid: String, cid: Long): String = withContext(Dispatchers.IO) {
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "qn" to "64",
            "fnval" to "16",
            "fourk" to "1",
        )
        val signed = WbiSigner.sign(params)
        val url = buildUrl(PLAYURL_URL, signed)
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        val dash = json.getJSONObject("data").getJSONObject("dash")
        val audio = dash.getJSONArray("audio")

        // mcdn/:8082 是 P2P CDN，下载/播放经常中途断流（实测 8.6MB 处反复 403/断连）。
        // 优先选普通 upos/mirror CDN 的候选；baseUrl 全是 mcdn 时改用 backupUrl 里的普通 CDN。
        fun isStable(u: String): Boolean =
            u.isNotEmpty() && !u.contains("mcdn") && !u.contains(":8082")
        var best: JSONObject? = null
        var bestP2p: JSONObject? = null
        for (i in 0 until audio.length()) {
            val a = audio.optJSONObject(i) ?: continue
            if (isStable(a.optString("baseUrl"))) {
                if (best == null || a.optInt("bandwidth") > best.optInt("bandwidth")) best = a
            } else if (bestP2p == null) {
                bestP2p = a
            }
        }
        val chosen = best ?: bestP2p ?: return@withContext ""
        var picked = chosen.optString("baseUrl")
        if (!isStable(picked)) {
            val backups = chosen.optJSONArray("backupUrl")
            for (i in 0 until (backups?.length() ?: 0)) {
                val u = backups?.optString(i).orEmpty()
                if (isStable(u)) { picked = u; break }
            }
        }
        picked
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        val q = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$base?$q"
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&")

    // ==================== 多源歌词（LRCLIB） ====================

    /** LRCLIB 同步歌词：不需要登录，作为 B 站字幕缺失时的兜底源。 */
    suspend fun getLrclibLyrics(title: String, author: String): List<LrcLine> =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode("$title $author", "UTF-8")
                val req = Request.Builder()
                    .url("https://lrclib.net/api/search?q=$q")
                    .header("User-Agent", "galigalimusic/1.0 (Android)")
                    .build()
                val json = JSONArray(client.newCall(req).execute().body?.string().orEmpty())
                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val synced = item.optString("syncedLyrics").takeIf { it.isNotBlank() }
                        ?: continue
                    val lines = parseLrc(synced)
                    if (lines.isNotEmpty()) return@runCatching lines
                }
                emptyList()
            }.getOrDefault(emptyList())
        }

    /** 解析标准 LRC（支持 [mm:ss.xx] 与 [mm:ss.xxx]，多标签行）。 */
    private fun parseLrc(text: String): List<LrcLine> {
        val re = Regex("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\]\\s*(.*)")
        return text.lineSequence().flatMap { line ->
            val m = re.matchEntire(line.trim()) ?: return@flatMap emptySequence()
            val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            val start = (m.groupValues[1].toLongOrNull() ?: 0) * 60_000L +
                (m.groupValues[2].toLongOrNull() ?: 0) * 1_000L + fraction
            val content = m.groupValues[4].trim()
            if (content.isEmpty()) emptySequence() else sequenceOf(LrcLine(start, start, content))
        }.sortedBy { it.startMs }.toList()
    }

    // ==================== AI 字幕歌词 ====================

    /** 单行歌词：startMs 对齐播放时间轴；endMs 供后续逐字卡拉OK扩展 */
    data class LrcLine(val startMs: Long, val endMs: Long, val text: String)

    /**
     * 取 B 站字幕并转为歌词行（优先 AI 字幕 ai_type>0，其次普通字幕）。
     * 无字幕/接口异常返回空列表（不阻塞播放）。
     */
    suspend fun getSubtitleLrc(bvid: String, cid: Long): List<LrcLine> =
        withContext(Dispatchers.IO) {
            val dbg: (String) -> Unit = { BiliSession.appendDebug(it) }
            runCatching {
                // 诊断：确认 CookieJar 实际会带上的 Cookie（AI 字幕要求登录态）
                runCatching {
                    val probe = "https://api.bilibili.com/x/player/wbi/v2".toHttpUrlOrNull()!!
                    dbg("cookies attached: " + BiliSession.jar.loadForRequest(probe).joinToString(",") { it.name })
                }
                val signed = WbiSigner.sign(mapOf("bvid" to bvid, "cid" to cid.toString()))
                val resp = client.newCall(
                    Request.Builder().url(buildUrl(PLAYER_INFO_URL, signed)).build()
                ).execute()
                val bodyText = resp.body?.string().orEmpty()
                val json = JSONObject(bodyText)
                val subtitleObj = json.getJSONObject("data").getJSONObject("subtitle")
                val subtitles = subtitleObj.optJSONArray("subtitles")
                dbg("player/v2 bvid=$bvid cid=$cid http=${resp.code} code=${json.optInt("code")} subs=${subtitles?.length() ?: -1}")
                if (subtitles == null || subtitles.length() == 0) {
                    return@runCatching emptyList()
                }

                // 选轨：AI 字幕优先（lan 以 ai- 开头也算 AI 轨）
                var chosen: JSONObject? = null
                for (i in 0 until subtitles.length()) {
                    val s = subtitles.optJSONObject(i) ?: continue
                    val isAi = s.optInt("ai_type", 0) > 0 ||
                        s.optString("lan").startsWith("ai-")
                    if (chosen == null || isAi) chosen = s
                    if (isAi) break
                }
                val subUrl = chosen!!.optString("subtitle_url").orEmpty()
                dbg("chosen lan=${chosen.optString("lan")} ai_type=${chosen.optInt("ai_type")} url=$subUrl")
                if (subUrl.isEmpty()) return@runCatching emptyList()
                val abs = if (subUrl.startsWith("//")) "https:$subUrl" else subUrl

                val subResp = client.newCall(Request.Builder().url(abs).build()).execute()
                val bodyJson = subResp.body?.string().orEmpty()
                dbg("subtitle http=${subResp.code} len=${bodyJson.length}")
                val body = JSONObject(bodyJson).optJSONArray("body")
                    ?: return@runCatching emptyList()

                val lines = mutableListOf<LrcLine>()
                for (i in 0 until body.length()) {
                    val item = body.optJSONObject(i) ?: continue
                    val text = item.optString("content").trim()
                    if (text.isEmpty()) continue
                    lines.add(
                        LrcLine(
                            startMs = (item.optDouble("from") * 1000).toLong(),
                            endMs = (item.optDouble("to") * 1000).toLong(),
                            text = text,
                        )
                    )
                }
                dbg("lrc lines=${lines.size}")
                lines.sortedBy { it.startMs }
            }.onFailure {
                dbg("FAILED: $it")
            }.getOrDefault(emptyList())
        }

    enum class QrState { WAITING, SCANNED, CONFIRMED, EXPIRED }

    data class QrLogin(val qrContent: String, val qrcodeKey: String)

    /** 生成登录二维码：qrContent 需渲染为二维码，用 B 站 App 扫描确认。 */
    suspend fun qrGenerate(): QrLogin = withContext(Dispatchers.IO) {
        val resp = client.newCall(Request.Builder().url(QR_GENERATE_URL).build()).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        val data = json.getJSONObject("data")
        QrLogin(data.getString("url"), data.getString("qrcode_key"))
    }

    /**
     * 轮询扫码状态。CONFIRMED 时登录 Set-Cookie 已由 BiliSession.jar 落盘。
     * 服务端码：86001=未扫 86090=已扫待确认 86038=已失效 0=成功。
     */
    suspend fun qrPoll(qrcodeKey: String): QrState = withContext(Dispatchers.IO) {
        val url = "$QR_POLL_URL?qrcode_key=${URLEncoder.encode(qrcodeKey, "UTF-8")}"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        when (json.getJSONObject("data").optInt("code")) {
            0 -> QrState.CONFIRMED
            86090 -> QrState.SCANNED
            86038 -> QrState.EXPIRED
            else -> QrState.WAITING
        }
    }

    /** 封面地址规范化：B 站常返回 http:// 或协议相对地址，明文会被 Android 9+ 拦截 */
    private fun normalizePic(p: String): String =
        when {
            p.startsWith("//") -> "https:$p"
            p.startsWith("http://") -> "https://" + p.substringAfter("http://")
            else -> p
        }
}
