package com.deatrg.dailyarticle.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 文章缓存的 SQLite 存储（无代码生成，不依赖 KSP/KAPT）。
 * 表结构与之前 Room 版一致，便于以后迁回 Room。
 */
class ArticleDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS articles (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                source TEXT NOT NULL,
                blocks TEXT NOT NULL,
                cachedAt INTEGER NOT NULL,
                scrollIndex INTEGER NOT NULL DEFAULT 0,
                scrollOffset INTEGER NOT NULL DEFAULT 0,
                isDaily INTEGER NOT NULL DEFAULT 0,
                date TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_date ON articles(isDaily, date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cached ON articles(cachedAt)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 起步：直接重建（缓存数据可丢）。
        db.execSQL("DROP TABLE IF EXISTS articles")
        onCreate(db)
    }

    companion object {
        private const val NAME = "daily_article.db"
        private const val VERSION = 1

        @Volatile
        private var INSTANCE: ArticleDbHelper? = null

        fun getInstance(context: Context): ArticleDbHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArticleDbHelper(context).also { INSTANCE = it }
            }
        }
    }
}
