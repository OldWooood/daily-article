package com.deatrg.dailyarticle.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.LocalDate
import kotlin.random.Random

/**
 * 主源：中国作家网 · 新作品（chinawriter.com.cn，中国作协官方，全 HTTPS）。
 *
 * 实测结构（人民网系 CMS，静态 HTML，无需 JS）：
 * - 列表：新作品首页的编辑精选（名家新作 + 报刊在线全文），n1 详情链接成池（约 10 篇，随编辑更新轮换）。
 * - 详情：标题 h6.end_tit em，作者 div.end_info（"来源：文艺报 | 胡学文 2026年09月11日…"），
 *   正文 div.end_article（p 段落，诗歌页 p 内用 <br> 分行）。
 *
 * 刻意不碰的坑：
 * - 期刊频道里的纯目录页（如《十月》当期目录，只有篇名+页码）没有 end_article，
 *   即使混入也会因解析失败被跳过；标题以"目录"结尾的再拦一道。
 * - 各子栏目列表页挂的是 vip 会员个人作品页（656 字节空壳，有登录墙），只用首页的 n1 链接成池。
 * - 首页 n1 链接有 http/https 两种写法，统一升级 https（该站 https 实测可用）。
 */
class ChinawriterSource : ArticleSource {

    override val name = "chinawriter.com.cn"

    override suspend fun fetchDaily(): Article {
        val pool = pool()
        return detail(pool, Math.floorMod(LocalDate.now().dayOfYear, pool.size))
    }

    override suspend fun fetchRandom(): Article {
        val pool = pool()
        return detail(pool, Random.nextInt(pool.size))
    }

    /** 新作品首页的 n1 详情链接成池；失败直接抛，交给 SourceChain 切备源。 */
    private fun pool(): List<String> {
        val doc = Jsoup.parse(Http.get(LIST, desktopUa = true), LIST)
        val out = LinkedHashSet<String>()
        doc.select("a[href]").forEach {
            var href = it.attr("abs:href").substringBefore("#").trim()
            if ("/n1/" !in href || !href.endsWith(".html")) return@forEach
            if (href.startsWith("http://")) href = "https://" + href.removePrefix("http://")
            if (!href.startsWith("https://www.chinawriter.com.cn/n1/")) return@forEach
            if (href in POOL_EXCLUDE) return@forEach
            out.add(href)
        }
        if (out.isEmpty()) throw ArticleException.EmptyContent(name)
        return out.toList()
    }

    /** 取详情；个别页面改版时顺着池子往下试。 */
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
        val page = Jsoup.parse(Http.get(url, desktopUa = true), url)
        val title = page.selectFirst("h6.end_tit em")?.text()?.trim()
            ?: page.selectFirst("h1")?.text()?.trim()
            ?: page.title().substringBefore("--").trim()
        // 期刊纯目录页不是可读文章，直接放行给池内下一篇
        if (title.trim().endsWith("目录")) throw ArticleException.EmptyContent(name)

        val author = parseAuthor(page.selectFirst("div.end_info")?.text().orEmpty())
        val body = page.selectFirst("div.end_article")
            ?: throw ArticleException.ParseError(name, "正文容器未找到")

        val blocks = mutableListOf<Block>()
        for (el in body.select("p, h2, h3")) {
            if (el.tagName() == "h2" || el.tagName() == "h3") {
                el.text().trim().takeIf { it.isNotEmpty() }?.let { blocks.add(Block.Subhead(it)) }
                continue
            }
            // 诗歌页一段内用 <br> 分行：按行拆成多段
            val html = el.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            Parser.unescapeEntities(html, false)
                .split("\n")
                .map { it.replace(Regex("<[^>]+>"), "").trim() }
                .filter { it.isNotEmpty() }
                .forEach { blocks.add(Block.Para(it)) }
        }
        body.select("img").forEach {
            val src = it.attr("abs:src").ifEmpty { it.attr("src") }
            if (src.isNotBlank()) blocks.add(Block.Image(src))
        }
        // 抓到页面但正文为空也算解析失败，必须抛异常进备源
        if (blocks.isEmpty()) throw ArticleException.EmptyContent(name)
        return Article(title.ifEmpty { "中国作家网" }, author, blocks, name)
    }

    /**
     * "来源：文艺报 | 胡学文 2026年09月11日08:58" 取竖线后、日期前的部分。
     * 有些页面没有作者，只有来源和日期，此时返回空（UI 会隐藏作者行）。
     */
    private fun parseAuthor(info: String): String {
        var s = info.substringAfter("|", "").trim()
        if (s.isEmpty()) return ""
        s = s.replace(Regex("\\d{4}年\\d{1,2}月\\d{1,2}日.*"), "").trim()
        // 剩下的如果还是"来源：xxx"说明竖线前才是来源、竖线后无作者
        if (s.startsWith("来源")) return ""
        return s
    }

    private companion object {
        const val LIST = "https://www.chinawriter.com.cn/404015/index.html"

        /** 页脚许可证页等非文章 n1 链接，建池时排除。 */
        val POOL_EXCLUDE = setOf(
            "https://www.chinawriter.com.cn/n1/2019/0130/c403928-30600339.html",
        )
    }
}
