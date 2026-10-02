package com.deatrg.dailyarticle.data

import android.util.Log
import java.time.LocalDate
import kotlin.random.Random

/**
 * 文章源统一接口。实现类必须做到：抓不到内容就抛异常，绝不返回空文章，
 * 这样 [SourceChain] 才能正确切到下一个备源（永不白屏）。
 *
 * 所有实现都是 suspend：上层统一在 Dispatchers.IO 调用，内部可用
 * coroutineScope/async 做并行（如散文网多分类列表页）。
 */
interface ArticleSource {
    /** 域名，用于日志与页脚标注 */
    val name: String

    /** 当天的文章。同一自然日应稳定返回同一篇。 */
    suspend fun fetchDaily(): Article

    /** 随机一篇文章。 */
    suspend fun fetchRandom(): Article
}

/**
 * 故障转移链：按轮换后的顺序逐个尝试，第一个成功的结果胜出。
 * 各源均为独立域名，避免"一起挂"。
 *
 * 轮换规则（解决"链首源垄断"）：
 * - daily：起始下标 = dayOfYear % 源数量。同一自然日内顺序固定（当天稳定同一篇），
 *   跨天主源轮换，各家源的"当日篇"轮流见面；缓存层按天存，当天只抓一次。
 * - random：起始下标完全随机，故障时顺延，保证每次都有跨源开盲盒感。
 * 无论从哪开始，整条链都会走完，故障转移语义不变。
 */
class SourceChain(private val sources: List<ArticleSource>) {

    /** 依次尝试 daily，返回首个成功结果；全失败则抛全部异常汇总。 */
    suspend fun daily(): Article {
        val start = if (sources.isEmpty()) 0
        else Math.floorMod(LocalDate.now().dayOfYear, sources.size)
        return firstSuccess(rotated(start)) { it.fetchDaily() }
    }

    suspend fun random(): Article {
        if (sources.isEmpty()) throw ArticleException.AllSourcesFailed(emptyList())
        return firstSuccess(rotated(Random.nextInt(sources.size))) { it.fetchRandom() }
    }

    /** 把链转一下使下标 start 的源排第一，其余顺序不变。 */
    private fun rotated(start: Int): List<ArticleSource> {
        if (sources.isEmpty()) return sources
        val n = sources.size
        return List(n) { sources[Math.floorMod(start + it, n)] }
    }

    /** 逐个尝试，任一源抛异常就跳到下一个；全部失败才抛出汇总异常。 */
    private suspend inline fun firstSuccess(
        ordered: List<ArticleSource>,
        crossinline block: suspend (ArticleSource) -> Article,
    ): Article {
        val errors = mutableListOf<Throwable>()
        for (s in ordered) {
            val result = runCatching { block(s) }
            result.onSuccess {
                Log.i(TAG, "命中数据源: ${s.name}")
                return it
            }
            // 关键：不能静默吞掉，否则主源常年失效也看不出来
            val e = result.exceptionOrNull()
            Log.w(TAG, "数据源失败: ${s.name}", e)
            if (e != null) errors.add(e)
        }
        val failed = ArticleException.AllSourcesFailed(errors.toList())
        Log.e(TAG, "全部数据源均失败", failed)
        throw failed
    }

    private companion object { const val TAG = "DailyArticle" }
}
