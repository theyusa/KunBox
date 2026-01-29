package com.kunk.singbox.service.manager

import android.content.Context
import android.util.Log
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 连接健康监控器 - 完美方案第一层：检测性防御
 *
 * 功能：
 * 1. 实时监控连接健康状态
 * 2. 检测连接假死（TCP 连接超时、无响应）
 * 3. 提供连接健康度评分
 * 4. 触发精准恢复机制
 *
 * 设计原则：
 * - 低开销：使用轻量级检测机制
 * - 高准确：避免误报和漏报
 * - 快速响应：5秒检测间隔
 */
class ConnectionHealthMonitor private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ConnectionHealthMonitor"

        // 检测间隔（毫秒）
        private const val CHECK_INTERVAL_MS = 5000L

        // 连接超时阈值（毫秒）
        private const val CONNECTION_TIMEOUT_MS = 30000L

        // 健康度评分阈值
        private const val HEALTH_SCORE_THRESHOLD = 50

        // 连接假死判定阈值（连续失败次数）
        private const val STALE_THRESHOLD = 3

        @Volatile
        private var instance: ConnectionHealthMonitor? = null

        fun getInstance(context: Context): ConnectionHealthMonitor {
            return instance ?: synchronized(this) {
                instance ?: ConnectionHealthMonitor(context.applicationContext).also { instance = it }
            }
        }
    }

    private val singBoxCore = SingBoxCore.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 监控状态
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    // 连接健康度评分 (0-100)
    private val _healthScore = MutableStateFlow(100)
    val healthScore: StateFlow<Int> = _healthScore.asStateFlow()

    // 应用连接状态映射
    private val appConnectionStates = ConcurrentHashMap<String, ConnectionState>()

    // 连接假死检测计数器
    private val staleDetectionCount = AtomicLong(0)

    // 监控任务
    private var monitorJob: Job? = null

    // 是否已初始化
    private val initialized = AtomicBoolean(false)

    /**
     * 连接状态数据类
     */
    data class ConnectionState(
        val packageName: String,
        val lastActiveTime: Long,
        val isActive: Boolean,
        val connectionCount: Int,
        val errorCount: Int,
        val lastErrorTime: Long
    )

    /**
     * 健康检查结果
     */
    data class HealthCheckResult(
        val isHealthy: Boolean,
        val score: Int,
        val staleApps: List<String>,
        val message: String
    )

    /**
     * 初始化监控器
     */
    fun initialize() {
        if (initialized.getAndSet(true)) {
            Log.d(TAG, "Already initialized")
            return
        }

        Log.i(TAG, "Initializing ConnectionHealthMonitor")
        startMonitoring()
    }

    /**
     * 启动监控
     */
    fun startMonitoring() {
        if (_isMonitoring.value) {
            Log.d(TAG, "Already monitoring")
            return
        }

        if (!VpnStateStore.getActive()) {
            Log.w(TAG, "VPN not active, skipping monitoring start")
            return
        }

        Log.i(TAG, "Starting connection health monitoring")
        _isMonitoring.value = true

        monitorJob = scope.launch {
            while (_isMonitoring.value) {
                try {
                    performHealthCheck()
                    delay(CHECK_INTERVAL_MS)
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Health check error", e)
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * 停止监控
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping connection health monitoring")
        _isMonitoring.value = false
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * 执行健康检查
     */
    private suspend fun performHealthCheck() {
        try {
            // 1. 检查 VPN 核心状态
            val coreStatus = checkCoreStatus()
            if (!coreStatus) {
                Log.w(TAG, "Core status check failed")
                _healthScore.value = 0
                return
            }

            // 2. 检查连接活跃度
            val connectionStatus = checkConnectionActivity()
            val score = calculateHealthScore(connectionStatus)
            _healthScore.value = score

            // 3. 检测连接假死
            val staleApps = detectStaleConnections(connectionStatus)
            if (staleApps.isNotEmpty()) {
                val count = staleDetectionCount.incrementAndGet()
                Log.w(TAG, "Detected stale connections: $staleApps (count: $count)")

                // 连续检测到假死，触发恢复
                if (count >= STALE_THRESHOLD) {
                    Log.w(TAG, "Stale connection threshold reached, triggering recovery")
                    triggerRecovery(staleApps)
                    staleDetectionCount.set(0)
                }
            } else {
                staleDetectionCount.set(0)
            }

            // 4. 记录健康检查结果
            val result = HealthCheckResult(
                isHealthy = score >= HEALTH_SCORE_THRESHOLD,
                score = score,
                staleApps = staleApps,
                message = if (score >= HEALTH_SCORE_THRESHOLD) "Healthy" else "Degraded"
            )

            Log.d(TAG, "Health check result: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            _healthScore.value = 0
        }
    }

    /**
     * 检查核心状态
     */
    private suspend fun checkCoreStatus(): Boolean {
        return try {
            // 检查 sing-box 核心是否正常运行
            val isRunning = VpnStateStore.getActive()
            if (!isRunning) {
                Log.w(TAG, "VPN not active")
                return false
            }

            // 检查是否有活跃的连接
            val hasConnections = singBoxCore.hasActiveConnections()
            if (!hasConnections) {
                Log.d(TAG, "No active connections (may be idle)")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Core status check failed", e)
            false
        }
    }

    /**
     * 检查连接活跃度
     */
    private suspend fun checkConnectionActivity(): Map<String, ConnectionState> {
        val currentTime = System.currentTimeMillis()
        val connectionStates = mutableMapOf<String, ConnectionState>()

        try {
            val activeConnections = singBoxCore.getActiveConnections()

            activeConnections.forEach { conn ->
                val packageName = conn.packageName ?: "unknown"
                val existingState = appConnectionStates[packageName]
                
                val isStale = !conn.hasRecentData && conn.oldestConnMs > CONNECTION_TIMEOUT_MS

                val newState = ConnectionState(
                    packageName = packageName,
                    lastActiveTime = if (conn.hasRecentData) currentTime else currentTime - conn.oldestConnMs,
                    isActive = !isStale,
                    connectionCount = conn.connectionCount,
                    errorCount = if (isStale) (existingState?.errorCount ?: 0) + 1 else (existingState?.errorCount ?: 0),
                    lastErrorTime = if (isStale) currentTime else (existingState?.lastErrorTime ?: 0L)
                )

                connectionStates[packageName] = newState
                appConnectionStates[packageName] = newState
            }

            val staleThreshold = currentTime - CONNECTION_TIMEOUT_MS
            appConnectionStates.entries.removeIf { (_, state) ->
                state.lastActiveTime < staleThreshold
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection activity check failed", e)
        }

        return connectionStates
    }

    /**
     * 计算健康度评分
     */
    private fun calculateHealthScore(connectionStates: Map<String, ConnectionState>): Int {
        if (connectionStates.isEmpty()) {
            // 没有活跃连接，视为健康（可能处于空闲状态）
            return 100
        }

        var totalScore = 0
        var count = 0

        connectionStates.values.forEach { state ->
            var score = 100

            // 根据错误数扣分
            score -= state.errorCount * 10

            // 根据连接数调整（连接数过多可能表示问题）
            if (state.connectionCount > 100) {
                score -= 20
            }

            // 确保分数在 0-100 范围内
            score = score.coerceIn(0, 100)

            totalScore += score
            count++
        }

        return if (count > 0) totalScore / count else 100
    }

    /**
     * 检测连接假死
     */
    private fun detectStaleConnections(connectionStates: Map<String, ConnectionState>): List<String> {
        val staleApps = mutableListOf<String>()

        connectionStates.values.forEach { state ->
            if (!state.isActive && state.connectionCount > 0) {
                staleApps.add(state.packageName)
            }
        }

        return staleApps
    }

    /**
     * 触发恢复机制
     */
    private fun triggerRecovery(staleApps: List<String>) {
        Log.i(TAG, "Triggering recovery for stale apps: $staleApps")

        // 通知 PrecisionRecoveryManager 执行精准恢复
        PrecisionRecoveryManager.getInstance(context).recoverApps(staleApps)
    }

    /**
     * 更新应用连接状态
     */
    fun updateAppState(packageName: String, isActive: Boolean, hasError: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val existingState = appConnectionStates[packageName]

        val newState = ConnectionState(
            packageName = packageName,
            lastActiveTime = currentTime,
            isActive = isActive,
            connectionCount = existingState?.connectionCount ?: 0,
            errorCount = if (hasError) (existingState?.errorCount ?: 0) + 1 else (existingState?.errorCount ?: 0),
            lastErrorTime = if (hasError) currentTime else (existingState?.lastErrorTime ?: 0L)
        )

        appConnectionStates[packageName] = newState
    }

    /**
     * 获取应用连接状态
     */
    fun getAppState(packageName: String): ConnectionState? {
        return appConnectionStates[packageName]
    }

    /**
     * 获取所有应用连接状态
     */
    fun getAllAppStates(): Map<String, ConnectionState> {
        return appConnectionStates.toMap()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up ConnectionHealthMonitor")
        stopMonitoring()
        appConnectionStates.clear()
        initialized.set(false)
    }
}
