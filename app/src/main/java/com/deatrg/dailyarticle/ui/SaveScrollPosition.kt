package com.deatrg.dailyarticle.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.deatrg.dailyarticle.data.Article
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/** 记录并持久化阅读位置（防抖，避免每帧写盘）。 */
@OptIn(FlowPreview::class)
@Composable
fun SaveScrollPosition(
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
