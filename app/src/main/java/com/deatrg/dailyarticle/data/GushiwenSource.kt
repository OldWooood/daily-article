package com.deatrg.dailyarticle.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.LocalDate
import kotlin.random.Random

/**
 * 备源：古文岛/原古诗文网（gushiwen.cn，独立域名，无需登录）
 * 诗文列表做候选池，详情页 div.contson 为原诗（<br> 分行）。
 * 译文/赏析走 JS 异步接口，静态抓不到，只取原诗 + 作者朝代——
 * 短，但全是经典，适合做「每日一诗」。
 * 必须用桌面 UA：移动 UA 只返回 158 字节的占位页，没有诗文链接。
 */
class GushiwenSource : ArticleSource {

    override val name = "gushiwen.cn"

    override fun fetchDaily(): Article {
        val pool = pool()
        return detail(pool, Math.floorMod(LocalDate.now().dayOfYear, pool.size))
    }

    override fun fetchRandom(): Article {
        val pool = pool()
        return detail(pool, Random.nextInt(pool.size))
    }

    /** 诗文列表页的 /shiwenv_xxx.aspx 链接成池（约 10 首，少但稳定）。 */
    private fun pool(): List<String> {
        // 单点请求，失败重试 3 次（该站 CDN 偶发 5xx）
        var last: Throwable = IllegalStateException("$name 列表为空")
        repeat(3) {
            val result = runCatching { parsePool() }
            result.onSuccess { return it }
            last = result.exceptionOrNull() ?: last
        }
        throw last
    }

    private fun parsePool(): List<String> {
        val doc = Jsoup.parse(Http.get(LIST, desktopUa = true, timeoutMs = 10_000), LIST)
        val out = LinkedHashSet<String>()
        doc.select("a[href]").forEach {
            val href = it.attr("abs:href")
            if ("/shiwenv_" in href && href.endsWith(".aspx")) out.add(href.substringBefore("#"))
        }
        if (out.isEmpty()) throw IllegalStateException("$name 列表为空")
        return out.toList()
    }

    /** 取详情；个别诗页改版时顺着池子往下试。 */
    private fun detail(pool: List<String>, startIdx: Int): Article {
        var last: Throwable = IllegalStateException("$name 没有可用条目")
        for (offset in pool.indices) {
            val url = pool[(startIdx + offset) % pool.size]
            val result = runCatching { fetchDetail(url) }
            result.onSuccess { return it }
            last = result.exceptionOrNull() ?: last
        }
        throw last
    }

    private fun fetchDetail(url: String): Article {
        val page = Jsoup.parse(Http.get(url, desktopUa = true, timeoutMs = 10_000), url)
        val title = page.selectFirst("h1")?.text()?.trim().orEmpty()
        // p.source：<a>阎选</a> <a>〔五代〕</a>
        val source = page.selectFirst("p.source")
        val links = source?.select("a")?.map { it.text().trim() }.orEmpty()
        val author = listOf(
            links.getOrNull(0).orEmpty(),
            links.getOrNull(1).orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString("")
        val poem = page.selectFirst("div.contson")
            ?: throw IllegalStateException("$name 正文容器未找到")
        // 原诗以 <br> 分行：按 br 切行，每行一段
        val html = poem.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        val blocks = Parser.unescapeEntities(html, false)
            .split("\n")
            .map { it.replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.isNotEmpty() }
            .map { Block.Para(it) }
        if (blocks.isEmpty()) throw IllegalStateException("$name 正文为空")
        return Article(title.ifBlank { "古诗文" }, author, blocks, name)
    }

    private companion object {
        const val LIST = "https://www.gushiwen.cn/shiwens/"
    }
}
