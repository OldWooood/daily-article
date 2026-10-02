package com.deatrg.dailyarticle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.deatrg.dailyarticle.data.Article
import kotlinx.coroutines.flow.first

/**
 * 右侧阅读进度条。
 *
 * `Modifier.verticalScrollbar` 只存在于 foundation 的 desktop 产物，
 * Android 端没有，所以这里按 LazyListState 的真实滚动量自己算。
 *
 * 注意：高度不能用「瞬时可见条目数」算——滚动时条目进出视口，
 * 数量在 N↔N+1 间跳，滑块会跟着突变约 1/N。这里一律用平均条目高度
 * 估算，变化是连续的，没有阶跃。
 */
@Composable
fun BoxScope.ReadingProgressIndicator(listState: LazyListState, article: Article) {
    // 基准条目高度：按文章冻结一次。滚动中可见集合会变化，实时平均仍会跳；
    // 冻结后滑块高度全程恒定，只有位置在动，和系统滚动条行为一致。
    var baseAvg by remember(article) { mutableFloatStateOf(0f) }
    LaunchedEffect(listState, article) {
        if (baseAvg == 0f) {
            baseAvg = snapshotFlow { sampleAvg(listState) }.first { it > 0f }
        }
    }
    // derivedStateOf：滚动时只在进度真的变化时重组，不逐帧重组，省 CPU
    val progress by remember(listState) { derivedStateOf { measure(listState, baseAvg) } }
    // 一屏就能读完，不需要进度条
    if (progress.fraction < 0f) return

    val density = LocalDensity.current
    val trackHeightPx = listState.layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f)
    // 滑块高度 = 视口占比，并设下限保证可见
    val minThumbPx = with(density) { 36.dp.toPx() }
    val thumbHeightPx = (trackHeightPx * progress.thumbRatio).coerceAtLeast(minThumbPx)
        .coerceAtMost(trackHeightPx)
    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val thumbHeightDp = with(density) { thumbHeightPx.toDp() }
    val thumbTopDp = with(density) { (travelPx * progress.fraction).toDp() }

    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 3.dp)
            .width(3.dp)
            .fillMaxHeight()
    ) {
        Box(
            Modifier
                .offset(y = thumbTopDp)
                .fillMaxWidth()
                .height(thumbHeightDp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
        )
    }
}

/** 进度条度量：fraction 为负表示一屏能读完，不需要进度条。 */
private data class Progress(val fraction: Float, val thumbRatio: Float)

/**
 * 用平均条目高度估算进度与滑块占比。
 * 可见条目进出视口时，平均值连续变化，不像「可见条目数」那样阶跃；
 * 再叠加按文章冻结基准高度，滑块长度全程恒定，只有位置在动。
 * @param frozenAvg 已冻结的基准高度（>0 时采用，滑块高度全程恒定）。
 */
private fun measure(listState: LazyListState, frozenAvg: Float = 0f): Progress {
    val info = listState.layoutInfo
    val total = info.totalItemsCount
    val items = info.visibleItemsInfo
    val viewportH = info.viewportSize.height.toFloat()
    if (total <= 1 || items.isEmpty() || viewportH <= 0f) return Progress(-1f, 0f)
    val liveAvg = items.sumOf { it.size }.toFloat() / items.size
    val avgH = if (frozenAvg > 0f) frozenAvg else liveAvg
    if (avgH <= 0f) return Progress(-1f, 0f)
    val visibleF = viewportH / avgH
    if (visibleF >= total) return Progress(-1f, 0f)
    val scrolled = listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset / avgH
    return Progress(
        fraction = (scrolled / (total - visibleF)).coerceIn(0f, 1f),
        thumbRatio = (viewportH / (avgH * total)).coerceIn(0f, 1f),
    )
}

/** 当前可见条目的平均高度；未布局好时返回 0。 */
private fun sampleAvg(listState: LazyListState): Float {
    val items = listState.layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return 0f
    return items.sumOf { it.size }.toFloat() / items.size
}
