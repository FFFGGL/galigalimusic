package com.galigalimusic.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.HistoryEntity
import com.galigalimusic.viewmodel.HistoryViewModel
import com.galigalimusic.viewmodel.PlayerViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    db: AppDatabase,
    playerVm: PlayerViewModel? = null,
    onOpenDownloads: () -> Unit = {},
) {
    val vm: HistoryViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(db) as T
    })
    val history by vm.history.collectAsState()
    val stats by vm.stats.collectAsState()

    // 多选删除：长按条目进入选择模式
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    fun keyOf(item: HistoryEntity) = "${item.bvid}|${item.cid}"

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("历史", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (selectionMode) {
                TextButton(onClick = {
                    selected = if (selected.size == history.size) emptySet()
                    else history.map { keyOf(it) }.toSet()
                }) { Text(if (selected.size == history.size) "全不选" else "全选") }
                TextButton(onClick = {
                    selected.forEach { k ->
                        val (bvid, cid) = k.split("|", limit = 2)
                        vm.deleteOne(bvid, cid.toLongOrNull() ?: 0L)
                    }
                    selected = emptySet()
                    selectionMode = false
                }, enabled = selected.isNotEmpty()) {
                    Text("删除(${selected.size})")
                }
                TextButton(onClick = { selectionMode = false; selected = emptySet() }) { Text("完成") }
            } else {
                TextButton(onClick = onOpenDownloads) { Text("下载") }
                TextButton(onClick = { vm.clear() }, enabled = history.isNotEmpty()) { Text("清空") }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有播放记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            StatsCard(stats)
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history) { item ->
                    val key = keyOf(item)
                    val isSelected = key in selected
                    // 最近优先；点击续播（重新解析播放地址），长按多选删除
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = {
                                if (selectionMode) {
                                    selected = if (isSelected) selected - key else selected + key
                                } else {
                                    playerVm?.playFromHistory(item)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selected = setOf(key)
                                }
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selectionMode) {
                                Checkbox(checked = isSelected, onCheckedChange = null)
                                Spacer(Modifier.width(4.dp))
                            }
                            AsyncImage(
                                model = item.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.author} · ${relativeTime(item.lastPlayed)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                if (item.listenedSec > 0) {
                                    Text(
                                        "听过 ${humanDuration(item.listenedSec)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (item.bvid == playerVm?.current?.value?.bvid &&
                                item.cid == playerVm?.current?.value?.cid
                            ) {
                                Text(
                                    "播放中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** 秒数转人类可读时长：3小时25分 / 47分钟 */
private fun humanDuration(totalSec: Long): String {
    val sec = totalSec.coerceAtLeast(0)
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}小时${m}分"
        h > 0 -> "${h}小时"
        m > 0 -> "${m}分钟"
        else -> "不到1分钟"
    }
}

/** 播放统计卡片：总时长 / 本周 / 曲目数 + 常听 TOP5 */
@Composable
private fun StatsCard(stats: HistoryViewModel.Stats) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatCell("总时长", humanDuration(stats.totalSec), Modifier.weight(1f))
                StatCell("本周", humanDuration(stats.weekSec), Modifier.weight(1f))
                StatCell("曲目", "${stats.trackCount}", Modifier.weight(1f))
            }
            if (stats.top.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "常听 TOP5",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                stats.top.forEach { t ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            humanDuration(t.listenedSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
