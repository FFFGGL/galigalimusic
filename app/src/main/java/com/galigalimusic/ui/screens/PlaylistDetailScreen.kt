package com.galigalimusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.PlaylistSongEntity
import com.galigalimusic.data.remote.BilibiliApi
import com.galigalimusic.data.repository.PlaylistRepository
import com.galigalimusic.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistDetailViewModel(
    repo: PlaylistRepository,
    playlistId: String,
) : ViewModel() {
    val songs: StateFlow<List<PlaylistSongEntity>> = repo.observeSongs(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun remove(repo: PlaylistRepository, playlistId: String, item: PlaylistSongEntity) {
        viewModelScope.launch { repo.removeSong(playlistId, item.bvid, item.cid) }
    }
}

@Composable
fun PlaylistDetailScreen(
    db: AppDatabase,
    playlistId: String,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val repo = remember { PlaylistRepository(db) }
    val vm: PlaylistDetailViewModel = viewModel(
        key = playlistId,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlaylistDetailViewModel(repo, playlistId) as T
        },
    )
    val songs by vm.songs.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val title by androidx.compose.runtime.produceState("歌单", playlistId) {
        value = db.playlistDao().getById(playlistId)?.title ?: "歌单"
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${songs.size} 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    val first = songs.firstOrNull()
                    if (first == null) {
                        onToast("歌单是空的")
                    } else {
                        playerVm.playPlaylistTrack(
                            first.bvid, first.cid, first.title,
                            first.author, first.coverUrl, first.durationSec,
                        )
                    }
                },
            ) { Text("▶ 播放全部") }
        }
        Spacer(Modifier.height(10.dp))

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("歌单是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(songs) { item ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playerVm.playPlaylistTrack(
                                    item.bvid, item.cid, item.title,
                                    item.author, item.coverUrl, item.durationSec,
                                )
                            },
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = item.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.author} · ${formatDuration(item.durationSec)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            var downloading by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = {
                                    if (downloading) return@IconButton
                                    downloading = true
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val result = runCatching {
                                            val realCid = if (item.cid > 0) item.cid
                                                else com.galigalimusic.data.remote.BilibiliApi.getCid(item.bvid)
                                            val url = com.galigalimusic.data.remote.BilibiliApi.getAudioUrl(
                                                item.bvid, realCid,
                                            )
                                            com.galigalimusic.player.Downloads.start(
                                                context, item.bvid, realCid, item.title,
                                                item.author, item.coverUrl, url,
                                            )
                                        }
                                        downloading = false
                                        result.onFailure {
                                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                onToast("下载失败：${it.message ?: "解析失败"}")
                                            }
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.CloudDownload,
                                    contentDescription = "离线下载",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch { repo.removeSong(playlistId, item.bvid, item.cid) }
                            }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "移出歌单",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
