package com.deatrg.dailyarticle

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deatrg.dailyarticle.ui.ArticleBody
import com.deatrg.dailyarticle.ui.SaveScrollPosition

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

                is UiState.Done -> {
                    // 按文章 key 隔离：换文时 LazyListState 全新创建，不会把旧文的
                    // 滚动位置和已布局信息带到新文，否则进度条首帧会读到脏数据。
                    key(s.article) {
                        val listState = rememberLazyListState()
                        val restore by rememberUpdatedState(vm.scroll.collectAsState().value)
                        val randomLoading by vm.randomLoading.collectAsState()

                        // 冷启动时回到上次的位置。index 可能因为换了文章而越界，兜底回顶部。
                        LaunchedEffect(s.article) {
                            runCatching { listState.scrollToItem(restore.index, restore.offset) }
                                .onFailure { listState.scrollToItem(0) }
                        }

                        SaveScrollPosition(listState, s.article, vm::onScrolled)

                        ArticleBody(
                            article = s.article,
                            listState = listState,
                            onRandom = vm::loadRandom,
                            randomLoading = randomLoading,
                        )
                    }
                }
            }
        }
    }
}
