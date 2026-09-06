package com.galigalimusic.ui.screens

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.galigalimusic.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val albumArt: String?,
)

/** 扫描 MediaStore 音频库；查询失败时返回空列表 + 错误文案 */
private fun scanLocalAudio(context: android.content.Context): Pair<List<LocalTrack>, String?> {
    return try {
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val list = mutableListOf<LocalTrack>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            proj,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000",
            null,
            // 注意：EMUI 的 MediaProvider 不认 "COLLATE LOCALIZED"（Invalid token），用普通排序
            "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(iId)
                )
                list += LocalTrack(
                    uri = uri.toString(),
                    title = c.getString(iTitle) ?: "未知标题",
                    artist = (c.getString(iArtist) ?: "").ifEmpty { "未知艺术家" },
                    durationMs = c.getLong(iDur),
                    albumArt = Uri.parse("content://media/external/audio/albumart")
                        .buildUpon().appendPath(c.getLong(iAlbum).toString()).toString(),
                )
            }
        }
        list to null
    } catch (t: Throwable) {
        emptyList<LocalTrack>() to (t.message ?: t.javaClass.simpleName)
    }
}

/**
 * 本地音乐：扫描 MediaStore 音频库，点击即用统一播放器播放
 * （倍速/AB循环/定时等播放器能力全部生效；歌词走 LRCLIB 标题匹配）。
 */
@Composable
fun LocalMusicScreen(
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<LocalTrack>?>(null) }
    var granted by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val notifPermission = if (android.os.Build.VERSION.SDK_INT >= 33)
        android.Manifest.permission.READ_MEDIA_AUDIO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE

    fun load() {
        scope.launch {
            val pair: Pair<List<LocalTrack>, String?> = withContext(Dispatchers.IO) { scanLocalAudio(context) }
            tracks = pair.first
            scanError = pair.second
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, notifPermission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            granted = true
            load()
        } else {
            activity?.let {
                androidx.core.app.ActivityCompat.requestPermissions(it, arrayOf(notifPermission), 3001)
            }
            // 用户在系统弹窗里点授权后这里轮询感知（不引入 lifecycle-compose 依赖）
            repeat(30) {
                kotlinx.coroutines.delay(1000)
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, notifPermission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    granted = true
                    load()
                    return@LaunchedEffect
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text("本地音乐", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        when {
            !granted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("需要音频文件访问权限", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            tracks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            tracks!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("没有扫描到音频文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (scanError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            scanError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tracks!!) { t ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playerVm.playExternal(
                                    uri = t.uri, title = t.title, author = t.artist,
                                    coverUrl = t.albumArt, sourceTag = "local",
                                )
                            },
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (t.albumArt != null) {
                                AsyncImage(
                                    model = t.albumArt, contentDescription = null,
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.MusicNote, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    t.artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            val m = t.durationMs / 60000
                            val s = (t.durationMs / 1000) % 60
                            Text(
                                "$m:%02d".format(s),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}
