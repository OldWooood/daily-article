package com.deatrg.dailyarticle.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.time.LocalDate
import kotlin.random.Random

/**
 * 备源：散文网（sanwenwang.com，原 sanwen.net，独立域名，无需登录）
 * 分类列表页（散文/随笔/杂文）做候选池，详情页 div.content 为正文。
 * 实测：真人投稿散文，单篇数千字，文学定位与主源一致。
 * 必须用桌面 UA，否则会被 302 到 m.sanwen.net（另一套移动站，链接规则不同）。
 */
class SanwenwangSource : ArticleSource {

    override val name = "sanwenwang.com"

    override suspend fun fetchDaily(): Article {
        val pool = pool()
        return detail(pool, Math.floorMod(LocalDate.now().dayOfYear, pool.size))
    }

    override suspend fun fetchRandom(): Article {
        val pool = pool()
        return detail(pool, Random.nextInt(pool.size))
    }

    /**
     * 3 个分类列表各取约 26 篇，凑 70+ 篇候选池；并行抓取，单分类失败记日志但不影响其他。
     * 全部分类都因网络失败时抛原始异常（便于排查），否则抛内容为空。
     */
    private suspend fun pool(): List<String> = coroutineScope {
        var last: Throwable? = null
        val results = CATEGORIES.map { cat ->
            async(Dispatchers.IO) {
                runCatching {
                    val doc = Jsoup.parse(Http.get("$BASE$cat", desktopUa = true, timeoutMs = 10_000), BASE)
                    doc.select("a[href]").mapNotNull { it.attr("abs:href") }
                        .filter { it.startsWith("$BASE$cat") && it.endsWith(".html") }
                }.onFailure {
                    Log.w(TAG, "列表页失败: $cat", it)
                }
            }
        }.awaitAll()
        val out = mutableListOf<String>()
        for (r in results) {
            val list = r.getOrNull()
            if (list != null) out.addAll(list)
            else r.exceptionOrNull()?.let { last = it }
        }
        val distinct = out.distinct()
        if (distinct.isEmpty()) {
            // 一个都没抓到且全是请求失败：抛原始异常，别报"列表为空"误导排查
            val e = last
            if (e != null) throw e
            throw ArticleException.EmptyContent(name)
        }
        distinct
    }

    /** 取详情；个别文章下线/改版时顺着池子往下试。 */
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
        val page = Jsoup.parse(Http.get(url, desktopUa = true, timeoutMs = 10_000), url)
        val title = page.selectFirst("div.title h1")?.text()?.trim()
            ?: page.selectFirst("h1")?.text()?.trim().orEmpty()
        // div.info：日期 作者:<a>独自行走</a> …，取作者链接文本
        val info = page.selectFirst("div.info")
        val author = info?.selectFirst("a")?.text()?.trim().orEmpty()
        val body = page.selectFirst("div.content")
            ?: throw ArticleException.ParseError(name, "正文容器未找到")
        // 去广告与隐藏 SEO 文案（left:-100000px 的灌水 span），否则混入正文
        body.select("script, .adcontent, .adsbygoogle, [style*=-100000], .share, .copyright, .tags").remove()
        val blocks = mutableListOf<Block>()
        for (el in body.select("h2, h3, p, img")) {
            when (el.tagName()) {
                "img" -> el.attr("abs:src").ifBlank { el.attr("src") }
                    .takeIf { it.startsWith("http") }
                    ?.let { blocks.add(Block.Image(it, el.attr("alt"))) }
                "h2", "h3" -> el.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { blocks.add(Block.Subhead(it)) }
                else -> {
                    val text = el.text().trim()
                    // 站内"首发散文网：https://…"宣传段与页脚来源重复，直接丢弃
                    if (text.isEmpty() || text.startsWith("首发") ||
                        "sanwenwang.com" in text || "sanwen.net" in text
                    ) continue
                    blocks.add(Block.Para(text))
                }
            }
        }
        if (blocks.none { it is Block.Para }) throw ArticleException.EmptyContent(name)
        return Article(title.ifBlank { "散文网" }, author, blocks, name)
    }

    private companion object {
        const val TAG = "DailyArticle"
        const val BASE = "https://www.sanwenwang.com"
        /** 散文/随笔/杂文：长文分类；诗歌太短不收。 */
        val CATEGORIES = listOf("/sanwen/", "/suibi/", "/zawen/")
    }
}
