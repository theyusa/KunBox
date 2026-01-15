package com.kunk.singbox.repository

import com.tencent.mmkv.MMKV

/**
 * 节点延迟缓存 - 使用 MMKV 持久化存储
 *
 * 功能:
 * - 持久化存储节点延迟测试结果
 * - App 重启后保留测速数据
 * - 24 小时缓存有效期
 */
object LatencyCache {
    private const val MMKV_ID = "latency_cache"
    private const val KEY_PREFIX = "lat_"
    private const val KEY_TIMESTAMP_PREFIX = "lat_ts_"
    private const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24 小时

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.SINGLE_PROCESS_MODE)
    }

    /**
     * 获取节点延迟
     * @param nodeId 节点 ID
     * @return 延迟值 (ms)，null 表示无缓存或已过期，-1 表示测试失败/超时
     */
    fun get(nodeId: String): Long? {
        val timestamp = mmkv.decodeLong(KEY_TIMESTAMP_PREFIX + nodeId, 0L)
        if (timestamp == 0L) return null

        // 检查缓存是否过期
        if (System.currentTimeMillis() - timestamp > CACHE_VALIDITY_MS) {
            remove(nodeId)
            return null
        }

        val latency = mmkv.decodeLong(KEY_PREFIX + nodeId, Long.MIN_VALUE)
        return if (latency == Long.MIN_VALUE) null else latency
    }

    /**
     * 设置节点延迟
     * @param nodeId 节点 ID
     * @param latency 延迟值 (ms)，-1 表示测试失败/超时
     */
    fun set(nodeId: String, latency: Long) {
        mmkv.encode(KEY_PREFIX + nodeId, latency)
        mmkv.encode(KEY_TIMESTAMP_PREFIX + nodeId, System.currentTimeMillis())
    }

    /**
     * 移除节点延迟缓存
     */
    fun remove(nodeId: String) {
        mmkv.removeValueForKey(KEY_PREFIX + nodeId)
        mmkv.removeValueForKey(KEY_TIMESTAMP_PREFIX + nodeId)
    }

    /**
     * 清除所有缓存
     */
    fun clear() {
        mmkv.clearAll()
    }

    /**
     * 获取所有有效的延迟缓存
     * @return Map<nodeId, latency>
     */
    fun getAll(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        val allKeys = mmkv.allKeys() ?: return result

        allKeys.filter { it.startsWith(KEY_PREFIX) && !it.startsWith(KEY_TIMESTAMP_PREFIX) }
            .forEach { key ->
                val nodeId = key.removePrefix(KEY_PREFIX)
                get(nodeId)?.let { result[nodeId] = it }
            }

        return result
    }

    /**
     * 批量设置延迟
     */
    fun setAll(latencies: Map<String, Long>) {
        val now = System.currentTimeMillis()
        latencies.forEach { (nodeId, latency) ->
            mmkv.encode(KEY_PREFIX + nodeId, latency)
            mmkv.encode(KEY_TIMESTAMP_PREFIX + nodeId, now)
        }
    }

    /**
     * 获取缓存数量
     */
    fun size(): Int {
        val allKeys = mmkv.allKeys() ?: return 0
        return allKeys.count { it.startsWith(KEY_PREFIX) && !it.startsWith(KEY_TIMESTAMP_PREFIX) }
    }
}
