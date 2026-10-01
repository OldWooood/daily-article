package com.deatrg.dailyarticle.data

/** 正文块：纯文本 / 小标题 / 图片 */
sealed interface Block {
    data class Para(val text: String) : Block
    data class Subhead(val text: String) : Block
    data class Image(val url: String, val caption: String = "") : Block
}

data class Article(
    val title: String,
    val author: String,
    val blocks: List<Block>,
    /** 内容来源域名，仅用于页脚小字标注与排查故障转移 */
    val source: String = "",
)
