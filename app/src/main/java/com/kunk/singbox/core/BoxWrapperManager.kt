package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BoxWrapper 管理器 - 统一管理 libbox 的生命周期
 *
 * 功能:
 * - 节点切换: selectOutbound()
 * - 电源管理: pause() / resume()
 * - 流量统计: getUploadTotal() / getDownloadTotal()
 * - 全局访问: 通过 Libbox 静态方法跨组件共享
 *
 * 新版 libbox API (基于 CommandServer):
 * - 不再使用 BoxService 和 BoxWrapper
 * - 使用 Libbox.xxxxx() 静态方法
 * - CommandServer 作为主入口点管理服务生命周期
 */
object BoxWrapperManager {
    private const val TAG = "BoxWrapperManager"

    @Volatile
    private var commandServer: CommandServer? = null

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _hasSelector = MutableStateFlow(false)
    val hasSelector: StateFlow<Boolean> = _hasSelector.asStateFlow()

    // 2025-fix-v22: 暂停历史跟踪，用于判断是否需要强制关闭连接
    @Volatile
    private var lastResumeTimestamp: Long = 0L

    /**
     * 初始化 - 绑定 CommandServer
     * 在 CommandServer 创建后调用
     */
    fun init(server: CommandServer): Boolean {
        return try {
            commandServer = server
            _isPaused.value = false
            _hasSelector.value = runCatching { Libbox.hasSelector() }.getOrDefault(false)
            Log.i(TAG, "BoxWrapperManager initialized, hasSelector=${_hasSelector.value}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init BoxWrapperManager", e)
            commandServer = null
            false
        }
    }

    /**
     * 释放 - 清理状态
     * 在 CommandServer 关闭时调用
     */
    fun release() {
        commandServer = null
        _isPaused.value = false
        _hasSelector.value = false
        Log.i(TAG, "BoxWrapperManager released")
    }

    /**
     * 检查服务是否可用
     */
    fun isAvailable(): Boolean {
        return runCatching { Libbox.isRunning() }.getOrDefault(false)
    }

    // ==================== 节点切换 ====================

    /**
     * 切换出站节点
     * @param nodeTag 节点标签
     * @return true 如果切换成功
     */
    fun selectOutbound(nodeTag: String): Boolean {
        return try {
            val result = Libbox.selectOutboundByTag(nodeTag)
            if (result) {
                Log.i(TAG, "selectOutbound($nodeTag) success")
            } else {
                Log.w(TAG, "selectOutbound($nodeTag) failed")
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "selectOutbound($nodeTag) failed: ${e.message}")
            false
        }
    }

