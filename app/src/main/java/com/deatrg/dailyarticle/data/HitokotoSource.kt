package com.deatrg.dailyarticle.data

/**
 * 最终保底：一言短句（v1.hitokoto.cn，独立域名）
 * 只要一句话，作为 Article 渲染，保证任何网络状况下都不白屏。
 */
class HitokotoSource(
    /**
     * 一言类型（https://hitokoto.cn）：a 动画，b 漫画，c 游戏，d 文学，
     * e 原创，f 来自网络，g 其他，h 影视，i 诗词，j 网易云，k 哲学，l 抖机灵。
     * 只取偏文学向的，保证保底出来的也是能读的句子。
     */
    private val types: List<String> = listOf("d", "i", "k", "h", "e"),
) : ArticleSource {

    override val name = "hitokoto.cn"

    override suspend fun fetchDaily(): Article = fetch(pickByDay())

    override suspend fun fetchRandom(): Article = fetch(types.random())

    /** 同一天同一类型，保证稳定。 */
    private fun pickByDay(): String {
        val idx = Math.floorMod(java.time.LocalDate.now().dayOfYear, types.size)
        return types[idx]
    }

    private fun fetch(type: String): Article {
        val o = Http.getJson("https://v1.hitokoto.cn/?c=$type&encode=json")
        val sentence = o.optString("hitokoto").trim()
        if (sentence.isEmpty()) throw ArticleException.EmptyContent(name)
        val from = o.optString("from").trim()
        val who = o.optString("from_who").trim()
        val blocks = mutableListOf<Block>(Block.Para(sentence))
        // 出处较长时单独一行呈现
        if (from.isNotEmpty()) blocks.add(Block.Subhead("出处：$from"))
        return Article(
            title = from.ifBlank { "一言" },
            author = who,
            blocks = blocks,
            source = name,
        )
    }
}
