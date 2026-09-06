package com.galigalimusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galigalimusic.data.remote.ExternalSources
import com.galigalimusic.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

private data class DavEntry(
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
)

private val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma", "ape")

/**
 * AList 网盘浏览：WebDAV 目录树 + 点击音频直接流播（Basic 认证随请求携带）。
 */
@Composable
fun AListScreen(
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<DavEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(p: String) {
        scope.launch {
            loading = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val base = ExternalSources.davBase(context)
                    val url = base + p.trimEnd('/') + "/"
                    val body = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getcontentlength/></D:prop></D:propfind>"""
                    val req = okhttp3.Request.Builder()
                        .url(url)
                        .header("Depth", "1")
                        .post(okhttp3.RequestBody.create(null, body))
                    ExternalSources.authHeaders(context).forEach { (k, v) -> req.header(k, v) }
                    okhttp3.OkHttpClient().newCall(req.build()).execute().use { resp ->
                        if (resp.code == 401) throw IllegalStateException("认证失败：检查用户名/密码")
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val xml = resp.body?.string().orEmpty()
                        val out = mutableListOf<DavEntry>()
                        val rows = xml.split(Regex("<[a-zA-Z0-9]*:?response[ >]")).drop(1)
                        for (row in rows) {
                            val href = Regex("<[a-zA-Z0-9]*:?href[^>]*>(.*?)</[a-zA-Z0-9]*:?href>")
                                .find(row)?.groupValues?.get(1)?.let { s ->
                                    URLDecoder.decode(s.trim().replace(Regex("^https?://[^/]+"), ""), "UTF-8")
                                } ?: continue
                            val isDir = row.contains(":collection") || row.contains("collection")
                            val size = Regex("<[a-zA-Z0-9]*:?getcontentlength[^>]*>(\\d+)<")
                                .find(row)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                            val rel = href.removePrefix(base)
                            if (rel.isEmpty() || rel == "/") continue
                            out += DavEntry(rel, isDir, size)
                        }
                        out.sortedWith(compareByDescending<DavEntry> { it.isDir }.thenBy { it.path })
                    }
                }
            }
            loading = false
            result.onSuccess {
                entries = it
                path = p
            }.onFailure {
                error = it.message ?: "连接失败"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!ExternalSources.isConfigured(context)) {
            onToast("请先在设置里填写 AList 服务器地址")
        } else {
            load("/")
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text("AList 网盘", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (path != "/") {
                TextButton(onClick = {
                    val parent = path.trimEnd('/').substringBeforeLast('/', "")
                    load(if (parent.isEmpty()) "/" else "$parent/")
                }) { Text("上一级") }
            }
        }
        Text(
            path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        when {
            !ExternalSources.isConfigured(context) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("尚未配置 AList 服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = { load(path) }) { Text("重试") }
                }
            }
            entries != null && entries!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            entries != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries!!) { e ->
                    val name = e.path.trimEnd('/').substringAfterLast('/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when {
                                    e.isDir -> load(e.path)
                                    ext in AUDIO_EXT -> {
                                        val url = ExternalSources.davBase(context) + e.path
                                        playerVm.playExternal(
                                            uri = url,
                                            title = name.substringBeforeLast('.'),
                                            author = "AList",
                                            coverUrl = null,
                                            sourceTag = "alist",
                                        )
                                        onToast("开始播放：$name")
                                    }
                                    else -> onToast("暂不支持的格式：.$ext")
                                }
                            },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when {
                                    e.isDir -> Icons.Rounded.Folder
                                    ext in AUDIO_EXT -> Icons.Rounded.MusicNote
                                    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = if (e.isDir) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!e.isDir && e.sizeBytes > 0) {
                                    Text(
                                        "%.1f MB".format(e.sizeBytes / 1048576.0),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}
