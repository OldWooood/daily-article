package com.deatrg.dailyarticle

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.deatrg.dailyarticle.data.Article
import com.deatrg.dailyarticle.data.Block
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first

@Composable
fun DailyScreen(vm: ArticleViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // 返回键退出：清掉这次的阅读进度，下次进来从顶部开始读（文章本身保留）。
    BackHandler {
        vm.onBackPressed()
        (context as? Activity)?.finish()
    }

    // 根容器必须提供 background + contentColor，否则默认文字颜色是黑色，
    // 深色模式下黑字压在深色窗口背景上完全看不见（本次 bug）。
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            when (val s = state) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is UiState.Error -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(s.msg, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { if (s.isRandom) vm.loadRandom() else vm.loadDaily() }) {
                        Text("重试")
                    }
                }

                is UiState.Done -> ArticleBody(
                    article = s.article,
                    onRandom = vm::loadRandom,
                    onScrolled = vm::onScrolled,
                    restore = vm.scroll.collectAsState().value,
                    randomLoading = vm.randomLoading.collectAsState().value,
                )
            }
        }
    }
}

/**
 * 正文。
 * - 「读完了，随机一篇」放在列表最后一项：读完整篇才看到，而不是一直占着屏幕底部。
 * - 右侧常驻细进度条：让用户知道这篇有多长、现在读到哪了。
 * - 全文可选：长按可复制句子。
 */
@Composable
private fun ArticleBody(
    article: Article,
    onRandom: () -> Unit,
    onScrolled: (Int, Int) -> Unit,
    restore: Scroll,
    randomLoading: Boolean,
) {
    val listState = rememberLazyListState()

    // 冷启动时回到上次的位置。index 可能因为换了文章而越界，兜底回顶部。
    val target by rememberUpdatedState(restore)
    LaunchedEffect(article) {
        val t = target
        runCatching { listState.scrollToItem(t.index, t.offset) }
            .onFailure { listState.scrollToItem(0) }
    }

    SaveScrollPosition(listState, article, onScrolled)

    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp),
                contentPadding = PaddingValues(vertical = 20.dp)
            ) {
                item(key = "header") {
                Text(
                    article.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (article.author.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        article.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
            }

            itemsIndexed(article.blocks, key = { index, _ -> index }) { _, b ->
                when (b) {
                    is Block.Subhead -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            b.text,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    is Block.Para -> {
                        Text(
                            "　　" + b.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.6,
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    is Block.Image -> {
                        // 限宽解码：源站横幅图常 2700px+，按屏宽解码即可，
                        // 省 CPU/内存，也省电；Coil 磁盘缓存照常命中。
                        val context = LocalContext.current
                        val screenPx = with(LocalDensity.current) {
                            LocalConfiguration.current.screenWidthDp.dp.toPx().toInt()
                        }
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(context)
                                .data(b.url)
                                .size(screenPx.coerceIn(1, 1080))
                                .crossfade(true)
                                .build(),
                            contentDescription = b.caption.ifBlank { null },
                            // contentScale = Fit + 不裁切，避免小图被拉成巨图
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .padding(vertical = 10.dp)
                        )
                        if (b.caption.isNotBlank()) {
                            Text(
                                b.caption,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            item(key = "footer") {
                if (article.source.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "来源 · ${article.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(36.dp))

                // 全 App 唯一的操作按钮，出现在正文末尾
                Button(
                    onClick = onRandom,
                    enabled = !randomLoading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("读完了，随机一篇") }

                Spacer(Modifier.height(8.dp))
                Text(
                    "每日一文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }
            } // LazyColumn
        } // SelectionContainer

        // 换文中：顶部细进度条，老文章保留可读
        if (randomLoading) {
            LinearProgressIndicator(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            )
        }

        ReadingProgressIndicator(listState, article)
    }
}

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
private fun BoxScope.ReadingProgressIndicator(listState: LazyListState, article: Article) {
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

/** 记录并持久化阅读位置（防抖，避免每帧写盘）。 */
@OptIn(FlowPreview::class)
@Composable
private fun SaveScrollPosition(
    listState: LazyListState,
    article: Article,
    onScrolled: (Int, Int) -> Unit,
) {
    val latest by rememberUpdatedState(onScrolled)
    LaunchedEffect(listState, article) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            // 防抖：停止滚动 400ms 后才落盘
            .debounce(400)
            .collect { (i, o) -> latest(i, o) }
    }
}
