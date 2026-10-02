package com.deatrg.dailyarticle.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArticleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(article: ArticleEntity)

    @Query("SELECT * FROM articles WHERE isDaily = 1 AND date = :date LIMIT 1")
    suspend fun loadDailyByDate(date: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE isDaily = 1 ORDER BY cachedAt DESC LIMIT 1")
    suspend fun loadLatestDaily(): ArticleEntity?

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun loadById(id: String): ArticleEntity?

    @Query("UPDATE articles SET scrollIndex = :index, scrollOffset = :offset WHERE id = :id")
    suspend fun updateScroll(id: String, index: Int, offset: Int)

    @Query("UPDATE articles SET scrollIndex = 0, scrollOffset = 0 WHERE id = :id")
    suspend fun clearScroll(id: String)

    @Query("DELETE FROM articles WHERE cachedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
