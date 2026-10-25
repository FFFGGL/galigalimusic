package com.galigalimusic.data.repository

/**
 * 外部歌单导入的条目（m3u / 歌单名清单解析的中间产物）。
 *
 * bvid 非空 = B 站视频（导入时补 cid/元信息）；
 * extUri 非空 = 本地文件路径（以 ext: 前缀进歌单，与播放器外部音源方案一致）。
 */
data class ExternalEntry(
    val bvid: String = "",
    val extUri: String = "",
    val title: String = "",
    val author: String = "",
)

/** m3u / 歌单名清单解析（纯函数，不碰网络） */
object ExternalPlaylistParser {

    private val BV_RE = Regex("(BV[0-9A-Za-z]{10})")

    /** 文件类型嗅探：按内容判断，扩展名只作兜底 */
    fun detect(text: String): ImportKind = when {
        text.trimStart().startsWith("#EXTM3U", ignoreCase = true) -> ImportKind.M3U
        text.trimStart().startsWith("{") -> ImportKind.JSON
        else -> ImportKind.LIST
    }

    enum class ImportKind { JSON, M3U, LIST }

    /**
     * 解析 m3u/m3u8：
     * `#EXTINF:秒数,作者 - 标题` 下一行是 location。
     * B 站链接（含 BV 号）→ bvid 条目；其余按本地文件路径。
     */
    fun parseM3u(text: String): List<ExternalEntry> {
        val entries = mutableListOf<ExternalEntry>()
        var pendingTitle = ""
        var pendingAuthor = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // #EXTINF:<sec>,<显示名>
                    val meta = line.substringAfter(':', "")
                    val name = meta.substringAfter(',', "").trim().ifEmpty { meta.trim() }
                    val (author, title) = splitAuthorTitle(name)
                    pendingAuthor = author
                    pendingTitle = title
                }
                line.startsWith("#") -> Unit // 其他注释行跳过
                else -> {
                    val bv = BV_RE.find(line)?.value
                    entries.add(
                        if (bv != null) {
                            ExternalEntry(
                                bvid = bv,
                                title = pendingTitle.ifEmpty { bv },
                                author = pendingAuthor,
                            )
                        } else {
                            ExternalEntry(
                                extUri = line,
                                title = pendingTitle.ifEmpty { line.substringAfterLast('/') },
                                author = pendingAuthor,
                            )
                        }
                    )
                    pendingTitle = ""
                    pendingAuthor = ""
                }
            }
        }
        return entries
    }

    /**
     * 解析「歌单名清单」txt：每行一首歌。
     * 支持「作者 - 标题」「标题 - 作者」「标题」三种写法（取搜索结果匹配）。
     */
    fun parseList(text: String): List<ExternalEntry> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val (author, title) = splitAuthorTitle(line)
                ExternalEntry(title = title, author = author, extUri = "")
            }
            .toList()

    /**
     * 「A - B」拆分：搜索结果里「作者 - 标题」更常见，
     * 但用户也常写「标题 - 作者」，无法可靠区分 → 两种组合都交给搜索匹配。
     */
    private fun splitAuthorTitle(raw: String): Pair<String, String> {
        val s = raw.trim()
        if (s.isEmpty()) return "" to ""
        // 只在第一个「 - 」处拆（标题里也可能含连字符）
        val idx = s.indexOf(" - ")
        if (idx <= 0) return "" to s
        val left = s.substring(0, idx).trim()
        val right = s.substring(idx + 3).trim()
        if (left.isEmpty() || right.isEmpty()) return "" to s
        return left to right // 左侧按作者处理，右侧按标题
    }

    /** 歌单标题建议名：优先文件名（去扩展名），空则「导入的歌单」 */
    fun playlistNameFromFile(fileName: String?): String {
        val base = fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.trim().orEmpty()
        return base.ifEmpty { "导入的歌单" }
    }
}
