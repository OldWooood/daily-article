package com.deatrg.dailyarticle.di

import android.content.Context
import com.deatrg.dailyarticle.data.ArticleRepository
import com.deatrg.dailyarticle.data.ArticleStore
import com.deatrg.dailyarticle.data.DuwenzhangSource
import com.deatrg.dailyarticle.data.DushuSource
import com.deatrg.dailyarticle.data.GushiwenSource
import com.deatrg.dailyarticle.data.HitokotoSource
import com.deatrg.dailyarticle.data.OneSource
import com.deatrg.dailyarticle.data.SqliteArticleStore
import com.deatrg.dailyarticle.data.SanwenwangSource
import com.deatrg.dailyarticle.data.SeventySecondsSource
import com.deatrg.dailyarticle.data.SourceChain
import com.deatrg.dailyarticle.data.ZhihuSource

/**
 * 极简 DI 容器：手动管理依赖图。
 * 后续可迁移到 Hilt/Koin，但当前规模下手动 DI 足够。
 */
object AppModule {

    @Volatile
    private var repository: ArticleRepository? = null

    fun provideRepository(context: Context): ArticleRepository {
        return repository ?: synchronized(this) {
            repository ?: createRepository(context).also { repository = it }
        }
    }

    private fun createRepository(context: Context): ArticleRepository {
        val store: ArticleStore = SqliteArticleStore(context)

        val chain = SourceChain(
            listOf(
                DushuSource(),
                SanwenwangSource(),
                DuwenzhangSource(),
                OneSource(),
                ZhihuSource(),
                GushiwenSource(),
                SeventySecondsSource(),
                HitokotoSource(),
            )
        )
        return ArticleRepository(store, chain)
    }
}
