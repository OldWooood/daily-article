package com.deatrg.dailyarticle.data

import org.jsoup.Jsoup
import java.time.LocalDate
import kotlin.random.Random

/**
 * 备源：知乎日报（news-at.zhihu.com，独立域名，公开 JSON，无需登录）
 * 列表 /api/4/news/latest，详情 /api/4/news/{id} 的 body 为正文 HTML（含 <p> 与 <img>）。
 */
class ZhihuSource : ArticleSource {

    override val name = "news-at.zhihu.com"

    private data class Story(val id: String, val title: String, val hint: String)

    override fun fetchDaily(): Article {
        val list = stories()
        val idx = Math.floorMod(LocalDate.now().dayOfYear, list.size)
        return detail(list[idx])
    }

    override fun fetchRandom(): Article {
        val list = stories()
        return detail(list[Random.nextInt(list.size)])
    }

    private fun stories(): List<Story> {
        val arr = Http.getJson("$BASE/latest").optJSONArray("stories")
            ?: throw ArticleException.ParseError(name, "列表字段缺失")
        val out = mutableListOf<Story>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                Story(
                    id = id,
                    title = o.optString("title"),
                    // hint 形如 "解磊 · 4 分钟阅读"，只取作者部分
                    hint = o.optString("hint").substringBefore("·").trim(),
                )
            )
        }
        if (out.isEmpty()) throw ArticleException.EmptyContent(name)
        return out
    }

    private fun detail(s: Story): Article {
        val body = Http.getJson("$BASE/${s.id}").optString("body")
        if (body.isBlank()) throw ArticleException.EmptyContent(name)
        val doc = Jsoup.parseBodyFragment(body, BASE)

        val blocks = mutableListOf<Block>()
        // 列表封面图不插入：它通常是头像或与正文首图重复，会把正文挤到屏幕外。
        // 只保留正文里真正的内容插图。
        for (el in doc.select("p, h2, h3, img")) {
            when (el.tagName()) {
                "img" -> {
                    // 跳过作者头像、行内表情等装饰性小图
                    val decorative = el.hasClass("avatar") ||
                        el.parents().any { it.hasClass("meta") || it.hasClass("author") }
                    if (decorative) continue
                    val src = el.attr("data-original").ifBlank { el.attr("abs:src") }
                        .ifBlank { el.attr("src") }
                    if (src.isNotBlank()) blocks.add(Block.Image(src))
                }

                "h2", "h3" -> el.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { blocks.add(Block.Subhead(it)) }

                else -> el.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { blocks.add(Block.Para(it)) }
            }
        }
        if (blocks.none { it is Block.Para }) throw ArticleException.EmptyContent(name)
        return Article(s.title.ifBlank { "知乎日报" }, s.hint, blocks, name)
    }

    private companion object {
        const val BASE = "https://news-at.zhihu.com/api/4/news"
    }
}
