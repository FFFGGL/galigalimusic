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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galigalimusic.ui.components.LyricScroll
import com.galigalimusic.viewmodel.PlayerViewModel

/**
 * 整页歌词（全屏沉浸）：点击播放页歌词区进入。
 * 歌词占满全屏、当前句居中放大滚动；底部保留精简控制（模式/上一首/播放/下一首 + 进度）。
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

    val cs = MaterialTheme.colorScheme
    val cur = current

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
            // 顶栏：返回 + 曲名/分P
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
                Spacer(Modifier.width(48.dp))
            }

            // 全屏歌词
            when {
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
                            )
                        }
                    }
                playerVm.recognizeState.value == PlayerViewModel.RecognizeState.TRANSCRIBING ->
                    Box(Modifier.weight(1f).fillMaxWidth()) {
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
                else -> LyricScroll(
                    lyrics = lyrics,
                    positionMs = playback.positionMs + lyricOffset,
                    activeColor = cs.primary,
                    idleColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    activeSize = 22.sp,
                    idleSize = 16.sp,
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
    }
}
