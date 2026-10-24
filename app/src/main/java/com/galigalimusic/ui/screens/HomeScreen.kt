package com.galigalimusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.galigalimusic.ui.theme.ThemeEngine
import com.galigalimusic.viewmodel.PlayerViewModel
import java.util.concurrent.TimeUnit
import com.galigalimusic.ui.formatDuration

/**
 * 首页（方案 B 内容流）：问候 + 继续听 hero 卡 + 快捷开始 + 最近播放。
 */
@Composable
fun HomeScreen(
    playerVm: PlayerViewModel,
    onNavigate: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    val history by playerVm.history.collectAsState()
    val accent by ThemeEngine.accent.collectAsState()
    val current by playerVm.current.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        // 问候语
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        Text(
            when (hour) {
                in 5..11 -> "早上好"
                in 12..17 -> "下午好"
                else -> "晚上好"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        val subtitle = if (accent != null && current != null) {
            "动态取色自：《${current?.title}》"
        } else {
            "把 B 站当曲库用"
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))

        // 继续听 hero 卡
        val latest = history.firstOrNull()
        if (latest != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (playerVm.resolving.value) {
                            onToast("正在解析，稍等…")
                        } else {
                            playerVm.playFromHistory(latest)
                        }
                    },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = latest.coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("继续听", style = MaterialTheme.typography.labelSmall)
                        Text(
                            latest.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "上次播放 · ${relativeTime(latest.lastPlayed)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    FilledIconButton(
                        onClick = { playerVm.playFromHistory(latest) },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "续播")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 快捷开始
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("快捷开始", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickCard(Icons.Rounded.Search, "B站搜索", Modifier.weight(1f)) { onNavigate("search") }
            QuickCard(Icons.Rounded.History, "历史", Modifier.weight(1f)) { onNavigate("history") }
            QuickCard(Icons.Rounded.Folder, "本地", Modifier.weight(1f)) { onNavigate("local") }
            QuickCard(Icons.Rounded.CloudQueue, "AList", Modifier.weight(1f)) { onNavigate("alist") }
        }
        Spacer(Modifier.height(20.dp))

        // 最近播放
        if (history.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近播放", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onNavigate("history") }) { Text("全部") }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    history.drop(1).take(4).forEach { item ->
                        HistoryRow(item) { playerVm.playFromHistory(item) }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun QuickCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp),
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun HistoryRow(item: com.galigalimusic.data.local.HistoryEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${item.author} · ${formatDuration(item.durationSec)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 分钟级相对时间 */
fun relativeTime(ts: Long): String {
    val diffMin = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - ts)
    return when {
        diffMin < 1 -> "刚刚"
        diffMin < 60 -> "$diffMin 分钟前"
        diffMin < 60 * 24 -> "${TimeUnit.MINUTES.toHours(diffMin)} 小时前"
        else -> "${TimeUnit.MINUTES.toHours(diffMin) / 24} 天前"
    }
}
