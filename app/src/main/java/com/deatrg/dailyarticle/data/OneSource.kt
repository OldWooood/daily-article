package com.deatrg.dailyarticle.data

import org.jsoup.Jsoup
import java.time.LocalDate
import kotlin.random.Random

/**
 * 备源：ONE·一个 官方 JSON 接口（v3.wufazhuce.com:8000，独立域名）
 * 内容库已冻结归档，接口不会再变，比爬 HTML 稳。
 * 列表 /api/channel/reading/more/{游标} 首屏游标 0，翻页游标取末条 id；
 * 详情 /api/essay/{item_id} 的 data.hp_content 为全文 HTML。
 */
class OneSource : ArticleSource {

    override val name = "wufazhuce.com"

    private data class Item(val id: String, val title: String, val author: String)

    override fun fetchDaily(): Article {
        val pool = pool()
        // 按 dayOfYear 取模，同一天命中同一篇
        val idx = Math.floorMod(LocalDate.now().dayOfYear, pool.size)
        return essay(pool, idx)
    }

    override fun fetchRandom(): Article {
        val pool = pool()
        return essay(pool, Random.nextInt(pool.size))
    }

    /** 抓 3 页约 30 篇做候选池；单页失败不影响已有成果。 */
    private fun pool(): List<Item> {
        val out = mutableListOf<Item>()
        var cursor = "0"
        repeat(PAGES) {
            val arr = Http.getJson("$LIST_BASE/more/$cursor").optJSONArray("data")
                ?: throw ArticleException.ParseError(name, "列表字段缺失")
            if (arr.length() == 0) return@repeat
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("item_id")
                if (id.isNotBlank()) {
                    out.add(
                        Item(
                            id,
                            o.optString("title"),
                            o.optJSONObject("author")?.optString("user_name").orEmpty()
                        )
                    )
                }
            }
            cursor = arr.optJSONObject(arr.length() - 1)?.optString("id").orEmpty()
                .ifBlank { cursor }
        }
        if (out.isEmpty()) throw ArticleException.EmptyContent(name)
        return out
    }

    /**
     * 取详情。列表里可能混入已下线的 item_id（详情接口直接 404），
     * 这时顺着候选池往下试，而不是让整个源失败。
     */
    private fun essay(pool: List<Item>, startIdx: Int): Article {
        var last: Throwable = ArticleException.ParseError(name, "没有可用条目")
        for (offset in pool.indices) {
            val item = pool[(startIdx + offset) % pool.size]
            val result = runCatching { fetchEssay(item) }
            result.onSuccess { return it }
            last = result.exceptionOrNull() ?: last
        }
        throw last
    }

    private fun fetchEssay(item: Item): Article {
        val d = Http.getJson("$ESSAY_BASE/${item.id}").optJSONObject("data")
            ?: throw ArticleException.ParseError(name, "详情字段缺失: ${item.id}")
        val title = d.optString("hp_title").ifBlank { item.title }.ifBlank { "ONE·一个" }
        val author = d.optString("hp_author").ifBlank { item.author }
        val content = d.optString("hp_content")

        val blocks = mutableListOf<Block>()
        // 传 baseUri 让 abs:src 能补全正文里的相对图片路径
        val body = Jsoup.parseBodyFragment(content, ESSAY_BASE)
        body.select("img").forEach { img ->
            val src = img.attr("abs:src").ifBlank { img.attr("src") }
            if (src.isNotBlank()) blocks.add(Block.Image(src))
        }
        // hp_content 多为 <p> 包裹，直接取 p；不足时按 <br> 切分兜底
        val paras = body.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) {
            content.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .split("\n")
                .map { it.replace(Regex("<[^>]+>"), "").trim() }
                .filter { it.isNotEmpty() }
                .forEach { blocks.add(Block.Para(it)) }
        } else {
            paras.forEach { blocks.add(Block.Para(it)) }
        }
        if (blocks.isEmpty()) throw ArticleException.EmptyContent(name)
        return Article(title, author, blocks, name)
    }

    private companion object {
        /** 列表接口：/api/channel/reading/more/{游标} */
        const val LIST_BASE = "http://v3.wufazhuce.com:8000/api/channel/reading"
        /** 详情接口：/api/essay/{item_id}，注意不在 channel/reading 下面 */
        const val ESSAY_BASE = "http://v3.wufazhuce.com:8000/api/essay"
        const val PAGES = 3
    }
}
