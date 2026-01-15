package com.kunk.singbox.utils

import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import io.nekohasekai.libbox.FetchResult
import io.nekohasekai.libbox.BoxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内核级 HTTP 客户端
 *
 * 优势:
 * 1. 支持所有代理协议 (SS/VMess/VLESS/Trojan/Hysteria2...)
 * 2. 与代理流量共享连接池
 * 3. HTTP/2 多路复用
 * 4. 无需额外配置代理端口
 *
 * 使用场景:
 * - 订阅更新 (需要翻墙的订阅源)
 * - 规则集下载
 * - 任何需要走代理的 HTTP 请求
 */
object KernelHttpClient {
    private const val TAG = "KernelHttpClient"

    // 默认超时 30 秒
    private const val DEFAULT_TIMEOUT_MS = 30000

    /**
     * Fetch 结果封装
     */
    data class HttpResult(
        val success: Boolean,
        val statusCode: Int,
        val body: String,
        val error: String?
    ) {
        val isOk: Boolean get() = success && statusCode in 200..299

        companion object {
            fun fromLibbox(result: FetchResult?): HttpResult {
                if (result == null) {
                    return HttpResult(false, 0, "", "Null result from kernel")
                }
                val errorStr = result.error ?: ""
                return HttpResult(
                    success = result.isSuccess,
                    statusCode = result.statusCode.toInt(),
                    body = result.body ?: "",
                    error = if (errorStr.isNotEmpty()) errorStr else null
                )
            }

            fun error(message: String): HttpResult {
                return HttpResult(false, 0, "", message)
            }
        }
    }

    /**
     * 使用运行中的 VPN 服务发起请求
     *
     * @param url 请求 URL
     * @param outboundTag 使用的出站标签 (默认 "proxy")
     * @param timeoutMs 超时时间 (毫秒)
     * @return HttpResult
     */
    suspend fun fetch(
        url: String,
        outboundTag: String = "proxy",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {
        try {
            val boxService = BoxWrapperManager.getBoxService()
            if (boxService == null) {
                Log.w(TAG, "BoxService not available, falling back to OkHttp")
                return@withContext fetchWithOkHttp(url, timeoutMs)
            }

            Log.d(TAG, "Kernel fetch: $url via $outboundTag")
            val result = boxService.fetchURL(outboundTag, url, timeoutMs)
            HttpResult.fromLibbox(result)
        } catch (e: NoSuchMethodError) {
            Log.w(TAG, "Kernel fetch not available (old libbox), falling back to OkHttp")
            fetchWithOkHttp(url, timeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "Kernel fetch error: ${e.message}", e)
            HttpResult.error("Fetch error: ${e.message}")
        }
    }

    /**
     * 使用运行中的 VPN 服务发起请求 (带自定义 Headers)
     *
     * @param url 请求 URL
     * @param headers 请求头 Map
     * @param outboundTag 使用的出站标签
     * @param timeoutMs 超时时间 (毫秒)
     * @return HttpResult
     */
    suspend fun fetchWithHeaders(
        url: String,
        headers: Map<String, String>,
        outboundTag: String = "proxy",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {
        try {
            val boxService = BoxWrapperManager.getBoxService()
            if (boxService == null) {
                Log.w(TAG, "BoxService not available")
                return@withContext HttpResult.error("VPN service not running")
            }

            val headersStr = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
            Log.d(TAG, "Kernel fetch with headers: $url")
            val result = boxService.fetchURLWithHeaders(outboundTag, url, headersStr, timeoutMs)
            HttpResult.fromLibbox(result)
        } catch (e: NoSuchMethodError) {
            Log.w(TAG, "Kernel fetchWithHeaders not available")
            HttpResult.error("Kernel method not available")
        } catch (e: Exception) {
            Log.e(TAG, "Kernel fetch error: ${e.message}", e)
            HttpResult.error("Fetch error: ${e.message}")
        }
    }

    /**
     * 智能请求 - 自动选择最佳方式
     *
     * 1. 如果 VPN 运行中，使用内核 Fetch
     * 2. 如果 VPN 未运行，回退到 OkHttp 直连
     *
     * @param url 请求 URL
     * @param preferKernel 是否优先使用内核 (即使会稍慢)
     * @param timeoutMs 超时时间
     * @return HttpResult
     */
    suspend fun smartFetch(
        url: String,
        preferKernel: Boolean = true,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {
        val boxService = BoxWrapperManager.getBoxService()

        if (boxService != null && preferKernel) {
            // VPN 运行中，使用内核
            fetch(url, "proxy", timeoutMs)
        } else {
            // VPN 未运行，使用 OkHttp
            fetchWithOkHttp(url, timeoutMs)
        }
    }

    /**
     * 使用 OkHttp 发起请求 (回退方案)
     */
    private fun fetchWithOkHttp(url: String, timeoutMs: Int): HttpResult {
        return try {
            val client = NetworkClient.createClientWithTimeout(
                connectTimeoutSeconds = (timeoutMs / 1000).toLong(),
                readTimeoutSeconds = (timeoutMs / 1000).toLong()
            )

            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "KunBox/1.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            HttpResult(
                success = true,
                statusCode = response.code,
                body = body,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "OkHttp fetch error: ${e.message}")
            HttpResult.error("OkHttp error: ${e.message}")
        }
    }

    /**
     * 检查内核 Fetch 是否可用
     */
    fun isKernelFetchAvailable(): Boolean {
        return try {
            // 检查方法是否存在
            io.nekohasekai.libbox.BoxService::class.java.getMethod(
                "fetchURL",
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            true
        } catch (e: NoSuchMethodException) {
            false
        }
    }

    /**
     * 检查 VPN 是否运行中 (可使用内核 Fetch)
     */
    fun isVpnRunning(): Boolean {
        return BoxWrapperManager.getBoxService() != null
    }
}
