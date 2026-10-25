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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galigalimusic.data.remote.BilibiliApi
import com.galigalimusic.ui.components.LyricScroll
import com.galigalimusic.ui.components.LyricTwoLine
import com.galigalimusic.ui.formatMs
import com.galigalimusic.viewmodel.PlayerViewModel
import com.galigalimusic.whisper.WhisperLocal

/**
 * 整页歌词（全屏沉浸）：点击播放页歌词区进入。
 *
 * 双行卡拉OK（默认）当前句大字逐字高亮 + 下一句预览，可切回整屏列表；
 * 顶栏搜索可在页内按关键词定位歌词句并跳转；字号四档全局持久化。
 * 「校正」可逐句修改文字/起止时间（写入独立 EDITED 缓存，随时恢复原始）。
 * 底部保留精简控制（模式/上一首/播放/下一首 + 进度 + 偏移）。
 */
@Composable
fun LyricsScreen(
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
) {
    val current by playerVm.current.collectAsState()
    val playback by playerVm.playback.collectAsState()
    val lyrics by playerVm.lyrics.collectAsState()
    val lyricsState by playerVm.lyricsState.collectAsState()
    val playMode by playerVm.playMode.collectAsState()
    val lyricOffset by playerVm.lyricOffsetMs.collectAsState()
    val fontScale by playerVm.lyricFontScale.collectAsState()
    val lyricsSource by playerVm.lyricsSource.collectAsState()

    val cs = MaterialTheme.colorScheme
    val cur = current

    // 双行/列表模式（默认双行）与页内搜索
    var twoLineMode by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // B5：正在校正的行下标（null = 关对话框）
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val searchResults: List<BilibiliApi.LrcLine> =
        if (searching && query.isNotBlank()) {
            lyrics.filter { it.text.contains(query, ignoreCase = true) }.take(80)
        } else emptyList()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(cs.primaryContainer.copy(alpha = 0.9f), cs.surface, cs.background)
                )
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：返回 + 曲名/分P（搜索时换成搜索框）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (searching) {
                        searching = false
                        query = ""
                    } else onBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = cs.onSurface,
                    )
                }
                if (searching) {
                    val focusRequester = remember { FocusRequester() }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索歌词内容…") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空", tint = cs.onSurface)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            cur?.title ?: "歌词",
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!cur?.pageLabel.isNullOrEmpty()) {
                            Text(
                                cur!!.pageLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.primary,
                            )
                        }
                    }
                    // 页内歌词搜索
                    IconButton(
                        onClick = { searching = true },
                        enabled = lyrics.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = "搜索歌词",
                            tint = if (lyrics.isEmpty()) cs.onSurfaceVariant.copy(alpha = 0.4f)
                            else cs.onSurface,
                        )
                    }
                    Spacer(Modifier.width(48.dp))
                }
            }

            // 主体：搜索结果 / 歌词（双行或列表）
            when {
                searching && cur != null -> {
                    if (query.isBlank()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("输入关键词，在字幕里定位", color = cs.onSurfaceVariant)
                        }
                    } else if (searchResults.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("没有匹配「$query」的歌词", color = cs.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(searchResults) { line ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            playerVm.seekTo(line.startMs)
                                            searching = false
                                            query = ""
                                        }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        formatMs(line.startMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cs.primary,
                                        modifier = Modifier.width(56.dp),
                                    )
                                    Text(
                                        line.text,
                                        color = cs.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
                cur == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("还没有在播放", color = cs.onSurfaceVariant)
                }
                lyricsState == PlayerViewModel.LyricsState.LOADING ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("正在获取 AI 字幕…", color = cs.onSurfaceVariant)
                    }
                lyricsState == PlayerViewModel.LyricsState.NONE || lyrics.isEmpty() ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("暂无字幕", color = cs.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            val rState by playerVm.recognizeState.collectAsState()
                            val rProgress by playerVm.recognizeProgress.collectAsState()
                            val rMsg by playerVm.recognizeMsg.collectAsState()
                            RecognizeButton(
                                state = rState,
                                progress = rProgress,
                                msg = rMsg,
                                onClick = { playerVm.recognizeLocally() },
                                onCancel = { playerVm.cancelRecognize() },
                            )
                        }
                    }
                playerVm.recognizeState.value == PlayerViewModel.RecognizeState.TRANSCRIBING ->
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // 识别中歌词已逐段上屏：正常渲染，底部叠进度条
                        if (twoLineMode) {
                            LyricTwoLine(
                                lyrics = lyrics,
                                positionMs = playback.positionMs + lyricOffset,
                                activeColor = cs.primary,
                                idleColor = cs.onSurfaceVariant,
                                activeSize = 24.sp * fontScale,
                                idleSize = 15.sp * fontScale,
                                onSeek = { playerVm.seekTo(it) },
                                modifier = Modifier.fillMaxSize().padding(bottom = 56.dp),
                            )
                        } else {
                            LyricScroll(
                                lyrics = lyrics,
                                positionMs = playback.positionMs + lyricOffset,
                                activeColor = cs.primary,
                                idleColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
                                activeSize = 22.sp * fontScale,
                                idleSize = 16.sp * fontScale,
                                onSeek = { playerVm.seekTo(it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                            LinearProgressIndicator(
                                progress = { playerVm.recognizeProgress.value / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = cs.primary,
                            )
                            Text(
                                "🎤 分块识别中 ${playerVm.recognizeProgress.value}% · 歌词已逐段上屏",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                twoLineMode -> LyricTwoLine(
                    lyrics = lyrics,
                    positionMs = playback.positionMs + lyricOffset,
                    activeColor = cs.primary,
                    idleColor = cs.onSurfaceVariant,
                    activeSize = 24.sp * fontScale,
                    idleSize = 15.sp * fontScale,
                    onSeek = { playerVm.seekTo(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                else -> LyricScroll(
                    lyrics = lyrics,
                    positionMs = playback.positionMs + lyricOffset,
                    activeColor = cs.primary,
                    idleColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    activeSize = 22.sp * fontScale,
                    idleSize = 16.sp * fontScale,
                    onSeek = { playerVm.seekTo(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }

            // 底部精简控制
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                var seeking by remember {
                    mutableStateOf<Float?>(null)
                }
                val sliderValue = seeking
                    ?: if (playback.durationMs > 0) {
                        (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
                    } else 0f
                Slider(
                    value = sliderValue,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let { playerVm.seekTo((it * playback.durationMs).toLong()) }
                        seeking = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = playback.durationMs > 0,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatMs(playback.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "-" + formatMs((playback.durationMs - playback.positionMs).coerceAtLeast(0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                // 显示模式 + 字号
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { twoLineMode = !twoLineMode }, enabled = lyrics.isNotEmpty()) {
                        Text(if (twoLineMode) "列表" else "双行")
                    }
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    TextButton(onClick = { playerVm.cycleLyricFontScale() }) {
                        Text("字号 ${playerVm.lyricFontScaleLabel()}")
                    }
                    if (lyrics.isNotEmpty()) {
                        Text(
                            "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                        // B5：打开校正对话框，默认定位到当前播放到的行
                        TextButton(
                            onClick = {
                                val posMs = playback.positionMs + lyricOffset
                                editingIndex =
                                    lyrics.indexOfLast { it.startMs <= posMs }.coerceAtLeast(0)
                            },
                        ) { Text("校正") }
                    }
                    if (lyricsSource == WhisperLocal.LyricSource.EDITED) {
                        Text(
                            "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                        TextButton(onClick = { playerVm.restoreOriginalLyrics() }) { Text("恢复原始") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { playerVm.adjustLyricOffset(-500) }) { Text("−0.5s") }
                    Text(
                        "偏移 ${if (lyricOffset >= 0) "+" else ""}${lyricOffset / 1000.0}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    TextButton(onClick = { playerVm.adjustLyricOffset(500) }) { Text("+0.5s") }
                    TextButton(onClick = { playerVm.resetLyricOffset() }) { Text("重置") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { playerVm.cyclePlayMode() }) {
                        Icon(
                            imageVector = when (playMode) {
                                PlayerViewModel.PlayMode.REPEAT_ALL -> Icons.Rounded.Repeat
                                PlayerViewModel.PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
                                PlayerViewModel.PlayMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
                            },
                            contentDescription = "播放模式",
                            tint = if (playMode == PlayerViewModel.PlayMode.REPEAT_ALL)
                                cs.onSurfaceVariant else cs.primary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(onClick = { playerVm.skipPrevious() }) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "上一集",
                            tint = cs.onSurface,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = cs.primary,
                        contentColor = cs.onPrimary,
                        modifier = Modifier.size(56.dp),
                    ) {
                        IconButton(onClick = { playerVm.toggle() }) {
                            Icon(
                                if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playback.isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(onClick = { playerVm.skipNext() }) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "下一集",
                            tint = cs.onSurface,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Spacer(Modifier.width(48.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        // B5：歌词校正对话框
        editingIndex?.let { idx ->
            LyricEditDialog(
                initialIndex = idx,
                lyrics = lyrics,
                positionMs = playback.positionMs,
                onDismiss = { editingIndex = null },
                onSave = { index, text, startMs, endMs ->
                    playerVm.saveLyricEdit(index, text, startMs, endMs)
                    editingIndex = null
                },
            )
        }
    }
}

/**
 * B5 歌词校正对话框：改文字 + 起止时间微调（±0.5s / 当前位置）。
 * 可翻行选择目标句；保存后整份写入 EDITED 缓存并即时生效。
 */
@Composable
private fun LyricEditDialog(
    initialIndex: Int,
    lyrics: List<BilibiliApi.LrcLine>,
    positionMs: Long,
    onDismiss: () -> Unit,
    onSave: (index: Int, text: String, startMs: Long, endMs: Long) -> Unit,
) {
    var index by remember { mutableStateOf(initialIndex.coerceIn(0, (lyrics.size - 1).coerceAtLeast(0))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校正第 ${index + 1} 句") },
        text = {
            // 换行时重置全部编辑状态
            key(index) {
                val line = lyrics.getOrNull(index)
                var text by remember { mutableStateOf(line?.text ?: "") }
                var startMs by remember { mutableStateOf(line?.startMs ?: 0L) }
                var endMs by remember { mutableStateOf(line?.endMs ?: 0L) }
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("歌词文字") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { if (index > 0) index-- },
                            enabled = index > 0,
                        ) { Text("上一句") }
                        Text(
                            "${index + 1}/${lyrics.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                        TextButton(
                            onClick = { if (index < lyrics.lastIndex) index++ },
                            enabled = index < lyrics.lastIndex,
                        ) { Text("下一句") }
                    }
                    LyricTimeRow(
                        label = "开始",
                        timeMs = startMs,
                        onDelta = { d -> startMs = (startMs + d).coerceAtLeast(0) },
                        onSetNow = { startMs = positionMs },
                    )
                    LyricTimeRow(
                        label = "结束",
                        timeMs = endMs,
                        onDelta = { d -> endMs = (endMs + d).coerceAtLeast(0) },
                        onSetNow = { endMs = positionMs },
                    )
                    Text(
                        "校正结果只保存在本机，可随时「恢复原始」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 保存按钮放这里（dialog 的 confirmButton 拿不到 key 内的状态）
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { onSave(index, text, startMs, endMs) },
                            enabled = text.isNotBlank(),
                        ) { Text("保存") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 时间微调行：标签 + −0.5s + 时间 + +0.5s + 当前位置 */
@Composable
private fun LyricTimeRow(
    label: String,
    timeMs: Long,
    onDelta: (Long) -> Unit,
    onSetNow: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
        TextButton(onClick = { onDelta(-500) }) { Text("−") }
        Text(
            formatMs(timeMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
        TextButton(onClick = { onDelta(500) }) { Text("+") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSetNow) { Text("当前位置") }
    }
}
