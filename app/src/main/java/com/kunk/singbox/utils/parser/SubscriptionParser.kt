package com.kunk.singbox.utils.parser

import android.util.Log
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 订阅转换引擎接口
 */
interface SubscriptionParser {
    /**
     * 判断是否能解析该内容
     */
    fun canParse(content: String): Boolean

    /**
     * 解析内容并返回 SingBoxConfig
     */
    fun parse(content: String): SingBoxConfig?
}

/**
 * DNS 预解析缓存
 * 用于加速节点连接，避免 DNS 污染
 */
object DnsResolveCache {
    private const val TAG = "DnsResolveCache"

    // 域名 -> IP 地址缓存
    private val cache = ConcurrentHashMap<String, String>()

    // 解析失败的域名（避免重复尝试）
    private val failedDomains = ConcurrentHashMap<String, Long>()

    // 失败重试间隔 (5 分钟)
    private const val RETRY_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * 获取缓存的 IP 地址
     */
    fun getResolvedIp(domain: String): String? = cache[domain]

    /**
     * 预解析域名列表
     * @param domains 需要解析的域名列表
     * @return 解析成功的数量
     */
    suspend fun preResolve(domains: List<String>): Int = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        val toResolve = domains.filter { domain ->
            // 跳过已缓存的
            if (cache.containsKey(domain)) return@filter false
            // 跳过最近失败的
            val failedTime = failedDomains[domain]
            if (failedTime != null && currentTime - failedTime < RETRY_INTERVAL_MS) {
                return@filter false
            }
            // 跳过已经是 IP 地址的
            if (isIpAddress(domain)) return@filter false
            true
        }.distinct()

        if (toResolve.isEmpty()) return@withContext 0

        Log.d(TAG, "Pre-resolving ${toResolve.size} domains...")

        val results = toResolve.map { domain ->
            async {
                try {
                    val addresses = InetAddress.getAllByName(domain)
                    val ip = addresses.firstOrNull()?.hostAddress
                    if (ip != null) {
                        cache[domain] = ip
                        Log.d(TAG, "Resolved $domain -> $ip")
                        1
                    } else {
                        failedDomains[domain] = currentTime
                        0
                    }
                } catch (e: Exception) {
                    failedDomains[domain] = currentTime
                    Log.w(TAG, "Failed to resolve $domain: ${e.message}")
                    0
                }
            }
        }.awaitAll()

        val successCount = results.sum()
        Log.d(TAG, "Pre-resolved $successCount/${toResolve.size} domains")
        successCount
    }

    /**
     * 从节点列表中提取所有需要解析的域名
     */
    fun extractDomains(outbounds: List<Outbound>): List<String> {
        return outbounds.mapNotNull { outbound ->
            val server = outbound.server ?: return@mapNotNull null
            // 跳过 IP 地址
            if (isIpAddress(server)) return@mapNotNull null
            server
        }.distinct()
    }

    /**
     * 判断是否为 IP 地址
     */
    private fun isIpAddress(host: String): Boolean {
        // IPv4 简单判断
        if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
            return true
        }
        // IPv6 判断
        if (host.contains(":") && !host.contains(".")) {
            return true
        }
        return false
    }

    /**
     * 清空缓存
     */
    fun clear() {
        cache.clear()
        failedDomains.clear()
    }

    /**
     * 获取缓存统计
     */
    fun getStats(): Pair<Int, Int> = Pair(cache.size, failedDomains.size)
}

/**
 * 订阅解析管理器
 */
class SubscriptionManager(private val parsers: List<SubscriptionParser>) {

    companion object {
        private const val TAG = "SubscriptionManager"

        /**
         * 生成节点去重 key
         * 基于 server:port:type 组合，相同组合视为重复节点
         */
        private fun getDeduplicationKey(outbound: Outbound): String? {
            val server = outbound.server ?: return null
            val port = outbound.serverPort ?: return null
            val type = outbound.type

            // 对于 selector/urltest 类型，不参与去重
            if (type == "selector" || type == "urltest" || type == "direct" || type == "block" || type == "dns") {
                return null
            }

            return "$type://$server:$port"
        }

        /**
         * 对节点列表进行去重
         * 保留第一个出现的节点，后续重复节点被忽略
         */
        fun deduplicateOutbounds(outbounds: List<Outbound>): List<Outbound> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<Outbound>()
            var duplicateCount = 0

            for (outbound in outbounds) {
                val key = getDeduplicationKey(outbound)
                if (key == null) {
                    // 非代理节点（selector/urltest/direct 等），直接保留
                    result.add(outbound)
                } else if (seen.add(key)) {
                    // 第一次见到这个 key，保留
                    result.add(outbound)
                } else {
                    // 重复节点，跳过
                    duplicateCount++
                }
            }

            if (duplicateCount > 0) {
                Log.d(TAG, "Deduplicated $duplicateCount duplicate nodes, ${result.size} unique nodes remaining")
            }

            return result
        }
    }

    /**
     * 解析订阅内容
     */
    fun parse(content: String): SingBoxConfig? {
        for (parser in parsers) {
            if (parser.canParse(content)) {
                try {
                    val config = parser.parse(content)
                    if (config != null && !config.outbounds.isNullOrEmpty()) {
                        // 对节点进行去重
                        val deduplicatedOutbounds = deduplicateOutbounds(config.outbounds)
                        return config.copy(outbounds = deduplicatedOutbounds)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parser ${parser.javaClass.simpleName} failed", e)
                }
            }
        }
        return null
    }

    /**
     * 解析订阅内容并预解析 DNS
     * @param content 订阅内容
     * @param preResolveDns 是否预解析 DNS
     * @return 解析结果和 DNS 解析数量
     */
    suspend fun parseWithDnsPreResolve(content: String, preResolveDns: Boolean = true): Pair<SingBoxConfig?, Int> {
        val config = parse(content)
        if (config == null || config.outbounds.isNullOrEmpty()) {
            return Pair(null, 0)
        }

        if (!preResolveDns) {
            return Pair(config, 0)
        }

        // 提取域名并预解析
        val domains = DnsResolveCache.extractDomains(config.outbounds)
        val resolvedCount = DnsResolveCache.preResolve(domains)

        return Pair(config, resolvedCount)
    }
}
