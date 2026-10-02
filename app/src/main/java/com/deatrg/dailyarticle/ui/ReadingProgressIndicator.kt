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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.deatrg.dailyarticle.data.Article

/**
 * 右侧阅读进度条。
 *
 * `Modifier.verticalScrollbar` 只存在于 foundation 的 desktop 产物，
 * Android 端没有，所以这里按 LazyListState 的真实滚动量自己算。
 *
 * 旧实现用「首屏平均高度 × 总条目数」估算全文长度，且首屏采样后冻结：
 * 后面懒组成的高条目（长段落、大图、页脚）进来后总数估算不变，
 * 滑块会提前贴底且一直粘在下面。
 * 这里改为像素口径：见过的条目用真实高度，没见过的才按「已见平均」
 * 估算。新内容组成 / 图片加载撑高后总数自动变大，滑块会从底部退回来。
 */
@Composable
fun BoxScope.ReadingProgressIndicator(listState: LazyListState, article: Article) {
    // 每篇文章独立一份：index -> 见过的最大高度。图片异步加载撑高后取最大，
    // 总数只增不减，滑块不会抖。
    val knownHeights = remember(article) { mutableStateMapOf<Int, Int>() }
    // 布局每次变化都记录可见条目的真实高度：尾部内容懒组成后总数估算
    // 自动更新，这正是旧实现缺的那一块。
    LaunchedEffect(listState, article) {
        snapshotFlowSizes(listState).collect { pairs ->
            for ((index, size) in pairs) {
                val prev = knownHeights[index]
                if (prev == null || size > prev) knownHeights[index] = size
            }
        }
    }
    // derivedStateOf：滚动时只在进度真的变化时重组，不逐帧重组，省 CPU
    val progress by remember(listState, article) {
        derivedStateOf { measure(listState, knownHeights) }
    }
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
 * 像素口径的进度与滑块占比。
 * - 已滚像素 = 首个可见条目之前所有条目的真实/估算高度之和 + 首项内的偏移。
 * - 全文总高 = 每个下标的真实高度（见过）或已见平均（没见过）之和。
 * - 尾部新条目组成、图片撑高时总高变大，已滚不变，fraction 自动从 1 回落，
 *   滑块离开底部；旧实现总数恒定，所以一直粘底。
 */
private fun measure(listState: LazyListState, known: Map<Int, Int>): Progress {
    val info = listState.layoutInfo
    val total = info.totalItemsCount
    val items = info.visibleItemsInfo
    val viewportH = info.viewportSize.height.toFloat()
    if (total <= 1 || items.isEmpty() || viewportH <= 0f) return Progress(-1f, 0f)
    val liveAvg = items.sumOf { it.size }.toFloat() / items.size
    val seenAvg = if (known.isNotEmpty()) known.values.average().toFloat() else 0f
    val avgH = when {
        seenAvg > 0f -> seenAvg
        liveAvg > 0f -> liveAvg
        else -> return Progress(-1f, 0f)
    }

    val firstIndex = listState.firstVisibleItemIndex
    val offset = listState.firstVisibleItemScrollOffset.toFloat()
    var scrolledPx = offset
    for (i in 0 until firstIndex) scrolledPx += known[i]?.toFloat() ?: avgH
    var totalPx = 0f
    for (i in 0 until total) totalPx += known[i]?.toFloat() ?: avgH
    if (totalPx <= viewportH) return Progress(-1f, 0f)
    return Progress(
        fraction = (scrolledPx / (totalPx - viewportH)).coerceIn(0f, 1f),
        thumbRatio = (viewportH / totalPx).coerceIn(0f, 1f),
    )
}

/** 观察可见条目 (index, size) 的快照流；抽出来方便测试与复用。 */
private fun snapshotFlowSizes(listState: LazyListState) =
    snapshotFlow {
        listState.layoutInfo.visibleItemsInfo.map { it.index to it.size }
    }
