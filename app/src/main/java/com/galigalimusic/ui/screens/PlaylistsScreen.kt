package com.galigalimusic.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.PlaylistEntity
import com.galigalimusic.data.remote.BiliSession
import com.galigalimusic.data.remote.BilibiliApi
import com.galigalimusic.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val repo: PlaylistRepository) : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>> = repo.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts

    init {
        viewModelScope.launch {
            repo.observeCounts().collect { rows ->
                _counts.value = rows.associate { it.playlistId to it.cnt }
            }
        }
    }

    fun create(title: String, onDone: () -> Unit) {
        if (title.isBlank()) return
        viewModelScope.launch { repo.create(title); onDone() }
    }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }

    private val _syncing = MutableStateFlow<Set<String>>(emptySet())
    val syncing: StateFlow<Set<String>> = _syncing

    fun syncFav(playlist: PlaylistEntity, onDone: (Int) -> Unit, onError: (String) -> Unit) {
        val mediaId = playlist.favMediaId ?: return
        if (playlist.id in _syncing.value) return
        viewModelScope.launch {
            _syncing.value += playlist.id
            runCatching { repo.importFav(mediaId, playlist.title) }
                .onSuccess { (_, added) -> onDone(added) }
                .onFailure { onError(it.message ?: "同步失败") }
            _syncing.value -= playlist.id
        }
    }

    // ===== B 站收藏夹导入 =====
    sealed interface ImportState {
        data object Idle : ImportState
        data object LoadingFolders : ImportState
        data class Folders(val folders: List<BilibiliApi.FavFolder>) : ImportState
        data class Importing(val folder: String) : ImportState
        data class Done(val count: Int) : ImportState
        data class Error(val message: String) : ImportState
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    fun loadFolders() {
        val mid = BiliSession.mid.value.toLongOrNull()
        if (mid == null || mid <= 0L) {
            _importState.value = ImportState.Error("请先在设置中登录 B 站")
            return
        }
        viewModelScope.launch {
            _importState.value = ImportState.LoadingFolders
            runCatching { BilibiliApi.getFavFolders(mid) }
                .onSuccess { _importState.value = ImportState.Folders(it) }
                .onFailure { _importState.value = ImportState.Error("获取收藏夹失败: ${it.message}") }
        }
    }

    fun importFav(folder: BilibiliApi.FavFolder) {
        viewModelScope.launch {
            _importState.value = ImportState.Importing(folder.title)
            runCatching { repo.importFav(folder.id, folder.title) }
                .onSuccess { (_, n) -> _importState.value = ImportState.Done(n) }
                .onFailure { _importState.value = ImportState.Error("导入失败: ${it.message}") }
        }
    }

    fun resetImport() {
        _importState.value = ImportState.Idle
    }

    // ===== 备份导出 / 导入 =====
    fun exportJson(onReady: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.exportJson() }
                .onSuccess(onReady)
                .onFailure { onError(it.message ?: "导出失败") }
        }
    }

    fun importJson(text: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.importJson(text) }
                .onSuccess { (pl, songs) ->
                    onDone(
                        if (pl == 0 && songs == 0) "没有新内容（可能全部已存在）"
                        else "导入完成：$pl 个歌单 / $songs 首歌曲 ✓"
                    )
                }
                .onFailure { onDone("导入失败：${it.message ?: "文件格式不对"}") }
        }
    }
}

@Composable
fun PlaylistsScreen(
    db: AppDatabase,
    onOpenPlaylist: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    val repo = remember { PlaylistRepository(db) }
    val vm: PlaylistsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlaylistsViewModel(repo) as T
    })
    val playlists by vm.playlists.collectAsState()
    val counts by vm.counts.collectAsState()
    val importState by vm.importState.collectAsState()
    val syncing by vm.syncing.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    // 备份导出/导入：系统文件选择器（SAF），无需存储权限
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExport
        if (uri != null && json != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }.onSuccess { onToast("已导出备份") }
                    .onFailure { onToast("导出失败：${it.message}") }
            }
        }
        pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }.getOrNull()
                if (text.isNullOrEmpty()) onToast("读取文件失败")
                else vm.importJson(text, onToast)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("歌单", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                vm.exportJson(
                    onReady = { json ->
                        pendingExport = json
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                            .format(java.util.Date())
                        exportLauncher.launch("galigali-playlists-$stamp.json")
                    },
                    onError = onToast,
                )
            }) {
                Icon(Icons.Rounded.FileDownload, contentDescription = "导出备份")
            }
            IconButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain"))
            }) {
                Icon(Icons.Rounded.FileUpload, contentDescription = "导入备份")
            }
            IconButton(onClick = { vm.resetImport(); showImport = true }) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = "导入 B 站收藏夹")
            }
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "新建歌单")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("还没有歌单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "右上角新建，或导入 B 站收藏夹",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlists) { pl ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPlaylist(pl.id) },
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(48.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    "${counts[pl.id] ?: 0} 首",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (pl.favMediaId != null) {
                                    Text("B站收藏夹 · ${if (pl.favSyncedAt != null) "已同步" else "未同步"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (pl.favMediaId != null) {
                                IconButton(
                                    onClick = { vm.syncFav(pl, { added -> onToast(if (added == 0) "「${pl.title}」已是最新" else "「${pl.title}」新增 $added 首") }, onToast) },
                                    enabled = pl.id !in syncing,
                                ) {
                                    if (pl.id in syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Rounded.Sync, contentDescription = "同步收藏夹")
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (showCreate) {
        TitleInputDialog(
            title = "新建歌单",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                vm.create(name) { showCreate = false }
            },
        )
    }

    if (showImport) {
        ImportFavDialog(
            state = importState,
            onDismiss = { showImport = false; vm.resetImport() },
            onLoadFolders = { vm.loadFolders() },
            onImport = { vm.importFav(it) },
        )
    }
}

@Composable
fun TitleInputDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("歌单名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** B 站收藏夹导入对话框：列收藏夹 → 点选导入 */
@Composable
private fun ImportFavDialog(
    state: PlaylistsViewModel.ImportState,
    onDismiss: () -> Unit,
    onLoadFolders: () -> Unit,
    onImport: (BilibiliApi.FavFolder) -> Unit,
) {
    LaunchedEffect(Unit) { onLoadFolders() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 B 站收藏夹") },
        text = {
            when (val s = state) {
                is PlaylistsViewModel.ImportState.LoadingFolders ->
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                is PlaylistsViewModel.ImportState.Folders -> {
                    if (s.folders.isEmpty()) {
                        Text("该账号没有创建收藏夹")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.folders.forEach { folder ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onImport(folder) },
                                ) {
                                    Row(modifier = Modifier.padding(12.dp)) {
                                        Text(folder.title, modifier = Modifier.weight(1f), maxLines = 1)
                                        Text(
                                            "${folder.mediaCount} 首",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is PlaylistsViewModel.ImportState.Importing -> {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("正在导入「${s.folder}」…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                is PlaylistsViewModel.ImportState.Done -> {
                    Text(
                        if (s.count == 0) "该收藏夹没有可导入的视频（可能全部失效或非视频内容）"
                        else "导入完成，共 ${s.count} 首 ✓"
                    )
                }
                is PlaylistsViewModel.ImportState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                else -> {}
            }
        },
        confirmButton = {
            when (val s = state) {
                is PlaylistsViewModel.ImportState.Done ->
                    TextButton(onClick = onDismiss) { Text("完成") }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