    /**
     * 获取当前选中的出站节点
     */
    fun getSelectedOutbound(): String? {
        return try {
            Libbox.getSelectedOutbound().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "getSelectedOutbound failed: ${e.message}")
            null
        }
    }

    /**
     * 获取所有出站节点列表
     * @return 节点标签列表
     */
    fun listOutbounds(): List<String> {
        return try {
            Libbox.listOutboundsString()
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "listOutbounds failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 检查是否有 selector 类型的出站
     */
    fun hasSelector(): Boolean {
        return try {
            Libbox.hasSelector()
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 电源管理 ====================

    /**
     * 暂停 - 设备休眠时调用
     * 通知 sing-box 内核进入省电模式
     */
    fun pause(): Boolean {
        return try {
            Libbox.pauseService()
            _isPaused.value = true
            Log.i(TAG, "pause() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "pause() failed: ${e.message}")
            false
        }
    }

    /**
     * 恢复 - 设备唤醒时调用
     * 通知 sing-box 内核恢复正常模式
     */
    fun resume(): Boolean {
        return try {
            Libbox.resumeService()
            _isPaused.value = false
            lastResumeTimestamp = System.currentTimeMillis()
            Log.i(TAG, "resume() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resume() failed: ${e.message}")
            false
        }
    }

    /**
     * 检查是否处于暂停状态
     */
    fun isPausedNow(): Boolean {
        return try {
            Libbox.isPaused()
        } catch (e: Exception) {
            _isPaused.value
        }
    }

    /**
     * 检查是否最近从暂停状态恢复
     * 用于判断是否需要在 NetworkBump 时强制关闭连接 (发送 RST)
     *
     * @param thresholdMs 阈值毫秒数，默认 30 秒
     * @return true 如果在阈值时间内从暂停状态恢复过
     */
    fun wasPausedRecently(thresholdMs: Long = 30_000L): Boolean {
        val timestamp = lastResumeTimestamp
        if (timestamp == 0L) return false
        return (System.currentTimeMillis() - timestamp) < thresholdMs
    }

    /**
     * 进入睡眠模式 - 设备空闲 (Doze) 时调用
     * 比 pause() 更激进
     *
     * @return true 如果成功
     */
    fun sleep(): Boolean {
        return pause()
    }

    /**
     * 从睡眠中唤醒 - 设备退出空闲 (Doze) 模式时调用
     *
     * @return true 如果成功
     */
    fun wake(): Boolean {
        val server = commandServer
        if (server == null) {
            Log.w(TAG, "wake() failed: commandServer is null, falling back to resume()")
            return resume()
        }
        return try {
            server.wake()
            _isPaused.value = false
            lastResumeTimestamp = System.currentTimeMillis()
            Log.i(TAG, "wake() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "wake() failed: ${e.message}, falling back to resume()")
            resume()
        }
    }

    // ==================== 流量统计 ====================

    /**
     * 获取累计上传字节数
     */
    fun getUploadTotal(): Long {
        return try {
            Libbox.getTrafficTotalUplink()
        } catch (e: Exception) {
            Log.w(TAG, "getUploadTotal failed: ${e.message}")
            -1L
        }
    }

    /**
     * 获取累计下载字节数
     */
    fun getDownloadTotal(): Long {
        return try {
            Libbox.getTrafficTotalDownlink()
        } catch (e: Exception) {
            Log.w(TAG, "getDownloadTotal failed: ${e.message}")
            -1L
        }
    }

    /**
     * 重置流量统计
     */
    fun resetTraffic(): Boolean {
        return try {
            val result = Libbox.resetTrafficStats()
            Log.i(TAG, "resetTraffic() result=$result")
            result
        } catch (e: Exception) {
            Log.w(TAG, "resetTraffic() failed: ${e.message}")
            false
        }
    }

    /**
     * 获取连接数
     */
    fun getConnectionCount(): Int {
        return try {
            Libbox.getConnectionCount()
        } catch (e: Exception) {
            0
        }
    }

    // ==================== 工具函数 ====================

    /**
     * 重置所有连接
     * @param system true=重置系统级连接表
     */
    fun resetAllConnections(system: Boolean = true): Boolean {
        return try {
            Libbox.resetAllConnections(system)
            Log.i(TAG, "resetAllConnections($system) success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resetAllConnections failed: ${e.message}")
            // 回退到 LibboxCompat
            LibboxCompat.resetAllConnections(system)
        }
    }

    /**
     * 重置网络
     */
    fun resetNetwork(): Boolean {
        val server = commandServer
        if (server == null) {
            Log.w(TAG, "resetNetwork() failed: commandServer is null")
            return false
        }
        return try {
            server.resetNetwork()
            Log.i(TAG, "resetNetwork() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resetNetwork() failed: ${e.message}")
            false
        }
    }

    /**
     * 关闭所有跟踪连接
     * 通过 TrafficManager 关闭连接，确保应用收到 RST/FIN 信号
     * 这是解决"TG 后台恢复后一直加载中"问题的关键
     */
    fun closeAllTrackedConnections(): Int {
        return try {
            val count = Libbox.closeAllTrackedConnections()
            if (count > 0) {
                Log.i(TAG, "closeAllTrackedConnections: closed $count connections")
            }
            count
        } catch (e: Exception) {
            Log.w(TAG, "closeAllTrackedConnections failed: ${e.message}")
            0
        }
    }

    /**
     * 获取扩展版本
     */
    fun getExtensionVersion(): String {
        return try {
            Libbox.getKunBoxVersion()
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 获取 CommandServer 实例
     * 仅在 VPN 运行时可用
     */
    fun getCommandServer(): CommandServer? {
        return commandServer
    }

    // ==================== Network Recovery (Fix loading issue after background resume) ====================

    /**
     * Auto network recovery - Recommended entry point
     * Automatically selects recovery strategy based on current state
     * @return true if recovery succeeded
     */
    fun recoverNetworkAuto(): Boolean {
        return try {
            Libbox.recoverNetworkAuto()
        } catch (e: Exception) {
            Log.w(TAG, "recoverNetworkAuto kernel call failed, fallback to manual", e)
            recoverNetworkManual()
        }
    }

    /**
     * Quick network recovery - Only close old connections
     * For short background resume scenarios
     */
    fun recoverNetworkQuick(): Boolean {
        return try {
            Libbox.recoverNetworkQuick()
        } catch (e: Exception) {
            Log.w(TAG, "recoverNetworkQuick kernel call failed, fallback", e)
            resetAllConnections(true)
        }
    }

    /**
     * Full network recovery
     * Complete recovery flow: wake -> close connections -> clear DNS -> reset network stack
     */
    fun recoverNetworkFull(): Boolean {
        return try {
            Libbox.recoverNetworkFull()
        } catch (e: Exception) {
            Log.w(TAG, "recoverNetworkFull kernel call failed, fallback to manual", e)
            recoverNetworkManual()
        }
    }

    /**
     * Deep network recovery
     * Most aggressive recovery mode, for long background or complete network interruption
     */
    fun recoverNetworkDeep(): Boolean {
        return try {
            Libbox.recoverNetworkDeep()
        } catch (e: Exception) {
            Log.w(TAG, "recoverNetworkDeep kernel call failed, fallback to manual", e)
            recoverNetworkManual()
        }
    }

    /**
     * Proactive network recovery (RECOMMENDED for foreground resume)
     * Includes network probe to ensure network is actually available
     * This "prewarms" the connection path and DNS cache
     */
    fun recoverNetworkProactive(): Boolean {
        return try {
            Libbox.recoverNetworkProactive()
        } catch (e: Exception) {
            Log.w(TAG, "recoverNetworkProactive kernel call failed, fallback to full", e)
            recoverNetworkFull()
        }
    }

    /**
     * Check if network recovery is needed
     */
    fun isNetworkRecoveryNeeded(): Boolean {
        return try {
            Libbox.checkNetworkRecoveryNeeded()
        } catch (e: Exception) {
            isPausedNow()
        }
    }

    /**
     * Manual network recovery (fallback)
     * Used when kernel-level recovery is not available
     *
     * 2025-fix-v21: 使用 wake() 替代 resume()
     * wake() 通过 CommandServer.wake() 更彻底地唤醒内核，
     * 而 resume() 只调用 Libbox.resumeService()
     * 这解决了息屏久了亮屏后 VPN 连着但没网络的问题
     */
    private fun recoverNetworkManual(): Boolean {
        return try {
            // Step 1: 唤醒 - 使用 wake() 而非 resume()
            // wake() 会调用 CommandServer.wake()，更彻底地唤醒 sing-box 内核
            if (isPausedNow()) {
                Log.i(TAG, "recoverNetworkManual: waking up paused service")
                wake()
            } else {
                // 即使不处于暂停状态，也尝试唤醒，确保内核完全活跃
                Log.i(TAG, "recoverNetworkManual: wake() for safety even if not paused")
                wake()
            }
            // Step 2: 关闭连接
            resetAllConnections(true)
            // Step 3: 重置网络栈
            resetNetwork()
            Log.i(TAG, "recoverNetworkManual completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "recoverNetworkManual failed", e)
            false
        }
    }

    /**
     * URL 测试单个节点
     */
    fun urlTestOutbound(outboundTag: String, url: String, timeoutMs: Int): Int {
        return try {
            Libbox.urlTestOutbound(outboundTag, url, timeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "urlTestOutbound failed: ${e.message}")
            -1
        }
    }

    /**
     * 批量 URL 测试
     */
    fun urlTestBatch(
        outboundTags: List<String>,
        url: String,
        timeoutMs: Int,
        concurrency: Int
    ): Map<String, Int> {
        return try {
            val tagsStr = outboundTags.joinToString("\n")
            val result = Libbox.urlTestBatch(tagsStr, url, timeoutMs, concurrency.toLong())
                ?: return emptyMap()

            val map = mutableMapOf<String, Int>()
            val count = result.len()
            @Suppress("LoopWithTooManyJumpStatements")
            for (i in 0 until count) {
                val item = result.get(i) ?: continue
                val tag = item.tag
                if (tag.isNullOrBlank()) continue
                map[tag] = item.delay
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "urlTestBatch failed: ${e.message}")
            emptyMap()
        }
    }

    // ==================== Idle Connection Cleanup (Fix TG image loading slow) ====================

    /**
     * Close idle connections that have been inactive for too long
     * This fixes the "TG image loading slow" issue caused by stale connections
     *
     * @param maxIdleSeconds Maximum idle time in seconds (default: 60)
     * @return Number of connections closed
     */
    fun closeIdleConnections(maxIdleSeconds: Int = 60): Int {
        return try {
            val count = Libbox.closeIdleConnections(maxIdleSeconds)
            if (count > 0) {
                Log.i(TAG, "closeIdleConnections: closed $count connections (maxIdle=${maxIdleSeconds}s)")
            }
            count
        } catch (e: Exception) {
            Log.w(TAG, "closeIdleConnections failed: ${e.message}")
            0
        }
    }

    /**
     * Close stale connections to a specific host pattern
     * Useful for troubleshooting specific domains (like Telegram CDN)
     *
     * @param hostPattern Host pattern to match (case-insensitive contains match)
     * @param maxAgeSeconds Maximum connection age in seconds
     * @return Number of connections closed
     */
    fun closeStaleConnectionsForHost(hostPattern: String, maxAgeSeconds: Int = 30): Int {
        return try {
            val count = Libbox.closeStaleConnectionsForHost(hostPattern, maxAgeSeconds)
            if (count > 0) {
                Log.i(
                    TAG,
                    "closeStaleConnectionsForHost: closed $count connections " +
                        "for '$hostPattern' (maxAge=${maxAgeSeconds}s)"
                )
            }
            count
        } catch (e: Exception) {
            Log.w(TAG, "closeStaleConnectionsForHost failed: ${e.message}")
            0
        }
    }
}
