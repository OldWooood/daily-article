package com.deatrg.dailyarticle.data

import android.util.Log
import org.jsoup.Jsoup
import java.time.LocalDate
import kotlin.random.Random

/**
 * 备源：短文学/文章阅读网（duwenzhang.com，独立域名，无需登录）
 * 美文/散文列表页做候选池，详情页 div#wenzhangziti 为正文。
 * 注意：全站 GB2312 编码，靠 [Http] 的编码自适应解码。
 */
class DuwenzhangSource : ArticleSource {

    override val name = "duwenzhang.com"

    override suspend fun fetchDaily(): Article {
        val pool = pool()
        return detail(pool, Math.floorMod(LocalDate.now().dayOfYear, pool.size))
    }

    override suspend fun fetchRandom(): Article {
        val pool = pool()
        return detail(pool, Random.nextInt(pool.size))
    }

    /** 美文 + 散文两个列表页的 /wenzhang/ 文章链接去重成池。 */
    private fun pool(): List<String> {
        val out = LinkedHashSet<String>()
        var last: Throwable? = null
        for (list in LISTS) {
            val result = runCatching {
                val doc = Jsoup.parse(Http.get(list, timeoutMs = 10_000), list)
                doc.select("a[href]").forEach {
                    val href = it.attr("abs:href").substringBefore("#")
                    if ("/wenzhang/" in href && href.endsWith(".html")) out.add(href)
                }
            }
            result.exceptionOrNull()?.let {
                Log.w(TAG, "列表页失败: $list", it)
                last = it
            }
        }
        // 一个都没抓到且全是请求失败：抛原始异常，别报"列表为空"误导排查
        if (out.isEmpty() && last != null) throw last!!
        if (out.isEmpty()) throw ArticleException.EmptyContent(name)
        return out.toList()
    }

    /** 取详情；个别文章删除/改版时顺着池子往下试。 */
    private fun detail(pool: List<String>, startIdx: Int): Article {
        var last: Throwable = ArticleException.ParseError(name, "没有可用条目")
        for (offset in pool.indices) {
            val url = pool[(startIdx + offset) % pool.size]
            val result = runCatching { fetchDetail(url) }
            result.onSuccess { return it }
            last = result.exceptionOrNull() ?: last
        }
        throw last
    }

    private fun fetchDetail(url: String): Article {
        val page = Jsoup.parse(Http.get(url, timeoutMs = 10_000), url)
        val title = page.selectFirst("h1")?.text()?.trim().orEmpty()
        // td.author：作者：<a>朋谊</a> 来源：…；老文章作者链接常为空，退到纯文本正则
        val authorCell = page.selectFirst("td.author")
        val author = authorCell?.selectFirst("a")?.text()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: Regex("作者：\\s*(\\S+)").find(authorCell?.text().orEmpty())
                ?.groupValues?.getOrNull(1).orEmpty()
                // 作者缺失时别把"来源：/时间："当成名字
                .takeUnless { it.contains("：") || it.contains(":") }.orEmpty()
        val body = page.selectFirst("div#wenzhangziti")
            ?: throw ArticleException.ParseError(name, "正文容器未找到")
        body.select("script").remove()
        val blocks = mutableListOf<Block>()
        // 站内用大写 <P>，Jsoup 会归一成小写，直接选 p 即可
        for (el in body.select("p, img")) {
            when (el.tagName()) {
                "img" -> el.attr("abs:src").ifBlank { el.attr("src") }
                    .takeIf { it.startsWith("http") }
                    ?.let { blocks.add(Block.Image(it, el.attr("alt"))) }
                else -> el.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { blocks.add(Block.Para(it)) }
            }
        }
        if (blocks.none { it is Block.Para }) throw ArticleException.EmptyContent(name)
        return Article(title.ifBlank { "短文学" }, author, blocks, name)
    }

    private companion object {
        const val TAG = "DailyArticle"
        val LISTS = listOf(
            "https://www.duwenzhang.com/meiwen.html",
            "https://www.duwenzhang.com/sanwen.html",
        )
    }
}
