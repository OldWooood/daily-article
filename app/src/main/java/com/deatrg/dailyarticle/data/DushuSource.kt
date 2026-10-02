package com.deatrg.dailyarticle.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 主源：读书网 · 每日一读（dushu.com）
 * 实测结构：标题 div.article-detail h1，作者 .article-info，正文 div.text（内含 h2/h3/p/img）。
 * 必须用桌面 UA，否则会被跳到 m 站。
 */
class DushuSource : ArticleSource {

    override val name = "dushu.com"

    /**
     * www 站的证书链缺少中间证书，在较老的 Android（实测 Android 11）上
     * 会因「Trust anchor for certification path not found」握手失败。
     * 因此按 [HOSTS] 顺序依次尝试：先桌面 UA 拿 www 站，再退到 m 站（移动 UA）。
     */
    override suspend fun fetchDaily(): Article = parse(CANDIDATES_DAILY)

    override suspend fun fetchRandom(): Article = parse(CANDIDATES_RANDOM)

    /** 依次请求各候选入口，第一个解析成功的胜出。 */
    private fun parse(candidates: List<Endpoint>): Article {
        var last: Throwable = ArticleException.NetworkError("$name 没有可用入口")
        for (e in candidates) {
            val result = runCatching { parse(Http.get(e.url, desktopUa = e.desktopUa), e.url) }
            result.onSuccess { return it }
            last = result.exceptionOrNull() ?: last
        }
        throw last
    }

    /**
     * @param baseUri 传给 Jsoup，使 `abs:src` 能把站点根相对路径（如 /img/x.jpg）
     *                正确补全为绝对 URL，否则相对图片会静默丢失。
     */
    private fun parse(html: String, baseUri: String): Article {
        val page = Jsoup.parse(html, baseUri)
        page.outputSettings().prettyPrint(false)

        val title = page.selectFirst("div.article-detail h1")?.text()?.trim()
            ?: page.selectFirst("h1")?.text()?.trim().orEmpty()
        val author = page.selectFirst(".article-info")?.text()?.trim().orEmpty()
        val body: Element = page.selectFirst("div.text")
            ?: throw ArticleException.ParseError(name, "正文容器未找到")

        val blocks = mutableListOf<Block>()
        for (el in body.select("h2, h3, p, img")) {
            when (el.tagName()) {
                "img" -> el.attr("abs:src").ifEmpty { el.attr("src") }
                    .takeIf { it.isNotBlank() }
                    ?.let { blocks.add(Block.Image(it)) }

                "h2", "h3" -> el.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { blocks.add(Block.Subhead(it)) }

                else -> el.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { blocks.add(Block.Para(it)) }
            }
        }
        // 兜底：图片有时包在 div 里而不在直接子节点
        if (blocks.none { it is Block.Image }) {
            body.select("img").forEach {
                val src = it.attr("abs:src").ifEmpty { it.attr("src") }
                if (src.isNotBlank()) blocks.add(Block.Image(src))
            }
        }
        // 抓到页面但正文为空也算解析失败，必须抛异常进备源
        if (blocks.isEmpty()) throw ArticleException.EmptyContent(name)
        return Article(title.ifEmpty { "每日一读" }, author, blocks, name)
    }

    private data class Endpoint(val url: String, val desktopUa: Boolean)

    private companion object {
        const val WWW = "https://www.dushu.com"
        const val MOBILE = "https://m.dushu.com"

        val CANDIDATES_DAILY = listOf(
            Endpoint("$WWW/meiwen/", desktopUa = true),
            Endpoint("$MOBILE/meiwen/", desktopUa = false),
        )
        val CANDIDATES_RANDOM = listOf(
            Endpoint("$WWW/meiwen/random/", desktopUa = true),
            Endpoint("$MOBILE/meiwen/random/", desktopUa = false),
        )
    }
}
