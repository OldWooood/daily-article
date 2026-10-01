package com.deatrg.dailyarticle.data

import android.util.Log

/**
 * 文章源统一接口。实现类必须做到：抓不到内容就抛异常，绝不返回空文章，
 * 这样 [SourceChain] 才能正确切到下一个备源（永不白屏）。
 */
interface ArticleSource {
    /** 域名，用于日志与页脚标注 */
    val name: String

    /** 当天的文章。同一自然日应稳定返回同一篇。 */
    fun fetchDaily(): Article

    /** 随机一篇文章。 */
    fun fetchRandom(): Article
}

/**
 * 故障转移链：按顺序逐个尝试，第一个成功的结果胜出。
 * 各源均为独立域名，避免"一起挂"。
 */
class SourceChain(private val sources: List<ArticleSource>) {

    /** 依次尝试 daily，返回首个成功结果；全失败则抛最后一个异常。 */
    fun daily(): Article = firstSuccess { it.fetchDaily() }

    fun random(): Article = firstSuccess { it.fetchRandom() }

    /** 逐个尝试，任一源抛异常就跳到下一个；全部失败才抛出最后一个异常。 */
    private inline fun firstSuccess(block: (ArticleSource) -> Article): Article {
        var last: Throwable = IllegalStateException("没有可用数据源")
        for (s in sources) {
            val result = runCatching { block(s) }
            result.onSuccess {
                Log.i(TAG, "命中数据源: ${s.name}")
                return it
            }
            // 关键：不能静默吞掉，否则主源常年失效也看不出来
            Log.w(TAG, "数据源失败: ${s.name}", result.exceptionOrNull())
            last = result.exceptionOrNull() ?: last
        }
        Log.e(TAG, "全部数据源均失败", last)
        throw last
    }

    private companion object { const val TAG = "DailyArticle" }
}
