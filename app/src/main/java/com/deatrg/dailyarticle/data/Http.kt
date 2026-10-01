package com.deatrg.dailyarticle.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 系统自带的极简 HTTP 客户端，零额外依赖。 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 桌面 UA：dushu.com 会把移动端 UA 跳到 m 站，桌面 UA 才留在 www 站拿到完整页面。 */
    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 返回响应体文本；非 2xx 直接抛异常，交由故障转移链接管。 */
    fun get(url: String, desktopUa: Boolean = false, timeoutMs: Int = 15_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", if (desktopUa) UA_DESKTOP else UA)
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Accept", "*/*")
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val bytes = readBytes(
                (if (code in 200..299) conn.inputStream else conn.errorStream),
                conn.contentEncoding,
            )
            if (code !in 200..299) throw IllegalStateException("HTTP $code @ $url")
            if (bytes.isEmpty()) throw IllegalStateException("响应为空")
            // 短文学等老站是 GB2312/GBK，必须按实际编码解码，否则中文全是乱码
            val text = decode(bytes, conn.contentType)
            if (text.isBlank()) throw IllegalStateException("响应为空")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** GET 并解析为 JSON，顺带校验业务状态码。 */
    fun getJson(url: String, timeoutMs: Int = 15_000): JSONObject =
        JSONObject(get(url, timeoutMs = timeoutMs))

    private fun readBytes(stream: java.io.InputStream?, encoding: String?): ByteArray {
        if (stream == null) return ByteArray(0)
        val raw = if (encoding.equals("gzip", true)) GZIPInputStream(stream) else stream
        return raw.use { it.readBytes() }
    }

    /** 按 响应头 charset → HTML meta charset → UTF-8 的顺序解码。 */
    private fun decode(bytes: ByteArray, contentType: String?): String {
        val name = charsetOf(contentType) ?: metaCharset(bytes) ?: Charsets.UTF_8.name()
        return runCatching { String(bytes, charset(name)) }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    private fun charsetOf(contentType: String?): String? =
        contentType?.let {
            Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)
        }

    /** 只扫描前 4KB 的 meta，避免对全文做正则。 */
    private fun metaCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return Regex("<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.getOrNull(1)
    }
}
