package com.deatrg.dailyarticle.data

import android.content.Context
import android.content.SharedPreferences
import com.deatrg.dailyarticle.data.db.ArticleDao
import com.deatrg.dailyarticle.data.db.ArticleDatabase
import com.deatrg.dailyarticle.data.db.ArticleEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 文章存储接口。实现类必须做到线程安全。
 */
interface ArticleStore {
    /** 保存每日文章 */
    suspend fun saveDaily(ctx: Context, a: Article)

    /** 加载今天的每日文章；不是今天的（或没缓存过）返回 null */
    suspend fun loadDailyToday(ctx: Context): Article?

    /** 加载最近一次缓存的每日文章（不限日期），兜底用 */
    suspend fun loadDailyAny(ctx: Context): Article?

    /** 保存上次在读的文章 */
    suspend fun saveLastRead(ctx: Context, a: Article, isRandom: Boolean)

    /** 加载上次在读的文章与阅读位置 */
    suspend fun loadLastRead(ctx: Context): Restored?

    /** 更新阅读位置 */
    suspend fun saveScroll(ctx: Context, index: Int, offset: Int)

    /** 清除阅读位置（返回键退出时调用，同步写盘） */
    fun clearScroll(ctx: Context)

    /** 恢复出来的状态 */
    data class Restored(
        val article: Article,
        val isRandom: Boolean,
        /** 这篇被读到的日期，yyyy-MM-dd */
        val date: String,
        val scrollIndex: Int,
        val scrollOffset: Int,
    )
}

/**
 * Room 实现：文章数据存 Room，阅读位置存 SharedPreferences（高频小数据）。
 */
class RoomArticleStore(context: Context) : ArticleStore {

    private val appContext = context.applicationContext
    private val dao: ArticleDao = ArticleDatabase.getInstance(appContext).articleDao()

    // 阅读位置仍用 SharedPreferences：高频小数据，apply() 异步写盘更合适
    private val sp: SharedPreferences =
        appContext.getSharedPreferences("article_scroll", Context.MODE_PRIVATE)

    private val currentArticleId: String?
        get() = sp.getString(K_CURRENT_ID, null)

    override suspend fun saveDaily(ctx: Context, a: Article) {
        val entity = a.toEntity(
            id = "daily_${LocalDate.now()}",
            isDaily = true,
            date = LocalDate.now().toString()
        )
        dao.insert(entity)
    }

    override suspend fun loadDailyToday(ctx: Context): Article? {
        val today = LocalDate.now().toString()
        return dao.loadDailyByDate(today)?.toArticle()
    }

    override suspend fun loadDailyAny(ctx: Context): Article? {
        return dao.loadLatestDaily()?.toArticle()
    }

    override suspend fun saveLastRead(ctx: Context, a: Article, isRandom: Boolean) {
        val id = a.id()
        sp.edit()
            .putString(K_CURRENT_ID, id)
            .putString(K_LAST_DATE, LocalDate.now().toString())
            .putBoolean(K_LAST_RANDOM, isRandom)
            .putInt(K_SCROLL_INDEX, 0)
            .putInt(K_SCROLL_OFFSET, 0)
            .apply()
        // 同时存入 Room 以便跨天恢复
        val entity = a.toEntity(
            id = id,
            isDaily = false,
            date = LocalDate.now().toString()
        )
        dao.insert(entity)
    }

    override suspend fun loadLastRead(ctx: Context): ArticleStore.Restored? {
        val id = currentArticleId ?: return null
        val entity = dao.loadById(id) ?: return null
        return ArticleStore.Restored(
            article = entity.toArticle(),
            isRandom = sp.getBoolean(K_LAST_RANDOM, false),
            date = sp.getString(K_LAST_DATE, "").orEmpty(),
            scrollIndex = sp.getInt(K_SCROLL_INDEX, 0).coerceAtLeast(0),
            scrollOffset = sp.getInt(K_SCROLL_OFFSET, 0),
        )
    }

    override suspend fun saveScroll(ctx: Context, index: Int, offset: Int) {
        val id = currentArticleId ?: return
        sp.edit()
            .putInt(K_SCROLL_INDEX, index.coerceAtLeast(0))
            .putInt(K_SCROLL_OFFSET, offset)
            .apply()
        dao.updateScroll(id, index.coerceAtLeast(0), offset)
    }

    override fun clearScroll(ctx: Context) {
        val id = currentArticleId
        sp.edit()
            .putInt(K_SCROLL_INDEX, 0)
            .putInt(K_SCROLL_OFFSET, 0)
            .commit() // 同步写盘
        id?.let { dao.clearScroll(it) }
    }

    // ---------------- 转换 ----------------

    private fun Article.id(): String = "article_${title.hashCode()}_${blocks.size}"

    private fun Article.toEntity(id: String, isDaily: Boolean, date: String): ArticleEntity {
        val arr = JSONArray()
        for (b in blocks) {
            when (b) {
                is Block.Para -> arr.put(JSONObject().put("t", "p").put("c", b.text))
                is Block.Subhead -> arr.put(JSONObject().put("t", "s").put("c", b.text))
                is Block.Image -> arr.put(
                    JSONObject().put("t", "i").put("c", b.url).put("d", b.caption)
                )
            }
        }
        val json = JSONObject()
            .put("title", title)
            .put("author", author)
            .put("source", source)
            .put("blocks", arr)
            .toString()
        return ArticleEntity(
            id = id,
            title = title,
            author = author,
            source = source,
            blocks = json,
            cachedAt = System.currentTimeMillis(),
            isDaily = isDaily,
            date = date
        )
    }

    private fun ArticleEntity.toArticle(): Article {
        return runCatching {
            val o = JSONObject(blocks)
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
        }.getOrNull() ?: Article(title = title, author = author, blocks = emptyList(), source = source)
    }

    companion object {
        private const val K_CURRENT_ID = "current_id"
        private const val K_LAST_DATE = "last_date"
        private const val K_LAST_RANDOM = "last_is_random"
        private const val K_SCROLL_INDEX = "scroll_index"
        private const val K_SCROLL_OFFSET = "scroll_offset"
    }
}
