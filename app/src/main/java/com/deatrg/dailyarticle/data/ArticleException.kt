package com.deatrg.dailyarticle.data

/**
 * 领域异常：区分网络错误、解析错误、空内容，便于上层针对性处理。
 */
sealed class ArticleException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** 网络请求失败（连接超时、HTTP 非 2xx 等） */
    class NetworkError(url: String, cause: Throwable? = null) :
        ArticleException("网络错误: $url", cause)

    /** 页面结构变化导致解析失败 */
    class ParseError(source: String, detail: String) :
        ArticleException("解析失败 [$source]: $detail")

    /** 抓到页面但正文为空 */
    class EmptyContent(source: String) :
        ArticleException("内容为空 [$source]")

    /** 所有数据源均失败 */
    class AllSourcesFailed(val errors: List<Throwable>) :
        ArticleException("全部数据源均失败 (${errors.size} 个)")
}
