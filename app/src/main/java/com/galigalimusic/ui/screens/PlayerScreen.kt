package com.galigalimusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import coil.compose.AsyncImage
import com.galigalimusic.viewmodel.PlayerViewModel

/**
 * 全屏播放页（方案 B-②）：强调色氛围背景 + 28dp 圆角封面 + 歌词区 +
 * 进度/控制 + 底部常驻「声音增强」状态条。
 */
@Composable
fun PlayerScreen(
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenLyrics: () -> Unit = {},
) {
    val current by playerVm.current.collectAsState()
    val playback by playerVm.playback.collectAsState()
    val resolving by playerVm.resolving.collectAsState()
    val history by playerVm.history.collectAsState()
    var boostOn by remember { androidx.compose.runtime.mutableStateOf(playerVm.volumeBoostEnabled()) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    val cur = current
    val cs = MaterialTheme.colorScheme
    var showSleepTimer by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        cs.primaryContainer.copy(alpha = 0.9f),
                        cs.surface,
                        cs.background,
                    )
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶栏：返回 + 播放模式（循环→随机→单曲循环）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = cs.onSurface,
                    )
                }
                Spacer(Modifier.weight(1f))
                val sleepRemaining by playerVm.sleepRemainingSec.collectAsState()
                IconButton(onClick = { showSleepTimer = true }) {
                    Icon(
                        Icons.Rounded.Timer,
                        contentDescription = "定时关闭",
                        tint = if (sleepRemaining > 0) cs.primary else cs.onSurfaceVariant,
                    )
                }
                val playMode by playerVm.playMode.collectAsState()
                var downloadMessage by remember { mutableStateOf("") }
                // 外部音源（本地/AList）没有 B 站直链，无离线下载概念
                if (cur?.bvid?.startsWith("ext:") != true) {
                    IconButton(onClick = { playerVm.downloadCurrent { downloadMessage = it } }) {
                        Text("↓", color = cs.primary, style = MaterialTheme.typography.titleMedium)
                    }
                }
                IconButton(onClick = { playerVm.cyclePlayMode() }) {
                    Icon(
                        imageVector = when (playMode) {
                            com.galigalimusic.viewmodel.PlayerViewModel.PlayMode.REPEAT_ALL ->
                                Icons.Rounded.Repeat
                            com.galigalimusic.viewmodel.PlayerViewModel.PlayMode.SHUFFLE ->
                                Icons.Rounded.Shuffle
                            com.galigalimusic.viewmodel.PlayerViewModel.PlayMode.REPEAT_ONE ->
                                Icons.Rounded.RepeatOne
                        },
                        contentDescription = when (playMode) {
                            com.galigalimusic.viewmodel.PlayerViewModel.PlayMode.REPEAT_ALL -> "列表循环"
                            com.galigalimusic.viewmodel.PlayerViewModel.PlayMode.SHUFFLE -> "随机播放"
                            com.galigalimusic.viewmodel.PlayerViewModel.PlayMode.REPEAT_ONE -> "单曲循环"
                        },
                        tint = if (playMode == com.galigalimusic.viewmodel.PlayerViewModel.PlayMode.REPEAT_ALL)
                            cs.onSurfaceVariant else cs.primary,
                    )
                }
            }

            if (cur == null) {
                Spacer(Modifier.height(120.dp))
                Text("还没有在播放", color = cs.onSurfaceVariant)
                Text(
                    "去搜索或从历史里挑一首吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(8.dp))

            // 封面
            Box {
                AsyncImage(
                    model = cur.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(272.dp)
                        .clip(RoundedCornerShape(28.dp)),
                    contentScale = ContentScale.Crop,
                )
                if (resolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(44.dp),
                        color = cs.primary,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                cur.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = cs.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                cur.author,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )

            // 当前分P 醒目提示 + 倍速 + 选集入口
            val pages by playerVm.pages.collectAsState()
            val speed by playerVm.speed.collectAsState()
            var showPages by remember { mutableStateOf(false) }
            var showSleepTimer by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                if (cur.pageLabel.isNotEmpty()) {
                    Surface(
                        color = cs.primary,
                        contentColor = cs.onPrimary,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            "▶ 正在播放 · ${cur.pageLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                Surface(
                    color = cs.secondaryContainer,
                    contentColor = cs.onSecondaryContainer,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { playerVm.cycleSpeed() },
                ) {
                    Text(
                        "${speed}x",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                if (pages.size > 1) {
                    Surface(
                        color = cs.secondaryContainer,
                        contentColor = cs.onSecondaryContainer,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.clickable { showPages = true },
                    ) {
                        Text(
                            "≡ 选集 · 共 ${pages.size}P",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // AB 循环 + 跳过头尾（第二行 pills）
            val abA by playerVm.abPointA.collectAsState()
            val abB by playerVm.abPointB.collectAsState()
            val skipHead by playerVm.skipHeadSec.collectAsState()
            val skipTail by playerVm.skipTailSec.collectAsState()
            var showSkipDialog by remember { mutableStateOf(false) }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Surface(
                    color = if (abA != null && abB != null) cs.primary else cs.secondaryContainer,
                    contentColor = if (abA != null && abB != null) cs.onPrimary else cs.onSecondaryContainer,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { playerVm.abStep() },
                ) {
                    Text(
                        when {
                            abA == null -> "A↔B 循环 · 关"
                            abB == null -> "A ${formatMs(abA ?: 0L)} · 点此设 B"
                            else -> "A↔B ${formatMs(abA ?: 0L)}~${formatMs(abB ?: 0L)} · 清除"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                Surface(
                    color = if (skipHead > 0 || skipTail > 0) cs.tertiary else cs.secondaryContainer,
                    contentColor = if (skipHead > 0 || skipTail > 0) cs.onTertiary else cs.onSecondaryContainer,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { showSkipDialog = true },
                ) {
                    Text(
                        if (skipHead > 0 || skipTail > 0) "跳头尾 ${skipHead}s/${skipTail}s"
                        else "跳头尾 · 关",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            if (showSkipDialog) {
                SkipHeadTailDialog(
                    headSec = skipHead,
                    tailSec = skipTail,
                    onPick = { h, t ->
                        playerVm.setSkipHeadTail(h, t)
                        showSkipDialog = false
                    },
                    onDismiss = { showSkipDialog = false },
                )
            }
            if (showPages) {
                val curCid = cur.cid
                PagesSheet(
                    pages = pages,
                    currentCid = curCid,
                    onPick = {
                        playerVm.playPage(it)
                        showPages = false
                    },
                    onDismiss = { showPages = false },
                )
            }
            if (showSleepTimer) {
                SleepTimerDialog(
                    remainingSec = playerVm.sleepRemainingSec.value,
                    onPick = { minutes ->
                        if (minutes > 0) playerVm.setSleepTimer(minutes) else playerVm.clearSleepTimer()
                        showSleepTimer = false
                    },
                    onDismiss = { showSleepTimer = false },
                )
            }

            // 歌词区：B 站 AI 字幕优先，随播放居中滚动；点击进整页歌词
            val lyrics by playerVm.lyrics.collectAsState()
            val lyricsState by playerVm.lyricsState.collectAsState()
            val recognizeState by playerVm.recognizeState.collectAsState()
            val recognizeProgress by playerVm.recognizeProgress.collectAsState()
            val recognizeMsg by playerVm.recognizeMsg.collectAsState()
            Spacer(Modifier.height(14.dp))
            LyricPanel(
                lyrics = lyrics,
                state = lyricsState,
                positionMs = playback.positionMs + playerVm.lyricOffsetMs.collectAsState().value,
                onOpenLyrics = onOpenLyrics,
                recognizeState = recognizeState,
                recognizeProgress = recognizeProgress,
                recognizeMsg = recognizeMsg,
                onRecognize = { playerVm.recognizeLocally() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )

            // 进度
            Spacer(Modifier.height(18.dp))
            val sliderValue = seeking
                ?: if (playback.durationMs > 0) {
                    (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
                } else 0f
            Slider(
                value = sliderValue,
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { f ->
                        playerVm.seekTo((f * playback.durationMs).toLong())
                    }
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

            // 控制行
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { playerVm.skipPrevious() }, enabled = history.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "上一首",
                        tint = cs.onSurface,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                // 26dp 圆角方形主按钮（设计稿样式）
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = cs.primary,
                    contentColor = cs.onPrimary,
                    modifier = Modifier.size(64.dp),
                ) {
                    IconButton(onClick = { playerVm.toggle() }) {
                        Icon(
                            if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playback.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Spacer(Modifier.width(18.dp))
                IconButton(onClick = { playerVm.skipNext() }, enabled = history.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "下一首",
                        tint = cs.onSurface,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 底部常驻「声音增强」状态条
            Surface(
                color = cs.secondaryContainer,
                contentColor = cs.onSecondaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (boostOn) "♪ 声音增强：音量增益 +60%" else "♪ 声音增强：关闭",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = boostOn,
                        onCheckedChange = {
                            boostOn = it
                            playerVm.setVolumeBoost(it)
                        },
                    )
                }
            }
        }
    }
}

/** 时长格式化：超过 1 小时用 H:MM:SS（有声书场景） */
fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 本地识别按钮：无字幕时的兜底。状态机驱动文案与进度（模型下载/音频下载/解码/推理）。
 */
@Composable
fun RecognizeButton(
    state: com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState,
    progress: Int,
    msg: String,
    onClick: () -> Unit,
) {
    val busy = state in setOf(
        com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.DOWNLOADING_MODEL,
        com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.DOWNLOADING_AUDIO,
        com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.DECODING,
        com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.TRANSCRIBING,
    )
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.clickable(enabled = !busy) { onClick() },
        ) {
            Text(
                if (busy) "识别中…" else "🎤 本地识别生成歌词",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (busy) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when (state) {
                    com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.DOWNLOADING_MODEL ->
                        "下载识别模型 $progress%（约 78MB，仅首次）"
                    com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.DOWNLOADING_AUDIO ->
                        "下载音频 $progress%"
                    com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.DECODING -> "解码音频…"
                    com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.TRANSCRIBING ->
                        "本地识别中 $progress%（离线，请稍候）"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        if (state == com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.DONE) {
            Spacer(Modifier.height(4.dp))
            Text("识别完成 ✓", style = MaterialTheme.typography.labelSmall, color = cs.primary)
        }
        if (state == com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.ERROR) {
            Spacer(Modifier.height(4.dp))
            Text(msg, style = MaterialTheme.typography.labelSmall, color = cs.error)
        }
    }
}

/**
 * 跳过头尾设置：有声书每集固定长度的片头曲/片尾曲，全局秒数，持久化。
 */
@Composable
private fun SkipHeadTailDialog(
    headSec: Int,
    tailSec: Int,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var head by remember { mutableStateOf(headSec) }
    var tail by remember { mutableStateOf(tailSec) }
    val headOptions = listOf(0, 5, 10, 15, 30, 60)
    val tailOptions = listOf(0, 5, 10, 15, 20)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳过片头片尾") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("片头（起播自动跳过）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    headOptions.forEach { s ->
                        FilledTonalButton(
                            onClick = { head = s },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (head == s) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.width(56.dp),
                        ) { Text(if (s == 0) "关" else "${s}s") }
                    }
                }
                Text("片尾（提前切下一集）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tailOptions.forEach { s ->
                        FilledTonalButton(
                            onClick = { tail = s },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (tail == s) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.width(56.dp),
                        ) { Text(if (s == 0) "关" else "${s}s") }
                    }
                }
                Text(
                    "对所有视频生效；A↔B 循环进行时优先 AB。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(head, tail) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 定时关闭对话框：15/30/60/90 分钟；已设置时显示剩余并可取消。
 */
@Composable
private fun SleepTimerDialog(
    remainingSec: Long,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时关闭") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (remainingSec > 0) {
                    Text(
                        "已设置：剩余 " + formatMs(remainingSec * 1000),
                        color = cs.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 90).forEach { m ->
                        FilledTonalButton(onClick = { onPick(m) }) { Text("$m 分钟") }
                    }
                }
                if (remainingSec > 0) {
                    TextButton(onClick = { onPick(0) }) { Text("取消定时") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 分P 选集面板（Plan B 下拉弹层）：列出全部分P，当前项高亮，点击切换播放。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PagesSheet(
    pages: List<com.galigalimusic.data.remote.BilibiliApi.VideoPage>,
    currentCid: Long,
    onPick: (com.galigalimusic.data.remote.BilibiliApi.VideoPage) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerHigh,
    ) {
        Text(
            "选集 · 共 ${pages.size}P",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
            itemsIndexed(pages) { _, page ->
                val active = page.cid == currentCid
                Surface(
                    color = if (active) cs.secondaryContainer else Color.Transparent,
                    contentColor = if (active) cs.onSecondaryContainer else cs.onSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .clickable { onPick(page) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "P${page.page}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) cs.primary else cs.onSurfaceVariant,
                            modifier = Modifier.width(46.dp),
                        )
                        Text(
                            page.part,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatDuration(page.durationSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 歌词面板：当前行高亮放大，其余半透明；随播放自动滚动；点击行 seek 到该行起点。
 */
@Composable
private fun LyricPanel(
    lyrics: List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>,
    state: com.galigalimusic.viewmodel.PlayerViewModel.LyricsState,
    positionMs: Long,
    onOpenLyrics: () -> Unit,
    recognizeState: com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState,
    recognizeProgress: Int,
    recognizeMsg: String,
    onRecognize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable(onClick = onOpenLyrics),
        color = cs.surfaceContainer.copy(alpha = 0.55f),
        contentColor = cs.onSurfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        when {
            state == com.galigalimusic.viewmodel.PlayerViewModel.LyricsState.LOADING -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在获取 AI 字幕…", style = MaterialTheme.typography.labelSmall)
                }
            }
            state == com.galigalimusic.viewmodel.PlayerViewModel.LyricsState.NONE -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无字幕", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        RecognizeButton(
                            state = recognizeState,
                            progress = recognizeProgress,
                            msg = recognizeMsg,
                            onClick = onRecognize,
                        )
                    }
                }
            }
            else -> {
                // 紧凑预览：当前句居中放大；点行进整页歌词（整页内点行才是 seek）
                Column(Modifier.fillMaxSize()) {
                    com.galigalimusic.ui.components.LyricScroll(
                        lyrics = lyrics,
                        positionMs = positionMs,
                        activeColor = cs.primary,
                        idleColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
                        activeSize = 15.sp,
                        idleSize = 11.sp,
                        onSeek = { onOpenLyrics() },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    if (recognizeState == com.galigalimusic.viewmodel.PlayerViewModel.RecognizeState.TRANSCRIBING) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LinearProgressIndicator(
                                progress = { recognizeProgress / 100f },
                                modifier = Modifier.weight(1f).height(3.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "识别中 $recognizeProgress%",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
