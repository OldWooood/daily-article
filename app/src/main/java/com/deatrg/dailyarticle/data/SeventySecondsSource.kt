package com.deatrg.dailyarticle.data

import java.time.LocalDate
import kotlin.random.Random

/**
 * 备源：每日 60 秒读懂世界（60s.viki.moe，独立域名，开源 JSON）
 * 支持 ?date=YYYY-MM-DD 查历史某天，用于"每日一篇"；随机则随机取近一年某天。
 * 内容为每日新闻简报，比文章短但结构稳定、从不解析失败。
 */
class SeventySecondsSource : ArticleSource {

    override val name = "60s.viki.moe"

    override suspend fun fetchDaily(): Article = dailyWithFallback()

    override suspend fun fetchRandom(): Article {
        // 近 365 天随机一天，避免长期只看到最近几条；
        // 单个日期缺数据时换一天重试，而不是让整个源失败。
        var last: Throwable = ArticleException.NetworkError("$name 没有可用日期")
        repeat(5) {
            val days = Random.nextInt(365)
            val result = runCatching { build(LocalDate.now().minusDays(days.toLong())) }
            result.onSuccess { return it }
            last = result.exceptionOrNull() ?: last
        }
        throw last
    }

    /** 今天没数据（节假日/接口延迟）时往前找两天，保证 daily 稳定有文。 */
    private fun dailyWithFallback(): Article {
        var last: Throwable = ArticleException.NetworkError("$name 没有可用日期")
        for (back in 0..2) {
            val result = runCatching { build(LocalDate.now().minusDays(back.toLong())) }
            result.onSuccess { return it }
            last = result.exceptionOrNull() ?: last
        }
        throw last
    }

    private fun build(date: LocalDate): Article {
        val d = Http.getJson("$BASE?date=$date").optJSONObject("data")
            ?: throw ArticleException.ParseError(name, "data 字段缺失")
        val news = d.optJSONArray("news")
            ?: throw ArticleException.ParseError(name, "news 字段缺失")
        val blocks = mutableListOf<Block>()
        for (i in 0 until news.length()) {
            val line = news.optString(i).trim()
            if (line.isNotEmpty()) blocks.add(Block.Para(line))
        }
        // 末尾微语作为收尾
        d.optString("tip").trim().takeIf { it.isNotEmpty() }
            ?.let { blocks.add(Block.Subhead("微语")); blocks.add(Block.Para(it)) }
        if (blocks.isEmpty()) throw ArticleException.EmptyContent(name)
        return Article(
            title = "每日 60 秒读懂世界 · ${d.optString("date").ifBlank { date.toString() }}",
            author = "60s",
            blocks = blocks,
            source = name,
        )
    }

    private companion object {
        const val BASE = "https://60s.viki.moe/v2/60s"
    }
}
