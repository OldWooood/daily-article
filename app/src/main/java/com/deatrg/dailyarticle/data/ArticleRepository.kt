package com.deatrg.dailyarticle.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文章仓库：封装数据源链和缓存策略。
 * ViewModel 只与 Repository 交互，不直接操作数据源。
 */
class ArticleRepository(
    private val store: ArticleStore,
    private val chain: SourceChain
) {

    /** 获取每日文章：优先缓存，否则走源链 */
    suspend fun getDaily(ctx: Context): Article = withContext(Dispatchers.IO) {
        // 1. 今天缓存命中
        store.loadDailyToday(ctx)?.let { return@withContext it }

        // 2. 走源链
        val article = chain.daily()
        store.saveDaily(ctx, article)
        store.saveLastRead(ctx, article, isRandom = false)
        article
    }

    /** 仅检查今日缓存，不触发网络请求 */
    suspend fun loadDailyToday(ctx: Context): Article? =
        withContext(Dispatchers.IO) { store.loadDailyToday(ctx) }

    /** 获取随机文章：直接走源链 */
    suspend fun getRandom(ctx: Context): Article = withContext(Dispatchers.IO) {
        val article = chain.random()
        store.saveLastRead(ctx, article, isRandom = true)
        article
    }

    /** 加载上次在读的文章 */
    suspend fun loadLastRead(ctx: Context): ArticleStore.Restored? =
        withContext(Dispatchers.IO) { store.loadLastRead(ctx) }

    /** 兜底：任意一天的每日文章 → 上次在读的文章 */
    suspend fun loadFallback(ctx: Context): Article? = withContext(Dispatchers.IO) {
        store.loadDailyAny(ctx) ?: store.loadLastRead(ctx)?.article
    }

    /** 更新阅读位置 */
    suspend fun saveScroll(ctx: Context, index: Int, offset: Int) =
        withContext(Dispatchers.IO) { store.saveScroll(ctx, index, offset) }

    /** 清除阅读位置（同步写盘） */
    fun clearScroll(ctx: Context) = store.clearScroll(ctx)

    companion object {
        private const val TAG = "DailyArticle"
    }
}
