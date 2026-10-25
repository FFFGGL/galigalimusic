package com.galigalimusic.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.galigalimusic.data.remote.BilibiliApi

/**
 * 双行歌词（卡拉OK式）：当前句大字 + 逐字高亮，下一句小字预览。
 *
 * 与 LyricScroll（整屏列表）二选一，由歌词页「双行/列表」开关切换。
 * 换句时当前句做垂直滑入动画；点击下一句可直接 seek 到该句起点。
 * 逐字高亮按句内进度均分（B 站字幕为短语级，与 LyricScroll 口径一致）。
 */
@Composable
fun LyricTwoLine(
    lyrics: List<BilibiliApi.LrcLine>,
    positionMs: Long,
    activeColor: Color,
    idleColor: Color,
    activeSize: TextUnit,
    idleSize: TextUnit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 24.dp,
) {
    if (lyrics.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无字幕", color = idleColor)
        }
        return
    }

    val activeIndex = lyrics.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val current = lyrics[activeIndex]
    val next = lyrics.getOrNull(activeIndex + 1)

    val progress = if (current.endMs > current.startMs) {
        ((positionMs - current.startMs).toFloat() / (current.endMs - current.startMs)).coerceIn(0f, 1f)
    } else if (positionMs >= current.startMs) 1f else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300),
        label = "karaoke",
    )
    val highlightedChars = (current.text.length * animatedProgress).toInt().coerceIn(0, current.text.length)
    val karaokeText: AnnotatedString = buildAnnotatedString {
        append(current.text)
        if (highlightedChars > 0) addStyle(SpanStyle(color = activeColor), 0, highlightedChars)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = verticalPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 当前句：换句滑入动画
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                if (targetState.startMs >= initialState.startMs) {
                    slideInVertically { it } togetherWith slideOutVertically { -it }
                } else {
                    slideInVertically { -it } togetherWith slideOutVertically { it }
                }
            },
            label = "currentLine",
        ) { line ->
            val lineProgress = if (line == current) animatedProgress else 1f
            val chars = (line.text.length * lineProgress).toInt().coerceIn(0, line.text.length)
            val text: AnnotatedString = buildAnnotatedString {
                append(line.text)
                if (chars > 0) addStyle(SpanStyle(color = activeColor), 0, chars)
            }
            Text(
                text = text,
                fontSize = activeSize,
                lineHeight = activeSize * 1.4f,
                color = idleColor,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))

        // 下一句预览：小字置灰，点击可跳转到该句
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (next != null) Modifier.clickable { onSeek(next.startMs) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = next?.text ?: "",
                fontSize = idleSize,
                lineHeight = idleSize * 1.4f,
                color = idleColor.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "${activeIndex + 1} / ${lyrics.size}",
            style = MaterialTheme.typography.labelSmall,
            color = idleColor.copy(alpha = 0.4f),
        )
    }
}
