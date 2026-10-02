package com.deatrg.dailyarticle.data

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 基于 OkHttp 的 HTTP 客户端：连接池复用、自动重试、安全 GZIP 解码。
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 证书固定：dushu.com 使用 Certum Trusted Root CA，
     * 老版本 Android（< Android 12）可能缺少该根证书导致握手失败。
     * 通过固定中间 CA 的公钥哈希，即使系统信任库缺少根证书，
     * 也能验证证书链的合法性。
     *
     * 注意：如果 Certum 轮换中间 CA，需要更新此哈希值。
     */
    private val dushuCertificatePinner: CertificatePinner = CertificatePinner.Builder()
        .add("www.dushu.com", "sha256/92oK29/qv5N8xocT/H9kxxVqihg3OD2rlooJW9f7L3Y=")
        .add("m.dushu.com", "sha256/92oK29/qv5N8xocT/H9kxxVqihg3OD2rlooJW9f7L3Y=")
        .build()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .certificatePinner(dushuCertificatePinner)
            .addInterceptor { chain ->
                // 应用层重试：处理 5xx 和 429
                var response = chain.proceed(chain.request())
                var tryCount = 0
                while (!response.isSuccessful && tryCount < 3) {
                    val code = response.code
                    if (code !in 500..599 && code != 429) break
                    response.close()
                    tryCount++
                    // 指数退避：1s, 2s, 4s
                    Thread.sleep(1000L * (1L shl (tryCount - 1)))
                    response = chain.proceed(chain.request())
                }
                response
            }
            .build()
    }

    /** 返回响应体文本；非 2xx 抛 [ArticleException.NetworkError]。 */
    fun get(url: String, desktopUa: Boolean = false, timeoutMs: Int = 15_000): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (desktopUa) UA_DESKTOP else UA)
            .header("Accept-Encoding", "gzip")
            .header("Accept", "*/*")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                ensureSuccessful(response, url)
                val bytes = readBytes(response)
                if (bytes.isEmpty()) throw ArticleException.EmptyContent(url)
                val text = decode(bytes, response.header("Content-Type"))
                if (text.isBlank()) throw ArticleException.EmptyContent(url)
                text
            }
        } catch (e: IOException) {
            throw ArticleException.NetworkError(url, e)
        } catch (e: ArticleException) {
            throw e
        } catch (e: Exception) {
            throw ArticleException.NetworkError(url, e)
        }
    }

    /** GET 并解析为 JSON。 */
    fun getJson(url: String, timeoutMs: Int = 15_000): JSONObject =
        JSONObject(get(url, timeoutMs = timeoutMs))

    private fun ensureSuccessful(response: Response, url: String) {
        if (!response.isSuccessful) {
            throw ArticleException.NetworkError(url, IOException("HTTP ${response.code}"))
        }
    }

    /**
     * 安全读取响应体：检查 GZIP magic bytes 后再解码，
     * 避免 Content-Encoding 头与实际内容不匹配时崩溃。
     */
    private fun readBytes(response: Response): ByteArray {
        val body = response.body ?: return ByteArray(0)
        val source = body.source()
        val bytes = source.readByteArray()
        // GZIP magic bytes: 0x1f 0x8b
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        }
        return bytes
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
