package com.kunk.singbox.service.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 精准恢复管理器 - 完美方案第二层：恢复性防御
 *
 * 功能：
 * 1. 只关闭特定应用的连接，避免全局 NetworkBump
 * 2. 使用 sing-box 的 closeConnections API 精准控制
 * 3. 最小化对其他应用的影响
 * 4. 提供恢复统计和日志
 *
 * 设计原则：
 * - 精准：只恢复有问题的应用
 * - 快速：毫秒级响应
 * - 安全：不影响其他应用
 */
class PrecisionRecoveryManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "PrecisionRecoveryManager"

        // 恢复冷却时间（毫秒）
        private const val RECOVERY_COOLDOWN_MS = 1000L

        @Volatile
        private var instance: PrecisionRecoveryManager? = null

        fun getInstance(context: Context): PrecisionRecoveryManager {
            return instance ?: synchronized(this) {
                instance ?: PrecisionRecoveryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val singBoxCore = SingBoxCore.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 恢复状态
    private val _isRecovering = MutableStateFlow(false)
    val isRecovering: StateFlow<Boolean> = _isRecovering.asStateFlow()

    // 恢复统计
    private val _recoveryCount = MutableStateFlow(0)
    val recoveryCount: StateFlow<Int> = _recoveryCount.asStateFlow()

    // 应用恢复历史
    private val recoveryHistory = ConcurrentHashMap<String, RecoveryRecord>()

    // 恢复冷却时间戳
    private val lastRecoveryTime = AtomicLong(0)

    // 是否已初始化
    private val initialized = AtomicBoolean(false)

    /**
     * 恢复记录
     */
    data class RecoveryRecord(
        val packageName: String,
        val timestamp: Long,
        val success: Boolean,
        val retryCount: Int,
        val durationMs: Long
    )

    /**
     * 恢复结果
     */
    data class RecoveryResult(
        val packageName: String,
        val success: Boolean,
        val durationMs: Long,
        val message: String
    )

    /**
     * 初始化恢复管理器
     */
    fun initialize() {
        if (initialized.getAndSet(true)) {
            Log.d(TAG, "Already initialized")
            return
        }

        Log.i(TAG, "Initializing PrecisionRecoveryManager")
    }

    /**
     * 恢复指定应用的连接
     */
    fun recoverApps(packageNames: List<String>) {
        if (packageNames.isEmpty()) {
            Log.w(TAG, "No packages to recover")
            return
        }

        // 检查冷却时间
        val currentTime = System.currentTimeMillis()
        val timeSinceLastRecovery = currentTime - lastRecoveryTime.get()
        if (timeSinceLastRecovery < RECOVERY_COOLDOWN_MS) {
            Log.d(TAG, "Recovery cooldown active, skipping (${timeSinceLastRecovery}ms < ${RECOVERY_COOLDOWN_MS}ms)")
            return
        }

        // 检查 VPN 状态
        if (!VpnStateStore.getActive()) {
            Log.w(TAG, "VPN not active, skipping recovery")
            return
        }

        Log.i(TAG, "Starting recovery for packages: $packageNames")
        _isRecovering.value = true

        scope.launch {
            try {
                val results = mutableListOf<RecoveryResult>()

                // 并行恢复所有应用
                packageNames.map { packageName ->
                    async {
                        recoverApp(packageName)
                    }
                }.awaitAll().forEach { result ->
                    results.add(result)
                }

                // 更新统计
                _recoveryCount.value += results.count { it.success }

                // 记录结果
                results.forEach { result ->
                    Log.i(TAG, "Recovery result: $result")
                }

                lastRecoveryTime.set(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Recovery failed", e)
            } finally {
                _isRecovering.value = false
            }
        }
    }

    /**
     * 恢复单个应用的连接
     */
    private suspend fun recoverApp(packageName: String): RecoveryResult {
        val startTime = System.currentTimeMillis()

        try {
            Log.d(TAG, "Recovering app: $packageName")

            // 获取应用的 UID
            val uid = getAppUid(packageName)
            if (uid <= 0) {
                val msg = "Failed to get UID for $packageName"
                Log.w(TAG, msg)
                return RecoveryResult(packageName, false, 0, msg)
            }

            // 尝试精准关闭连接
            val success = closeAppConnections(packageName, uid)

            val duration = System.currentTimeMillis() - startTime

            // 记录恢复历史
            val record = RecoveryRecord(
                packageName = packageName,
                timestamp = System.currentTimeMillis(),
                success = success,
                retryCount = 0,
                durationMs = duration
            )
            recoveryHistory[packageName] = record

            val message = if (success) "Recovered successfully" else "Recovery failed"
            return RecoveryResult(packageName, success, duration, message)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Recovery error for $packageName", e)

            val record = RecoveryRecord(
                packageName = packageName,
                timestamp = System.currentTimeMillis(),
                success = false,
                retryCount = 0,
                durationMs = duration
            )
            recoveryHistory[packageName] = record

            return RecoveryResult(packageName, false, duration, e.message ?: "Unknown error")
        }
    }

    /**
     * 关闭应用的连接
     */
    private suspend fun closeAppConnections(packageName: String, uid: Int): Boolean {
        return try {
            // 方法1: 使用 sing-box 的 closeConnections API（如果可用）
            val closedByCore = tryCloseConnectionsByCore(packageName, uid)
            if (closedByCore) {
                Log.d(TAG, "Closed connections via core API for $packageName")
                return true
            }

            // 方法2: 使用 NetworkBump（作为后备方案）
            val closedByBump = tryCloseConnectionsByNetworkBump()
            if (closedByBump) {
                Log.d(TAG, "Closed connections via NetworkBump for $packageName")
                return true
            }

            Log.w(TAG, "Failed to close connections for $packageName")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connections for $packageName", e)
            false
        }
    }

    /**
     * 使用 sing-box 核心 API 关闭连接
     */
    private suspend fun tryCloseConnectionsByCore(packageName: String, uid: Int): Boolean {
        return try {
            // 检查 sing-box 核心是否支持 closeConnections API
            // 注意：这需要 libbox 扩展支持
            val success = singBoxCore.closeConnections(packageName, uid)
            if (success) {
                Log.d(TAG, "Successfully closed connections via core API")
            } else {
                Log.d(TAG, "Core API not available or failed")
            }
            success
        } catch (e: Exception) {
            Log.d(TAG, "Core API not supported: ${e.message}")
            false
        }
    }

    /**
     * 使用 NetworkBump 关闭连接（后备方案）
     */
    private suspend fun tryCloseConnectionsByNetworkBump(): Boolean {
        return try {
            // 发送 NetworkBump 信号
            val intent = Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_NETWORK_BUMP
            }
            context.startService(intent)

            // 等待网络广播发送
            delay(200)

            true
        } catch (e: Exception) {
            Log.e(TAG, "NetworkBump failed", e)
            false
        }
    }

    /**
     * 获取应用的 UID
     */
    private fun getAppUid(packageName: String): Int {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appInfo.uid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get UID for $packageName", e)
            -1
        }
    }

    /**
     * 获取应用的恢复记录
     */
    fun getRecoveryRecord(packageName: String): RecoveryRecord? {
        return recoveryHistory[packageName]
    }

    /**
     * 获取所有恢复记录
     */
    fun getAllRecoveryRecords(): Map<String, RecoveryRecord> {
        return recoveryHistory.toMap()
    }

    /**
     * 清理恢复记录
     */
    fun clearRecoveryRecords() {
        recoveryHistory.clear()
        Log.d(TAG, "Cleared recovery records")
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up PrecisionRecoveryManager")
        scope.cancel()
        recoveryHistory.clear()
        initialized.set(false)
    }
}
