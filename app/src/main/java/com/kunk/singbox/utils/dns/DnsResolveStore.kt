package com.kunk.singbox.utils.dns

import android.util.Log
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

/**
 * DNS 解析结果存储
 *
 * 使用 MMKV 持久化存储解析后的 IP 地址，支持 TTL 过期机制
 */
class DnsResolveStore private constructor() {

    companion object {
        private const val TAG = "DnsResolveStore"
        private const val MMKV_ID = "dns_resolve_cache"

        // 默认 TTL: 1 小时
        const val DEFAULT_TTL_SECONDS = 3600

        @Volatile
        private var instance: DnsResolveStore? = null

        fun getInstance(): DnsResolveStore {
            return instance ?: synchronized(this) {
                instance ?: DnsResolveStore().also { instance = it }
            }
        }
    }

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.SINGLE_PROCESS_MODE)
    }

    private val gson = Gson()

    /**
     * 存储的解析条目
     */
    data class ResolvedEntry(
        val ip: String,
        val resolvedAt: Long,
        val ttlSeconds: Int = DEFAULT_TTL_SECONDS,
        val source: String = "doh"
    ) {
        /**
         * 检查是否已过期
         */
        fun isExpired(): Boolean {
            val now = System.currentTimeMillis()
            return now - resolvedAt > ttlSeconds * 1000L
        }

        /**
         * 获取剩余有效时间 (秒)
         */
        fun remainingSeconds(): Long {
            val elapsed = (System.currentTimeMillis() - resolvedAt) / 1000
            return maxOf(0, ttlSeconds - elapsed)
        }
    }

    /**
     * 生成存储 key
     */
    private fun makeKey(profileId: String, domain: String): String {
        return "${profileId}_${domain}"
    }

    /**
     * 保存解析结果
     */
    fun save(
        profileId: String,
        domain: String,
        ip: String,
        ttlSeconds: Int = DEFAULT_TTL_SECONDS,
        source: String = "doh"
    ) {
        val entry = ResolvedEntry(
            ip = ip,
            resolvedAt = System.currentTimeMillis(),
            ttlSeconds = ttlSeconds,
            source = source
        )
        val key = makeKey(profileId, domain)
        val json = gson.toJson(entry)
        mmkv.encode(key, json)
        Log.d(TAG, "Saved: $domain -> $ip (TTL: ${ttlSeconds}s)")
    }

    /**
     * 获取解析结果
     *
     * @param profileId 配置 ID
     * @param domain 域名
     * @param allowExpired 是否允许返回过期的结果
     * @return 解析条目，如果不存在或已过期则返回 null
     */
    fun get(
        profileId: String,
        domain: String,
        allowExpired: Boolean = false
    ): ResolvedEntry? {
        val key = makeKey(profileId, domain)
        val json = mmkv.decodeString(key, null) ?: return null

        return try {
            val entry = gson.fromJson(json, ResolvedEntry::class.java)
            when {
                entry == null -> null
                allowExpired -> entry
                entry.isExpired() -> {
                    Log.d(TAG, "Entry expired: $domain")
                    null
                }
                else -> entry
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse entry for $domain", e)
            null
        }
    }

    /**
     * 获取解析的 IP 地址
     *
     * @return IP 地址，如果不存在或已过期则返回 null
     */
    fun getIp(profileId: String, domain: String): String? {
        return get(profileId, domain)?.ip
    }

    /**
     * 删除指定域名的解析结果
     */
    fun remove(profileId: String, domain: String) {
        val key = makeKey(profileId, domain)
        mmkv.removeValueForKey(key)
    }

    /**
     * 删除指定配置的所有解析结果
     */
    fun removeAllForProfile(profileId: String) {
        val prefix = "${profileId}_"
        val keysToRemove = mmkv.allKeys()?.filter { it.startsWith(prefix) } ?: return
        keysToRemove.forEach { mmkv.removeValueForKey(it) }
        Log.d(TAG, "Removed ${keysToRemove.size} entries for profile $profileId")
    }

    /**
     * 批量保存解析结果
     */
    fun saveBatch(
        profileId: String,
        results: Map<String, DnsResolveResult>,
        ttlSeconds: Int = DEFAULT_TTL_SECONDS
    ): Int {
        var savedCount = 0
        for ((domain, result) in results) {
            if (result.isSuccess && result.ip != null) {
                save(profileId, domain, result.ip, ttlSeconds, result.source)
                savedCount++
            }
        }
        Log.d(TAG, "Batch saved $savedCount entries for profile $profileId")
        return savedCount
    }

    /**
     * 获取配置的所有有效解析结果
     */
    fun getAllForProfile(profileId: String): Map<String, ResolvedEntry> {
        val prefix = "${profileId}_"
        val result = mutableMapOf<String, ResolvedEntry>()

        mmkv.allKeys()?.filter { it.startsWith(prefix) }?.forEach { key ->
            val domain = key.removePrefix(prefix)
            val entry = get(profileId, domain)
            if (entry != null) {
                result[domain] = entry
            }
        }

        return result
    }

    /**
     * 清理所有过期的条目
     */
    fun cleanupExpired(): Int {
        var cleanedCount = 0
        mmkv.allKeys()?.forEach { key ->
            val json = mmkv.decodeString(key, null) ?: return@forEach
            try {
                val entry = gson.fromJson(json, ResolvedEntry::class.java)
                if (entry?.isExpired() == true) {
                    mmkv.removeValueForKey(key)
                    cleanedCount++
                }
            } catch (e: Exception) {
                // Invalid entry, remove it
                mmkv.removeValueForKey(key)
                cleanedCount++
            }
        }
        if (cleanedCount > 0) {
            Log.d(TAG, "Cleaned up $cleanedCount expired entries")
        }
        return cleanedCount
    }

    /**
     * 获取统计信息
     */
    fun getStats(): Stats {
        var total = 0
        var valid = 0
        var expired = 0

        mmkv.allKeys()?.forEach { key ->
            val json = mmkv.decodeString(key, null) ?: return@forEach
            try {
                val entry = gson.fromJson(json, ResolvedEntry::class.java)
                if (entry != null) {
                    total++
                    if (entry.isExpired()) expired++ else valid++
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return Stats(total, valid, expired)
    }

    data class Stats(
        val total: Int,
        val valid: Int,
        val expired: Int
    )

    /**
     * 清空所有数据
     */
    fun clear() {
        mmkv.clearAll()
        Log.d(TAG, "Cleared all entries")
    }
}
