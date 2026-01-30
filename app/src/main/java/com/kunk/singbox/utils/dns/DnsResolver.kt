package com.kunk.singbox.utils.dns

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * DNS 解析结果
 */
data class DnsResolveResult(
    val ip: String?,
    val source: String,
    val error: String? = null
) {
    val isSuccess: Boolean get() = ip != null && error == null
}

/**
 * DoH (DNS over HTTPS) 解析器
 *
 * 支持通过 HTTPS 安全地解析域名，绕过本地 DNS 污染
 */
class DnsResolver(
    private val client: OkHttpClient = createDefaultClient()
) {
    companion object {
        private const val TAG = "DnsResolver"

        // 预定义的 DoH 服务器
        const val DOH_CLOUDFLARE = "https://1.1.1.1/dns-query"
        const val DOH_GOOGLE = "https://8.8.8.8/dns-query"
        const val DOH_ALIDNS = "https://223.5.5.5/dns-query"

        private val IPV4_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        private val IPV6_REGEX = Regex("^[0-9a-fA-F:]+$")

        private fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 判断是否为 IP 地址
         */
        fun isIpAddress(host: String): Boolean {
            return IPV4_REGEX.matches(host) || (host.contains(":") && IPV6_REGEX.matches(host))
        }
    }

    /**
     * 使用 DoH 解析域名
     *
     * @param domain 要解析的域名
     * @param dohServer DoH 服务器地址
     * @return 解析结果
     */
    suspend fun resolveViaDoH(
        domain: String,
        dohServer: String = DOH_CLOUDFLARE
    ): DnsResolveResult = withContext(Dispatchers.IO) {
        if (isIpAddress(domain)) {
            return@withContext DnsResolveResult(domain, "direct")
        }

        try {
            // 构建 DNS 查询报文 (A 记录)
            val query = buildDnsQuery(domain)

            val request = Request.Builder()
                .url(dohServer)
                .header("Accept", "application/dns-message")
                .header("Content-Type", "application/dns-message")
                .post(query.toRequestBody("application/dns-message".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DnsResolveResult(
                        ip = null,
                        source = "doh",
                        error = "HTTP ${response.code}"
                    )
                }

                val body = response.body?.bytes()
                if (body == null) {
                    return@withContext DnsResolveResult(
                        ip = null,
                        source = "doh",
                        error = "Empty response"
                    )
                }

                val ip = parseDnsResponse(body)
                if (ip != null) {
                    Log.d(TAG, "DoH resolved $domain -> $ip")
                    DnsResolveResult(ip, "doh")
                } else {
                    DnsResolveResult(null, "doh", "No A record found")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH resolve failed for $domain: ${e.message}")
            DnsResolveResult(null, "doh", e.message)
        }
    }

    /**
     * 使用系统 DNS 解析
     */
    suspend fun resolveViaSystem(domain: String): DnsResolveResult = withContext(Dispatchers.IO) {
        if (isIpAddress(domain)) {
            return@withContext DnsResolveResult(domain, "direct")
        }

        try {
            val addresses = InetAddress.getAllByName(domain)
            val ip = addresses.firstOrNull()?.hostAddress
            if (ip != null) {
                Log.d(TAG, "System resolved $domain -> $ip")
                DnsResolveResult(ip, "system")
            } else {
                DnsResolveResult(null, "system", "No address found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "System resolve failed for $domain: ${e.message}")
            DnsResolveResult(null, "system", e.message)
        }
    }

    /**
     * 竞速解析：同时启动 DoH 和系统 DNS，谁先成功用谁
     * 避免 DoH 超时时的长时间等待
     */
    suspend fun resolve(
        domain: String,
        dohServer: String? = DOH_CLOUDFLARE
    ): DnsResolveResult = withContext(Dispatchers.IO) {
        if (isIpAddress(domain)) {
            return@withContext DnsResolveResult(domain, "direct")
        }

        // 用 Channel 实现真正的竞速
        val resultChannel = Channel<DnsResolveResult>(2)
        var pendingCount = if (dohServer != null) 2 else 1

        coroutineScope {
            // 启动 DoH 解析
            if (dohServer != null) {
                launch {
                    val result = resolveViaDoH(domain, dohServer)
                    resultChannel.send(result)
                }
            }

            // 启动系统 DNS 解析
            launch {
                val result = resolveViaSystem(domain)
                resultChannel.send(result)
            }

            // 等待结果：收到成功结果立即返回，否则等所有完成
            var lastResult: DnsResolveResult? = null
            repeat(pendingCount) {
                val result = resultChannel.receive()
                if (result.isSuccess) {
                    resultChannel.close()
                    return@coroutineScope result
                }
                lastResult = result
            }
            resultChannel.close()
            lastResult ?: DnsResolveResult(null, "racing", "All resolvers failed")
        }
    }

    /**
     * 批量解析多个域名
     *
     * @param domains 域名列表
     * @param dohServer DoH 服务器
     * @param concurrency 并发数
     * @return 域名到解析结果的映射
     */
    suspend fun resolveBatch(
        domains: List<String>,
        dohServer: String? = DOH_CLOUDFLARE,
        concurrency: Int = 8
    ): Map<String, DnsResolveResult> = withContext(Dispatchers.IO) {
        val uniqueDomains = domains.filter { !isIpAddress(it) }.distinct()
        if (uniqueDomains.isEmpty()) {
            return@withContext emptyMap()
        }

        Log.d(TAG, "Batch resolving ${uniqueDomains.size} domains...")

        val semaphore = Semaphore(concurrency)
        val results = uniqueDomains.map { domain ->
            async {
                semaphore.withPermit {
                    domain to resolve(domain, dohServer)
                }
            }
        }.awaitAll()

        val resultMap = results.toMap()
        val successCount = resultMap.values.count { result -> result.isSuccess }
        Log.d(TAG, "Batch resolved: $successCount/${uniqueDomains.size} succeeded")

        resultMap
    }

    /**
     * 构建 DNS 查询报文 (A 记录)
     */
    private fun buildDnsQuery(domain: String): ByteArray {
        val buffer = ByteBuffer.allocate(512)

        // Transaction ID (random)
        buffer.putShort((System.currentTimeMillis() and 0xFFFF).toShort())

        // Flags: standard query, recursion desired
        buffer.putShort(0x0100.toShort())

        // Questions: 1, Answers: 0, Authority: 0, Additional: 0
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)

        // Question section
        val labels = domain.split(".")
        for (label in labels) {
            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray(Charsets.US_ASCII))
        }
        buffer.put(0) // End of name

        // Type: A (1)
        buffer.putShort(1)
        // Class: IN (1)
        buffer.putShort(1)

        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }

    /**
     * 解析 DNS 响应报文
     */
    private fun parseDnsResponse(data: ByteArray): String? {
        if (data.size < 12) return null

        val buffer = ByteBuffer.wrap(data)

        // Skip header (12 bytes)
        buffer.position(12)

        // Skip question section
        skipName(buffer)
        buffer.position(buffer.position() + 4) // Type + Class

        // Read answer count from header
        val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        // Parse answers
        repeat(answerCount) {
            if (buffer.remaining() < 12) return null

            skipName(buffer)

            val type = buffer.short.toInt() and 0xFFFF
            buffer.short // Class
            buffer.int // TTL
            val rdLength = buffer.short.toInt() and 0xFFFF

            if (type == 1 && rdLength == 4) {
                // A record - IPv4 address
                val ip = ByteArray(4)
                buffer.get(ip)
                return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
                    "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
            } else {
                // Skip this record
                buffer.position(buffer.position() + rdLength)
            }
        }

        return null
    }

    /**
     * 跳过 DNS 名称字段
     */
    private fun skipName(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val len = buffer.get().toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                // Compression pointer
                buffer.get()
                break
            }
            buffer.position(buffer.position() + len)
        }
    }
}
