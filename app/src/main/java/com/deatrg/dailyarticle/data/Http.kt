package com.deatrg.dailyarticle.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 基于 OkHttp 的 HTTP 客户端：连接池复用、单次应用层重试、安全 GZIP 解码。
 *
 * 注意：不在这里做证书固定（CertificatePinner）。Pinner 只是系统信任校验通过后
 * 的额外校验，修不好“老系统缺根证书”的握手失败，反而会在 CA 轮换时让主源永久
 * 不可用。如需兼容老系统，应自定义 TrustManager，而非加 Pin。
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val DEFAULT_TIMEOUT_MS = 15_000

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                // 应用层重试：只补一次 5xx/429，不做退避睡眠。
                // 退避重试交给各 Source（如 Gushiwen 的 pool 重试），避免多层相乘
                // 把单次请求拖成几十秒。
                var response = chain.proceed(chain.request())
                if (!response.isSuccessful) {
                    val code = response.code
                    if (code in 500..599 || code == 429) {
                        response.close()
                        response = chain.proceed(chain.request())
                    }
                }
                response
            }
            .build()
    }

    /** 按超时克隆的 client 缓存：newBuilder 会共享连接池/Dispatcher，只是超时不同。 */
    private val clientCache = ConcurrentHashMap<Int, OkHttpClient>()

    private fun clientFor(timeoutMs: Int): OkHttpClient {
        if (timeoutMs <= 0 || timeoutMs == DEFAULT_TIMEOUT_MS) return baseClient
        return clientCache.getOrPut(timeoutMs) {
            baseClient.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
        }
    }

    /** 返回响应体文本；非 2xx 抛 [ArticleException.NetworkError]。 */
    fun get(url: String, desktopUa: Boolean = false, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (desktopUa) UA_DESKTOP else UA)
            .header("Accept-Encoding", "gzip")
            .header("Accept", "*/*")
            .build()

        return try {
            clientFor(timeoutMs).newCall(request).execute().use { response ->
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
    fun getJson(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): JSONObject =
        JSONObject(get(url, timeoutMs = timeoutMs))

    private fun ensureSuccessful(response: Response, url: String) {
        if (!response.isSuccessful) {
            throw ArticleException.NetworkError(url, IOException("HTTP ${response.code}"))
        }
    }

    /**
     * 安全读取响应体：检查 GZIP magic bytes 后再解码，
     * 避免 Content-Encoding 头与实际内容不匹配时崩溃。
     *
     * 说明：我们手动设置了 `Accept-Encoding: gzip`，OkHttp 的透明解压
     * （只在调用方没手动设该头时生效）不会启动，所以这里必须手动解。
     */
    private fun readBytes(response: Response): ByteArray {
        val bytes = response.body?.bytes() ?: return ByteArray(0)
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
