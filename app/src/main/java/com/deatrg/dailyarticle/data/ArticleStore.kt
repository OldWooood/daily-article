package com.deatrg.dailyarticle.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 本地存储，两类数据：
 *
 * 1. **每日一篇**（daily）—— 同一天永远拿到同一篇，抓取失败时也用它兜底。
 * 2. **上次在读**（lastRead）—— 记住用户最后看的是哪一篇（可能是点过「随机一篇」
 *    之后的任意文章）以及滚动到第几段。退出 App 再进来时回到原处，
 *    而不是把用户弹回当天的「每日一篇」。
 */
object ArticleStore {

    private const val NAME = "article_store"

    private const val K_DAILY_DATE = "daily_date"
    private const val K_DAILY = "daily_article"

    private const val K_LAST_DATE = "last_date"
    private const val K_LAST_RANDOM = "last_is_random"
    private const val K_LAST = "last_article"
    private const val K_SCROLL_INDEX = "scroll_index"
    private const val K_SCROLL_OFFSET = "scroll_offset"

    /** 恢复出来的状态。 */
    data class Restored(
        val article: Article,
        val isRandom: Boolean,
        /** 这篇被读到的日期，yyyy-MM-dd */
        val date: String,
        val scrollIndex: Int,
        val scrollOffset: Int,
    )

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun today(): String = LocalDate.now().toString()

    // ---------------- 每日一篇 ----------------

    fun saveDaily(ctx: Context, a: Article) {
        sp(ctx).edit()
            .putString(K_DAILY_DATE, today())
            .putString(K_DAILY, encode(a))
            .apply()
    }

    /** 今天缓存的每日文章；不是今天的（或没缓存过）返回 null。 */
    fun loadDailyToday(ctx: Context): Article? {
        val p = sp(ctx)
        if (p.getString(K_DAILY_DATE, null) != today()) return null
        return decode(p.getString(K_DAILY, null))
    }

    /** 最近一次抓到的每日文章（不限日期），抓取彻底失败时兜底。 */
    fun loadDailyAny(ctx: Context): Article? = decode(sp(ctx).getString(K_DAILY, null))

    // ---------------- 上次在读 ----------------

    fun saveLastRead(ctx: Context, a: Article, isRandom: Boolean) {
        sp(ctx).edit()
            .putString(K_LAST_DATE, today())
            .putBoolean(K_LAST_RANDOM, isRandom)
            .putString(K_LAST, encode(a))
            .putInt(K_SCROLL_INDEX, 0)
            .putInt(K_SCROLL_OFFSET, 0)
            .apply()
    }

    /** 读取上次在读的篇目与阅读位置；从来没有读过返回 null。 */
    fun loadLastRead(ctx: Context): Restored? {
        val p = sp(ctx)
        val article = decode(p.getString(K_LAST, null)) ?: return null
        return Restored(
            article = article,
            isRandom = p.getBoolean(K_LAST_RANDOM, false),
            date = p.getString(K_LAST_DATE, "").orEmpty(),
            scrollIndex = p.getInt(K_SCROLL_INDEX, 0).coerceAtLeast(0),
            scrollOffset = p.getInt(K_SCROLL_OFFSET, 0),
        )
    }

    /** 单独更新阅读位置（滚动时高频调用）。 */
    fun saveScroll(ctx: Context, index: Int, offset: Int) {
        sp(ctx).edit()
            .putInt(K_SCROLL_INDEX, index.coerceAtLeast(0))
            .putInt(K_SCROLL_OFFSET, offset)
            .apply()
    }

    /**
     * 用户按返回键退出时调用：把阅读位置清零，下次进来回到文章顶部。
     * 必须用同步 commit：Activity 即将销毁，apply 的异步写盘可能丢失。
     */
    fun clearScroll(ctx: Context) {
        sp(ctx).edit()
            .putInt(K_SCROLL_INDEX, 0)
            .putInt(K_SCROLL_OFFSET, 0)
            .commit()
    }

    // ---------------- 编解码 ----------------

    private fun encode(a: Article): String {
        val arr = JSONArray()
        for (b in a.blocks) {
            when (b) {
                is Block.Para -> arr.put(JSONObject().put("t", "p").put("c", b.text))
                is Block.Subhead -> arr.put(JSONObject().put("t", "s").put("c", b.text))
                is Block.Image -> arr.put(
                    JSONObject().put("t", "i").put("c", b.url).put("d", b.caption)
                )
            }
        }
        return JSONObject()
            .put("title", a.title)
            .put("author", a.author)
            .put("source", a.source)
            .put("blocks", arr)
            .toString()
    }

    private fun decode(raw: String?): Article? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("blocks") ?: JSONArray()
            val blocks = mutableListOf<Block>()
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                when (b.optString("t")) {
                    "p" -> blocks.add(Block.Para(b.optString("c")))
                    "s" -> blocks.add(Block.Subhead(b.optString("c")))
                    "i" -> blocks.add(Block.Image(b.optString("c"), b.optString("d")))
                }
            }
            if (blocks.isEmpty()) null
            else Article(
                title = o.optString("title"),
                author = o.optString("author"),
                blocks = blocks,
                source = o.optString("source"),
            )
        }.getOrNull()
    }
}
