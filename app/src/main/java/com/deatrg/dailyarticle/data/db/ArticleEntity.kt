package com.deatrg.dailyarticle.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 实体：存储文章和阅读位置。
 * 替代原 SharedPreferences 方案，支持更大数据量和类型安全。
 */
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val source: String,
    /** Block 列表的 JSON 序列化 */
    val blocks: String,
    /** 缓存时间戳 */
    val cachedAt: Long,
    /** 阅读位置：条目 index */
    val scrollIndex: Int = 0,
    /** 阅读位置：像素偏移 */
    val scrollOffset: Int = 0,
    /** 是否为每日文章 */
    val isDaily: Boolean = false,
    /** 文章日期 yyyy-MM-dd */
    val date: String = ""
)
