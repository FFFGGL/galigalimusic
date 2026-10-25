package com.galigalimusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.galigalimusic.data.local.SubscriptionEntity
import com.galigalimusic.viewmodel.SubscriptionViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 追更订阅列表：以多P合集（有声书）为单位，定时检查分P更新并通知。
 * 订阅入口在播放页顶栏铃铛按钮。
 */
@Composable
fun SubscriptionsScreen(
    vm: SubscriptionViewModel,
    onBack: () -> Unit,
    onPlay: (SubscriptionEntity) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val subs by vm.subscriptions.collectAsState()
    val checking by vm.checking.collectAsState()
    val checkMsg by vm.checkMsg.collectAsState()

    // Android 13+ 通知权限：进页面顺势请求（追更提醒需要通知）
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2002
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(cs.background)) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = cs.onSurface,
                )
            }
            Text(
                "追更",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).padding(end = 4.dp),
                    strokeWidth = 2.dp,
                    color = cs.primary,
                )
            } else {
                IconButton(onClick = { vm.checkNow() }, enabled = subs.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "检查更新",
                        tint = if (subs.isEmpty()) cs.onSurfaceVariant.copy(alpha = 0.4f) else cs.primary,
                    )
                }
            }
        }

        if (checkMsg != null) {
            Surface(
                color = cs.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        checkMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.consumeCheckMsg() }) { Text("知道了") }
                }
            }
        }

        if (subs.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有追更订阅", color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在播放页点右上角铃铛，即可订阅当前合集",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(subs, key = { it.bvid }) { sub ->
                    SubscriptionRow(
                        sub = sub,
                        onClick = { onPlay(sub) },
                        onDelete = { vm.unsubscribe(sub.bvid) },
                    )
                }
            }
            Text(
                "每 6 小时自动检查一次（仅联网时）",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
    }
}

@Composable
private fun SubscriptionRow(
    sub: SubscriptionEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = sub.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                sub.title,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${sub.author} · 已追 ${sub.pageCount} 集",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub.lastCheckedAt > 0) {
                Text(
                    "上次检查 ${formatCheckedAt(sub.lastCheckedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "取消追更",
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

private fun formatCheckedAt(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000 -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ms))
    }
}
