package com.deatrg.dailyarticle.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.deatrg.dailyarticle.data.Article
import com.deatrg.dailyarticle.data.Block

/**
 * 正文。
 * - 「读完了，随机一篇」放在列表最后一项：读完整篇才看到，而不是一直占着屏幕底部。
 * - 右侧常驻细进度条：让用户知道这篇有多长、现在读到哪了。
 * - 全文可选：长按可复制句子。
 */
@Composable
fun ArticleBody(
    article: Article,
    listState: LazyListState,
    onRandom: () -> Unit,
    randomLoading: Boolean,
) {
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
                                model = ImageRequest.Builder(context)
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
