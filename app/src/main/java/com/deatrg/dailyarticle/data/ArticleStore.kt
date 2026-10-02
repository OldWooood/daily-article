package com.deatrg.dailyarticle.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import com.deatrg.dailyarticle.data.db.ArticleDbHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
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

    /**
     * 清除阅读位置（返回键退出时调用）。
     * SP 部分同步 commit，DB 部分挂起写：调用方在 viewModelScope 里调即可，
     * 位置的唯一真相来源是 SP，DB 的 scroll 列只是备份，异步落盘不影响下次从顶部读。
     */
    suspend fun clearScroll(ctx: Context)

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

/** DB 行。blocks 是 Block 列表的 JSON（含 title/author/source，便于直接还原 Article）。 */
private data class ArticleRow(
    val id: String,
    val title: String,
    val author: String,
    val source: String,
    val blocks: String,
    val cachedAt: Long,
    val isDaily: Boolean,
    val date: String,
)

/**
 * SQLite 实现：文章数据存 DB，阅读位置以 SharedPreferences 为准（高频小数据）。
 * 不用 Room/KSP，避免 AGP 内置 Kotlin 与 KSP 的版本三角冲突；表结构与 Room 版一致。
 */
class SqliteArticleStore(context: Context) : ArticleStore {

    private val appContext = context.applicationContext
    private val helper: ArticleDbHelper = ArticleDbHelper.getInstance(appContext)

    // 阅读位置仍用 SharedPreferences：高频小数据，apply() 异步写盘更合适
    private val sp: SharedPreferences =
        appContext.getSharedPreferences("article_scroll", Context.MODE_PRIVATE)

    private val currentArticleId: String?
        get() = sp.getString(K_CURRENT_ID, null)

    override suspend fun saveDaily(ctx: Context, a: Article) {
        val today = LocalDate.now().toString()
        upsert(
            ArticleRow(
                id = "daily_$today",
                title = a.title,
                author = a.author,
                source = a.source,
                blocks = encode(a),
                cachedAt = System.currentTimeMillis(),
                isDaily = true,
                date = today,
            )
        )
        prune()
    }

    override suspend fun loadDailyToday(ctx: Context): Article? {
        val today = LocalDate.now().toString()
        return queryOne(
            selection = "isDaily = 1 AND date = ?",
            args = arrayOf(today),
            orderBy = null,
        )?.toArticle()
    }

    override suspend fun loadDailyAny(ctx: Context): Article? {
        return queryOne(
            selection = "isDaily = 1",
            args = null,
            orderBy = "cachedAt DESC",
        )?.toArticle()
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
        // 同时存入 DB 以便跨天恢复
        upsert(
            ArticleRow(
                id = id,
                title = a.title,
                author = a.author,
                source = a.source,
                blocks = encode(a),
                cachedAt = System.currentTimeMillis(),
                isDaily = false,
                date = LocalDate.now().toString(),
            )
        )
        prune()
    }

    override suspend fun loadLastRead(ctx: Context): ArticleStore.Restored? {
        val id = currentArticleId ?: return null
        val row = queryById(id) ?: return null
        val article = row.toArticle() ?: return null
        return ArticleStore.Restored(
            article = article,
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
        updateScroll(id, index.coerceAtLeast(0), offset)
    }

    override suspend fun clearScroll(ctx: Context) {
        val id = currentArticleId
        sp.edit()
            .putInt(K_SCROLL_INDEX, 0)
            .putInt(K_SCROLL_OFFSET, 0)
            .commit() // 同步写盘：Activity 即将销毁，SP 必须落盘
        // DB 只是备份位置，挂起写即可（SP 才是真相来源）
        if (id != null) updateScroll(id, 0, 0)
    }

    // ---------------- DB 原语 ----------------

    private fun upsert(row: ArticleRow) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("id", row.id)
            put("title", row.title)
            put("author", row.author)
            put("source", row.source)
            put("blocks", row.blocks)
            put("cachedAt", row.cachedAt)
            put("scrollIndex", 0)
            put("scrollOffset", 0)
            put("isDaily", if (row.isDaily) 1 else 0)
            put("date", row.date)
        }
        // INSERT OR REPLACE：阅读位置真相在 SP，DB 的 scroll 列只是备份，置零即可。
        db.insertWithOnConflict("articles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun queryById(id: String): ArticleRow? = queryOne(
        selection = "id = ?",
        args = arrayOf(id),
        orderBy = null,
    )

    private fun queryOne(selection: String?, args: Array<String>?, orderBy: String?): ArticleRow? {
        val db = helper.readableDatabase
        db.query(
            "articles", COLUMNS, selection, args, null, null, orderBy, "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return rowOf(c)
        }
    }

    private fun updateScroll(id: String, index: Int, offset: Int) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("scrollIndex", index)
            put("scrollOffset", offset)
        }
        db.update("articles", values, "id = ?", arrayOf(id))
    }

    /** 删除 30 天前的缓存，防止随机文章无限堆积。最近的每日文章不受影响（兜底用）。 */
    private fun prune() {
        runCatching {
            helper.writableDatabase.delete(
                "articles", "cachedAt < ?", arrayOf((System.currentTimeMillis() - RETENTION_MS).toString())
            )
        }
    }

    // ---------------- 转换 ----------------

    /**
     * 内容稳定的文章 ID：标题/作者/来源 + 前 5 个块的 SHA-256。
     * 原来的 title.hashCode + blocks.size 碰撞率太高（同标题同段数即撞车），
     * 会导致 loadLastRead 取错文章。
     */
    private fun Article.id(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder()
        sb.append(title).append('\n').append(author).append('\n').append(source).append('\n')
        for (b in blocks.take(5)) {
            when (b) {
                is Block.Para -> sb.append('p').append(b.text.take(200))
                is Block.Subhead -> sb.append('s').append(b.text.take(200))
                is Block.Image -> sb.append('i').append(b.url.take(200))
            }
            sb.append('\n')
        }
        val hex = digest.digest(sb.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        return "article_$hex"
    }

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

    /**
     * 解析失败或正文为空时返回 null（调用方按缓存未命中处理，走网络），
     * 绝不返回空 blocks 的 Article，避免白屏却被当成缓存命中。
     */
    private fun ArticleRow.toArticle(): Article? {
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
        }.getOrNull()
    }

    companion object {
        private const val K_CURRENT_ID = "current_id"
        private const val K_LAST_DATE = "last_date"
        private const val K_LAST_RANDOM = "last_is_random"
        private const val K_SCROLL_INDEX = "scroll_index"
        private const val K_SCROLL_OFFSET = "scroll_offset"
        private const val RETENTION_MS = 30L * 24 * 3600 * 1000
        private val COLUMNS = arrayOf(
            "id", "title", "author", "source", "blocks", "cachedAt", "isDaily", "date"
        )

        private fun rowOf(c: android.database.Cursor): ArticleRow {
            return ArticleRow(
                id = c.getString(0),
                title = c.getString(1),
                author = c.getString(2),
                source = c.getString(3),
                blocks = c.getString(4),
                cachedAt = c.getLong(5),
                isDaily = c.getInt(6) == 1,
                date = c.getString(7),
            )
        }
    }
}
