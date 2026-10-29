package com.galigalimusic.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.media3.exoplayer.offline.Download
import com.galigalimusic.player.DownloadService
import com.galigalimusic.player.Downloads
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(private val context: android.content.Context) : ViewModel() {
    init {
        // 确保冷启动直接进下载页也能看到已记录的任务（否则列表初始恒为空，
        // 要等 DownloadService 被别的路径拉起后才会填充）
        Downloads.get(context.applicationContext)
    }

    val downloads: StateFlow<List<Download>> = Downloads.list
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun remove(context: Context, key: String) {
        Downloads.remove(context, key)
    }

    fun removeBatch(context: Context, keys: Set<String>) {
        keys.forEach { Downloads.remove(context, it) }
    }

    fun retry(context: Context, download: Download) {
        viewModelScope.launch { Downloads.retry(context, download) }
    }
}

/**
 * 下载管理页：列出下载中/已完成的离线内容，支持暂停/继续与删除。
 * 已完成条目点击直接离线播放。
 */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlayDownload: (bvid: String, cid: Long, title: String, author: String, coverUrl: String) -> Unit =
        { _, _, _, _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: DownloadsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            @Suppress("UNCHECKED_CAST")
            DownloadsViewModel(context) as T
    })
    val downloads by vm.downloads.collectAsState()
    val lastErrors by com.galigalimusic.player.Downloads.lastErrors.collectAsState()
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("全部") }

    // 多选删除模式
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    // Android 13+ 通知权限：进入下载页时顺势请求（下载完成/失败需要通知）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001
            )
        }
    }
    val visibleDownloads = downloads.filter { d ->
        filter == "全部" || (filter == "失败" && d.state == Download.STATE_FAILED) ||
            (filter == "已完成" && d.state == Download.STATE_COMPLETED) ||
            (filter == "进行中" && d.state in setOf(Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_STOPPED))
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
            // 选择模式隐藏标题：给「全选/删除(N)/完成」让位，窄屏不溢出
            if (!selectionMode) {
                Text("离线下载", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.weight(1f))
            if (selectionMode) {
                TextButton(onClick = {
                    selected = if (selected.size == visibleDownloads.size)
                        emptySet() else visibleDownloads.map { it.request.id }.toSet()
                }) { Text(if (selected.size == visibleDownloads.size) "全不选" else "全选") }
                TextButton(onClick = {
                    vm.removeBatch(context, selected)
                    selected = emptySet()
                    selectionMode = false
                }, enabled = selected.isNotEmpty()) { Text("删除(${selected.size})") }
                TextButton(onClick = { selectionMode = false; selected = emptySet() }) { Text("完成") }
            } else {
                TextButton(onClick = { selectionMode = true }, enabled = downloads.isNotEmpty()) { Text("多选") }
                TextButton(onClick = {
                    androidx.media3.exoplayer.offline.DownloadService.sendPauseDownloads(
                        context, DownloadService::class.java, false)
                }) { Text("暂停") }
                TextButton(onClick = {
                    androidx.media3.exoplayer.offline.DownloadService.sendResumeDownloads(
                        context, DownloadService::class.java, false)
                }) { Text("继续") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "进行中", "已完成", "失败").forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
            }
        }
        Spacer(Modifier.height(8.dp))

        if (visibleDownloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有下载内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleDownloads) { d ->
                    val key = d.request.id
                    val (bvid, cid) = Downloads.parseKey(key)
                    val meta = runCatching { org.json.JSONObject(String(d.request.data, Charsets.UTF_8)) }.getOrNull()
                    val title = meta?.optString("title").takeUnless { it.isNullOrEmpty() } ?: key
                    val author = meta?.optString("author").orEmpty()
                    val cover = meta?.optString("cover").orEmpty()
                    val percent = when (d.state) {
                        Download.STATE_DOWNLOADING ->
                            if (d.contentLength > 0) (d.bytesDownloaded * 100 / d.contentLength).toInt() else 0
                        else -> 0
                    }
                    val stateText = when (d.state) {
                        Download.STATE_COMPLETED -> "已下载 ✓"
                        Download.STATE_DOWNLOADING -> "下载中 $percent%"
                        Download.STATE_QUEUED -> "排队中"
                        Download.STATE_STOPPED -> "已暂停"
                        Download.STATE_FAILED -> "失败"
                        Download.STATE_REMOVING -> "删除中"
                        Download.STATE_RESTARTING -> "重启中"
                        else -> "未知"
                    }
                    Surface(
                        color = if (d.request.id in selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (d.request.id in selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                when {
                                    selectionMode -> Modifier.clickable {
                                        val k = d.request.id
                                        selected = if (k in selected) selected - k else selected + k
                                    }
                                    d.state == Download.STATE_COMPLETED -> Modifier.clickable {
                                        onPlayDownload(bvid, cid, title, author, cover)
                                    }
                                    else -> Modifier
                                }
                            ),
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selectionMode) {
                                Checkbox(checked = d.request.id in selected, onCheckedChange = null)
                                Spacer(Modifier.width(4.dp))
                            }
                            if (cover.isNotEmpty()) {
                                AsyncImage(
                                    model = cover, contentDescription = null,
                                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    author + " · " + stateText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        d.state == Download.STATE_FAILED -> MaterialTheme.colorScheme.error
                                        d.state == Download.STATE_COMPLETED -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                )
                                if (d.state == Download.STATE_FAILED) {
                                    lastErrors[key]?.let { reason ->
                                        Text(
                                            reason,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (!selectionMode) {
                                when (d.state) {
                                    Download.STATE_STOPPED -> IconButton(onClick = {
                                        androidx.media3.exoplayer.offline.DownloadService.sendResumeDownloads(
                                            context, DownloadService::class.java, false)
                                    }) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "继续下载")
                                    }
                                    Download.STATE_FAILED -> IconButton(onClick = {
                                        vm.retry(context, d)
                                    }) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = "重试下载")
                                    }
                                    Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> IconButton(onClick = {
                                        androidx.media3.exoplayer.offline.DownloadService.sendPauseDownloads(
                                            context, DownloadService::class.java, false)
                                    }) {
                                        Icon(Icons.Rounded.Pause, contentDescription = "暂停下载")
                                    }
                                    else -> {}
                                }
                                IconButton(onClick = { vm.remove(context, key) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "删除下载",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
