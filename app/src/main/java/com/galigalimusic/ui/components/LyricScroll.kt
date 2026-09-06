package com.galigalimusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.galigalimusic.data.remote.BilibiliApi

/**
 * 滚动歌词列表（播放页预览与整页歌词共用）。
 *
 * 当前行**垂直居中**且放大高亮，随播放自动滚动；点击行 seek 到该行起点。
 * 居中实现：上下 contentPadding = 视口一半，使首/末行也能滚到中央；
 * animateScrollToItem 的 scrollOffset 把行高的一半再抬回去，让「行中心」对准视口中心。
 */
@Composable
fun LyricScroll(
    lyrics: List<BilibiliApi.LrcLine>,
    positionMs: Long,
    activeColor: Color,
    idleColor: Color,
    activeSize: TextUnit,
    idleSize: TextUnit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val activeIndex = lyrics.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val centerNudge = with(density) { 22.dp.toPx().toInt() }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(index = activeIndex, scrollOffset = -centerNudge)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val half = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = half),
        ) {
            itemsIndexed(lyrics) { index, line ->
                val active = index == activeIndex
                val progress = if (active && line.endMs > line.startMs) {
                    ((positionMs - line.startMs).toFloat() / (line.endMs - line.startMs)).coerceIn(0f, 1f)
                } else if (active && positionMs >= line.startMs) 1f else 0f
                val highlightedChars = (line.text.length * progress).toInt().coerceIn(0, line.text.length)
                val karaokeText: AnnotatedString = buildAnnotatedString {
                    append(line.text)
                    if (highlightedChars > 0) addStyle(SpanStyle(color = activeColor), 0, highlightedChars)
                }
                Text(
                    text = karaokeText,
                    style = if (active) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.bodyLarge,
                    fontSize = if (active) activeSize else idleSize,
                    color = if (active) activeColor else idleColor,
                    textAlign = TextAlign.Center,
                    maxLines = if (active) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(line.startMs) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                )
            }
        }
    }
}
