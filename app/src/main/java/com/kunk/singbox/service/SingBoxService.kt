package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.system.OsConstants
import android.util.Log
import android.service.quicksettings.TileService
import android.content.ComponentName
import com.google.gson.Gson
import com.kunk.singbox.MainActivity
import com.kunk.singbox.R
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.TrafficRepository
import io.nekohasekai.libbox.*
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SingBoxService : VpnService() {

    private val gson = Gson()
    private var routeGroupAutoSelectJob: Job? = null

    private val notificationUpdateDebounceMs: Long = 900L
    private val lastNotificationUpdateAtMs = AtomicLong(0L)
    @Volatile private var notificationUpdateJob: Job? = null

    // 华为设备修复: 追踪是否已经调用过 startForeground(),避免重复调用触发提示音
    private val hasForegroundStarted = AtomicBoolean(false)

    private val remoteStateUpdateDebounceMs: Long = 250L
    private val lastRemoteStateUpdateAtMs = AtomicLong(0L)
    @Volatile private var remoteStateUpdateJob: Job? = null

    data class ConnectionOwnerStatsSnapshot(
        val calls: Long,
        val invalidArgs: Long,
        val uidResolved: Long,
        val securityDenied: Long,
        val otherException: Long,
        val lastUid: Int,
        val lastEvent: String
    )

    companion object {
        private const val TAG = "SingBoxService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "singbox_vpn_service"
        
        const val ACTION_START = "com.kunk.singbox.START"
        const val ACTION_STOP = "com.kunk.singbox.STOP"
        const val ACTION_SWITCH_NODE = "com.kunk.singbox.SWITCH_NODE"
        const val ACTION_SERVICE = "com.kunk.singbox.SERVICE"
        const val ACTION_UPDATE_SETTING = "com.kunk.singbox.UPDATE_SETTING"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val EXTRA_CLEAN_CACHE = "clean_cache"
        const val EXTRA_SETTING_KEY = "setting_key"
        const val EXTRA_SETTING_VALUE_BOOL = "setting_value_bool"
        
        @Volatile
        var instance: SingBoxService? = null
            private set

        @Volatile
        var isRunning = false
            private set(value) {
                field = value
                _isRunningFlow.value = value
            }

        private val _isRunningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isRunningFlow = _isRunningFlow.asStateFlow()

        private val _isStartingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isStartingFlow = _isStartingFlow.asStateFlow()

        private val _lastErrorFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val lastErrorFlow = _lastErrorFlow.asStateFlow()

        @Volatile
        var isStarting = false
            private set(value) {
                field = value
                _isStartingFlow.value = value
            }

        @Volatile
        var isManuallyStopped = false
            private set
        
        private var lastConfigPath: String? = null

        private fun setLastError(message: String?) {
            _lastErrorFlow.value = message
            if (!message.isNullOrBlank()) {
                try {
                    com.kunk.singbox.repository.LogRepository.getInstance()
                        .addLog("ERROR SingBoxService: $message")
                } catch (_: Exception) {
                }
            }
        }

        private val connectionOwnerCalls = AtomicLong(0)
        private val connectionOwnerInvalidArgs = AtomicLong(0)
        private val connectionOwnerUidResolved = AtomicLong(0)
        private val connectionOwnerSecurityDenied = AtomicLong(0)
        private val connectionOwnerOtherException = AtomicLong(0)

        @Volatile private var connectionOwnerLastUid: Int = 0
        @Volatile private var connectionOwnerLastEvent: String = ""

        fun getConnectionOwnerStatsSnapshot(): ConnectionOwnerStatsSnapshot {
            return ConnectionOwnerStatsSnapshot(
                calls = connectionOwnerCalls.get(),
                invalidArgs = connectionOwnerInvalidArgs.get(),
                uidResolved = connectionOwnerUidResolved.get(),
                securityDenied = connectionOwnerSecurityDenied.get(),
                otherException = connectionOwnerOtherException.get(),
                lastUid = connectionOwnerLastUid,
                lastEvent = connectionOwnerLastEvent
            )
        }

        fun resetConnectionOwnerStats() {
            connectionOwnerCalls.set(0)
            connectionOwnerInvalidArgs.set(0)
            connectionOwnerUidResolved.set(0)
            connectionOwnerSecurityDenied.set(0)
            connectionOwnerOtherException.set(0)
            connectionOwnerLastUid = 0
            connectionOwnerLastEvent = ""
        }
    }

    private fun tryRegisterRunningServiceForLibbox() {
        val svc = boxService ?: return
        try {
            val m = Libbox::class.java.methods.firstOrNull { it.name == "setRunningService" && it.parameterTypes.size == 1 }
            m?.invoke(null, svc)
        } catch (_: Exception) {
        }
    }

    private fun tryClearRunningServiceForLibbox() {
        val svc = boxService ?: return
        try {
            val m = Libbox::class.java.methods.firstOrNull { it.name == "clearRunningService" && it.parameterTypes.size == 1 }
            m?.invoke(null, svc)
        } catch (_: Exception) {
        }
    }

    private fun startRouteGroupAutoSelect(configContent: String) {
        routeGroupAutoSelectJob?.cancel()

        routeGroupAutoSelectJob = serviceScope.launch {
            delay(1200)
            while (isRunning && !isStopping) {
                runCatching {
                    autoSelectBestForRouteGroupsOnce(configContent)
                }
                val intervalMs = 30L * 60L * 1000L
                delay(intervalMs)
            }
        }
    }

    private suspend fun autoSelectBestForRouteGroupsOnce(configContent: String) {
        val cfg = runCatching { gson.fromJson(configContent, SingBoxConfig::class.java) }.getOrNull() ?: return
        val routeRules = cfg.route?.rules.orEmpty()
        val referencedOutbounds = routeRules.mapNotNull { it.outbound }.toSet()

        if (referencedOutbounds.isEmpty()) return

        val outbounds = cfg.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }

        val targetSelectors = outbounds.filter {
            it.type == "selector" &&
                referencedOutbounds.contains(it.tag) &&
                !it.tag.equals("PROXY", ignoreCase = true)
        }

        if (targetSelectors.isEmpty()) return

        val client = waitForCommandClient(timeoutMs = 4500L) ?: return
        val core = SingBoxCore.getInstance(this@SingBoxService)
        val semaphore = kotlinx.coroutines.sync.Semaphore(permits = 4)

        for (selector in targetSelectors) {
            if (!isRunning || isStopping) return

            val groupTag = selector.tag
            val candidates = selector.outbounds
                .orEmpty()
                .filter { it.isNotBlank() }
                .filterNot { it.equals("direct", true) || it.equals("block", true) || it.equals("dns-out", true) }

            if (candidates.isEmpty()) continue

            val results = ConcurrentHashMap<String, Long>()

            coroutineScope {
                candidates.map { tag ->
                    async(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            val outbound = byTag[tag] ?: return@async
                            val rtt = try {
                                core.testOutboundLatency(outbound)
                            } catch (_: Exception) {
                                -1L
                            }
                            if (rtt >= 0) {
                                results[tag] = rtt
                            }
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }

            val best = results.entries.minByOrNull { it.value }?.key ?: continue
            val currentSelected = groupSelectedOutbounds[groupTag]
            if (currentSelected != null && currentSelected == best) continue

            runCatching {
                try {
                    client.selectOutbound(groupTag, best)
                } catch (_: Exception) {
                    client.selectOutbound(groupTag.lowercase(), best)
                }
            }
        }
    }

    private suspend fun waitForCommandClient(timeoutMs: Long): io.nekohasekai.libbox.CommandClient? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val c = commandClient
            if (c != null) return c
            delay(120)
        }
        return commandClient
    }

    private fun closeRecentConnectionsBestEffort(reason: String) {
        val ids = recentConnectionIds
        if (ids.isEmpty()) return

        val client: Any = commandClientConnections ?: commandClient ?: return
        var closed = 0
        for (id in ids) {
            if (id.isBlank()) continue
            if (invokeCloseConnection(client, id)) {
                closed++
            }
        }
        if (closed > 0) {
            LogRepository.getInstance().addLog("INFO SingBoxService: closeConnection($reason) closed=$closed total=${ids.size}")
        }
    }

    private fun invokeCloseConnection(client: Any, connId: String): Boolean {
        val names = listOf("closeConnection", "CloseConnection")
        for (name in names) {
            try {
                val m = client.javaClass.getMethod(name, String::class.java)
                val r = m.invoke(client, connId)
                if (r is Boolean) return r
                return true
            } catch (_: NoSuchMethodException) {
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }
    
    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    @Volatile private var serviceState: ServiceState = ServiceState.STOPPED

    private fun getActiveLabelInternal(): String {
        return runCatching {
            val repo = ConfigRepository.getInstance(applicationContext)
            val activeNodeId = repo.activeNodeId.value
            realTimeNodeName
                ?: repo.nodes.value.find { it.id == activeNodeId }?.name
                ?: ""
        }.getOrDefault("")
    }

    private fun resolveEgressNodeName(repo: ConfigRepository, tagOrSelector: String?): String? {
        if (tagOrSelector.isNullOrBlank()) return null

        // 1) Direct outbound tag -> node name
        repo.resolveNodeNameFromOutboundTag(tagOrSelector)?.let { return it }

        // 2) Selector/group tag -> selected outbound -> resolve again (depth-limited)
        var current: String? = tagOrSelector
        repeat(4) {
            val next = current?.let { groupSelectedOutbounds[it] }
            if (next.isNullOrBlank() || next == current) return@repeat
            repo.resolveNodeNameFromOutboundTag(next)?.let { return it }
            current = next
        }

        return null
    }

    private fun notifyRemoteStateNow() {
        SingBoxIpcHub.update(
            state = serviceState,
            activeLabel = getActiveLabelInternal(),
            lastError = lastErrorFlow.value.orEmpty(),
            manuallyStopped = isManuallyStopped
        )
    }

    private fun requestRemoteStateUpdate(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val last = lastRemoteStateUpdateAtMs.get()

        if (force) {
            lastRemoteStateUpdateAtMs.set(now)
            remoteStateUpdateJob?.cancel()
            remoteStateUpdateJob = null
            notifyRemoteStateNow()
            return
        }

        val delayMs = (remoteStateUpdateDebounceMs - (now - last)).coerceAtLeast(0L)
        if (delayMs <= 0L) {
            lastRemoteStateUpdateAtMs.set(now)
            remoteStateUpdateJob?.cancel()
            remoteStateUpdateJob = null
            notifyRemoteStateNow()
            return
        }

        if (remoteStateUpdateJob?.isActive == true) return
        remoteStateUpdateJob = serviceScope.launch {
            delay(delayMs)
            lastRemoteStateUpdateAtMs.set(SystemClock.elapsedRealtime())
            notifyRemoteStateNow()
        }
    }

    private fun updateServiceState(state: ServiceState) {
        if (serviceState == state) return
        serviceState = state
        requestRemoteStateUpdate(force = true)
    }

    /**
     * 暴露给 ConfigRepository 调用，尝试热切换节点
     * @return true if hot switch triggered successfully, false if restart is needed
     */
    suspend fun hotSwitchNode(nodeTag: String): Boolean {
        if (boxService == null || !isRunning) return false
        
        try {
            val selectorTag = "PROXY"
            Log.i(TAG, "Attempting hot switch to node tag: $nodeTag via selector: $selectorTag")
            runCatching {
                LogRepository.getInstance().addLog("INFO SingBoxService: hotSwitchNode tag=$nodeTag")
            }
            
            var switchSuccess = false

            // 1. 尝试直接通过 boxService 调用 (NekoBox 方式)
            // 部分 libbox 版本在 BoxService 上实现了 selectOutbound(tag)
            try {
                val method = boxService?.javaClass?.getMethod("selectOutbound", String::class.java)
                if (method != null) {
                    val result = method.invoke(boxService, nodeTag) as? Boolean ?: false
                    if (result) {
                        Log.i(TAG, "Hot switch accepted by boxService.selectOutbound")
                        switchSuccess = true
                    }
                }
            } catch (_: Exception) {}

            // 2. 尝试通过 CommandClient 调用 (官方方式)
            if (!switchSuccess) {
                val client = commandClient
                if (client != null) {
                    try {
                        // 尝试 "PROXY" 和 "proxy"
                        try {
                            client.selectOutbound(selectorTag, nodeTag)
                            switchSuccess = true
                        } catch (e: Exception) {
                            client.selectOutbound(selectorTag.lowercase(), nodeTag)
                            switchSuccess = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CommandClient.selectOutbound failed: ${e.message}")
                    }
                }
            }

            if (!switchSuccess) {
                Log.e(TAG, "Hot switch failed: no suitable method or method failed")
                return false
            }

            // 3. 关键：关闭旧连接
            // 这是解决“切换后旧连接不断开”问题的核心
            try {
                // 如果 libbox 开启了 with_conntrack，这会关闭所有连接
                // 注意：这是异步操作，需要一点时间生效
                commandClient?.closeConnections()
                Log.i(TAG, "Closed all connections after hot switch")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close connections: ${e.message}")
            }

            // 增加缓冲时间，确保连接关闭状态已传播，避免新请求复用旧连接导致 "use of closed network connection"
            delay(300)

            runCatching {
                closeRecentConnectionsBestEffort(reason = "hotSwitch")
            }

            // 4. 强制触发系统级网络重置
            // 这是解决“切换后旧 TCP 连接不立即断开”问题的关键
            // setUnderlyingNetworks(null) -> setUnderlyingNetworks(net) 会触发 ConnectivityManager 的网络变更事件
            // 让应用（如 TG）感知到网络中断，从而放弃旧的 TCP 连接并重新建立连接
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    val currentNetwork = lastKnownNetwork
                    if (currentNetwork != null) {
                        Log.i(TAG, "Triggering system-level network reset for hot switch...")
                        setUnderlyingNetworks(null)
                        // 短暂延迟，确保系统传播“无网络”状态
                        delay(150)
                        setUnderlyingNetworks(arrayOf(currentNetwork))
                        Log.i(TAG, "System-level network reset triggered (net=$currentNetwork)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to trigger system network reset", e)
                }
            }

            // 5. 重置网络栈 & 清理 DNS
            try {
                requestCoreNetworkReset(reason = "hotSwitch", force = true)
                Log.i(TAG, "Network stack reset")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reset network stack: ${e.message}")
            }
            
            runCatching {
                LogRepository.getInstance().addLog("SUCCESS SingBoxService: Hot switch to $nodeTag completed")
            }
            requestNotificationUpdate(force = true)
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Hot switch failed with unexpected exception", e)
            return false
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    private var boxService: BoxService? = null
    private var currentSettings: AppSettings? = null
    private val serviceSupervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)
    private val cleanupSupervisorJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)
    @Volatile private var isStopping: Boolean = false
    @Volatile private var stopSelfRequested: Boolean = false
    @Volatile private var cleanupJob: Job? = null
    @Volatile private var pendingStartConfigPath: String? = null
    @Volatile private var pendingCleanCache: Boolean = false
    @Volatile private var pendingHotSwitchNodeId: String? = null
    @Volatile private var connectionOwnerPermissionDeniedLogged = false
    @Volatile private var startVpnJob: Job? = null
    @Volatile private var realTimeNodeName: String? = null
    // @Volatile private var nodePollingJob: Job? = null // Removed in favor of CommandClient

    private val isConnectingTun = AtomicBoolean(false)
    @Volatile private var foreignVpnMonitorCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var preExistingVpnNetworks: Set<Network> = emptySet()

    private var commandServer: io.nekohasekai.libbox.CommandServer? = null
    private var commandClient: io.nekohasekai.libbox.CommandClient? = null
    private var commandClientLogs: io.nekohasekai.libbox.CommandClient? = null
    private var commandClientConnections: io.nekohasekai.libbox.CommandClient? = null
    @Volatile private var activeConnectionNode: String? = null
    @Volatile private var activeConnectionLabel: String? = null
    @Volatile private var recentConnectionIds: List<String> = emptyList()
    private val groupSelectedOutbounds = ConcurrentHashMap<String, String>()
    @Volatile private var lastConnectionsLabelLogged: String? = null
    @Volatile private var lastNotificationTextLogged: String? = null
    
    private var lastUplinkTotal: Long = 0
    private var lastDownlinkTotal: Long = 0
    
    // 速度计算相关 - 使用 TrafficStats API
    private var lastSpeedUpdateTime: Long = 0L
    private var currentUploadSpeed: Long = 0L
    private var currentDownloadSpeed: Long = 0L
    @Volatile private var showNotificationSpeed: Boolean = true
    
    // TrafficStats 相关变量
    private var trafficStatsBaseTx: Long = 0L
    private var trafficStatsBaseRx: Long = 0L
    private var trafficStatsLastTx: Long = 0L
    private var trafficStatsLastRx: Long = 0L
    private var trafficStatsLastSampleTime: Long = 0L
    @Volatile private var trafficStatsMonitorJob: Job? = null

    private val coreResetDebounceMs: Long = 2500L
    private val lastCoreNetworkResetAtMs = AtomicLong(0L)
    @Volatile private var coreNetworkResetJob: Job? = null

    @Volatile private var lastRuleSetCheckMs: Long = 0L
    private val ruleSetCheckIntervalMs: Long = 6 * 60 * 60 * 1000L

    private val uidToPackageCache = ConcurrentHashMap<Int, String>()
    private val maxUidToPackageCacheSize: Int = 512

    private fun cacheUidToPackage(uid: Int, pkg: String) {
        if (uid <= 0 || pkg.isBlank()) return
        uidToPackageCache[uid] = pkg
        if (uidToPackageCache.size > maxUidToPackageCacheSize) {
            uidToPackageCache.clear()
        }
    }

    //网络栈重置失败计数器 - 用于检测是否需要完全重启
    private val resetFailureCounter = AtomicInteger(0)
    private val lastSuccessfulResetAt = AtomicLong(0)
    private val maxConsecutiveResetFailures = 3

    private fun requestCoreNetworkReset(reason: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val last = lastCoreNetworkResetAtMs.get()

        // 检查是否需要完全重启而不是仅重置网络栈
        // 如果连续重置失败次数过多,或距离上次成功重置太久,则采用重启策略
        val lastSuccess = lastSuccessfulResetAt.get()
        val timeSinceLastSuccess = now - lastSuccess
        val failures = resetFailureCounter.get()

        if (failures >= maxConsecutiveResetFailures && timeSinceLastSuccess > 30000L) {
            Log.w(TAG, "Too many reset failures ($failures) or too long since last success (${timeSinceLastSuccess}ms), restarting service instead")
            serviceScope.launch {
                restartVpnService(reason = "Excessive network reset failures")
            }
            return
        }

        // 激进的重置策略：对于 Android 14+ 网络切换，更快的响应比防抖更重要
        // 如果是 force（如网络变更），缩短防抖时间到 100ms
        val minInterval = if (force) 100L else coreResetDebounceMs

        if (force) {
            if (now - last < minInterval) return
            lastCoreNetworkResetAtMs.set(now)
            coreNetworkResetJob?.cancel()
            coreNetworkResetJob = null
            serviceScope.launch {
                // 改为单次重置 + 增强清理,而不是多次重试
                // 原因: 多次重试可能导致连接池状态更混乱
                try {
                    // Step 1: 先尝试关闭已有连接 (如果 API 可用)
                    try {
                        boxService?.let { service ->
                            // 使用反射尝试调用 closeConnections (如果存在)
                            val closeMethod = service.javaClass.methods.find {
                                it.name == "closeConnections" && it.parameterCount == 0
                            }
                            closeMethod?.invoke(service)
                            Log.d(TAG, "Closed existing connections before reset")
                        }
                    } catch (e: Exception) {
                        Log.v(TAG, "closeConnections not available or failed: ${e.message}")
                    }

                    // Step 2: 延迟等待连接关闭完成
                    delay(150)

                    // Step 3: 重置网络栈
                    boxService?.resetNetwork()

                    // 重置成功,清除失败计数器
                    resetFailureCounter.set(0)
                    lastSuccessfulResetAt.set(SystemClock.elapsedRealtime())

                    Log.d(TAG, "Core network stack reset triggered (reason=$reason)")
                } catch (e: Exception) {
                    // 重置失败,增加失败计数
                    val newFailures = resetFailureCounter.incrementAndGet()
                    Log.w(TAG, "Failed to reset core network stack (reason=$reason, failures=$newFailures)", e)
                }
            }
            return
        }

        val delayMs = (coreResetDebounceMs - (now - last)).coerceAtLeast(0L)
        if (delayMs <= 0L) {
            lastCoreNetworkResetAtMs.set(now)
            coreNetworkResetJob?.cancel()
            coreNetworkResetJob = null
            serviceScope.launch {
                try {
                    boxService?.resetNetwork()
                    resetFailureCounter.set(0)
                    lastSuccessfulResetAt.set(SystemClock.elapsedRealtime())
                    Log.d(TAG, "Core network stack reset triggered (reason=$reason)")
                } catch (e: Exception) {
                    resetFailureCounter.incrementAndGet()
                    Log.w(TAG, "Failed to reset core network stack (reason=$reason)", e)
                }
            }
            return
        }

        if (coreNetworkResetJob?.isActive == true) return
        coreNetworkResetJob = serviceScope.launch {
            delay(delayMs)
            val t = SystemClock.elapsedRealtime()
            val last2 = lastCoreNetworkResetAtMs.get()
            if (t - last2 < coreResetDebounceMs) return@launch
            lastCoreNetworkResetAtMs.set(t)
            try {
                boxService?.resetNetwork()
                resetFailureCounter.set(0)
                lastSuccessfulResetAt.set(SystemClock.elapsedRealtime())
                Log.d(TAG, "Core network stack reset triggered (reason=$reason)")
            } catch (e: Exception) {
                resetFailureCounter.incrementAndGet()
                Log.w(TAG, "Failed to reset core network stack (reason=$reason)", e)
            }
        }
    }

    /**
     * 重启 VPN 服务以彻底清理网络状态
     * 用于处理网络栈重置无效的严重情况
     */
    private suspend fun restartVpnService(reason: String) = withContext(Dispatchers.Main) {
        Log.i(TAG, "Restarting VPN service: $reason")

        // 保存当前配置路径
        val configPath = lastConfigPath ?: run {
            Log.w(TAG, "Cannot restart: no config path available")
            return@withContext
        }

        try {
            // 停止当前服务 (不停止 Service 本身)
            stopVpn(stopService = false)

            // 等待完全停止
            var waitCount = 0
            while (isStopping && waitCount < 50) {
                delay(100)
                waitCount++
            }

            // 短暂延迟确保资源完全释放
            delay(500)

            // 重新启动
            startVpn(configPath)

            Log.i(TAG, "VPN service restarted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart VPN service", e)
            setLastError("Failed to restart VPN: ${e.message}")
        }
    }

    /**
     * 启动周期性健康检查
     * 定期检查 boxService 是否仍在正常运行,防止 native 崩溃导致僵尸状态
     */
    private fun startPeriodicHealthCheck() {
        periodicHealthCheckJob?.cancel()
        consecutiveHealthCheckFailures = 0

        periodicHealthCheckJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(healthCheckIntervalMs)

                if (!isRunning || isStopping) {
                    break
                }

                try {
                    // 检查 1: boxService 对象是否仍然存在
                    val service = boxService
                    if (service == null) {
                        Log.e(TAG, "Health check failed: boxService is null but isRunning=true")
                        handleHealthCheckFailure("boxService became null")
                        continue
                    }

                    // 检查 2: 验证 VPN 接口仍然有效
                    if (vpnInterface == null) {
                        Log.e(TAG, "Health check failed: vpnInterface is null but isRunning=true")
                        handleHealthCheckFailure("vpnInterface became null")
                        continue
                    }

                    // 检查 3: 尝试调用 boxService 方法验证其响应性
                    withContext(Dispatchers.IO) {
                        try {
                            // 轻量级检查:验证对象引用仍然有效
                            service.toString()

                            // 健康检查通过,重置失败计数器
                            if (consecutiveHealthCheckFailures > 0) {
                                Log.i(TAG, "Health check recovered, failures reset to 0")
                                consecutiveHealthCheckFailures = 0
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Health check failed: boxService method call threw exception", e)
                            handleHealthCheckFailure("boxService exception: ${e.message}")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Periodic health check encountered exception", e)
                    handleHealthCheckFailure("health check exception: ${e.message}")
                }
            }
            Log.i(TAG, "Periodic health check stopped (isRunning=$isRunning)")
        }
    }

    /**
     * 处理健康检查失败
     */
    private fun handleHealthCheckFailure(reason: String) {
        consecutiveHealthCheckFailures++
        Log.w(TAG, "Health check failure #$consecutiveHealthCheckFailures: $reason")

        if (consecutiveHealthCheckFailures >= maxConsecutiveHealthCheckFailures) {
            Log.e(TAG, "Max consecutive health check failures reached, restarting VPN service")
            LogRepository.getInstance().addLog(
                "ERROR: VPN service became unresponsive ($reason), automatically restarting..."
            )

            serviceScope.launch {
                withContext(Dispatchers.Main) {
                    restartVpnService(reason = "Health check failures: $reason")
                }
            }
        }
    }

    /**
     * 注册屏幕状态监听器
     * 在屏幕唤醒时主动检查 VPN 连接健康状态，这是修复 Telegram 等应用切换回来后卡在连接中的关键
     */
    private fun registerScreenStateReceiver() {
        try {
            if (screenStateReceiver != null) {
                Log.d(TAG, "Screen state receiver already registered")
                return
            }

            screenStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            val now = SystemClock.elapsedRealtime()
                            val elapsed = now - lastScreenOnCheckMs

                            if (elapsed < screenOnCheckDebounceMs) {
                                Log.d(TAG, "Screen ON event debounced (${elapsed}ms < ${screenOnCheckDebounceMs}ms)")
                                return
                            }

                            lastScreenOnCheckMs = now
                            Log.i(TAG, "📱 Screen ON detected, checking VPN connection health...")

                            // 在后台协程中执行健康检查
                            serviceScope.launch {
                                delay(800) // 等待系统稳定后再检查
                                performScreenOnHealthCheck()
                            }
                        }
                        Intent.ACTION_SCREEN_OFF -> {
                            Log.d(TAG, "📱 Screen OFF detected")
                            // 屏幕关闭时不做特殊处理，WakeLock 会保持服务运行
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }

            registerReceiver(screenStateReceiver, filter)
            Log.i(TAG, "✅ Screen state receiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen state receiver", e)
        }
    }

    /**
     * 注销屏幕状态监听器
     */
    private fun unregisterScreenStateReceiver() {
        try {
            screenStateReceiver?.let {
                unregisterReceiver(it)
                screenStateReceiver = null
                Log.i(TAG, "Screen state receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen state receiver", e)
        }
    }

    /**
     * 屏幕唤醒时的健康检查
     * 这是修复 Telegram 等应用切换回来后无法连接的核心逻辑
     *
     * 参考实现：
     * - NekoBox 使用类似的屏幕监听 + Wake() 调用机制
     * - libbox 提供了专门的 Wake() 和 ResetNetwork() API
     */
    private suspend fun performScreenOnHealthCheck() {
        if (!isRunning) {
            Log.d(TAG, "VPN not running, skip screen-on health check")
            return
        }

        try {
            Log.i(TAG, "🔍 Performing screen-on health check...")

            // 检查 1: VPN 接口是否有效
            val vpnInterfaceValid = vpnInterface?.fileDescriptor?.valid() == true
            if (!vpnInterfaceValid) {
                Log.e(TAG, "❌ VPN interface invalid after screen on, triggering recovery")
                handleHealthCheckFailure("VPN interface invalid after screen on")
                return
            }

            // 检查 2: boxService 是否响应
            val service = boxService
            if (service == null) {
                Log.e(TAG, "❌ boxService is null after screen on")
                handleHealthCheckFailure("boxService is null after screen on")
                return
            }

            withContext(Dispatchers.IO) {
                try {
                    // **关键修复**: 调用 libbox 的 Wake() 方法通知核心设备唤醒
                    // 这会触发核心内部的连接恢复逻辑，是业界标准做法
                    service.wake()
                    Log.i(TAG, "✅ Called boxService.wake() to notify core about device wake")

                    // 等待核心处理完唤醒逻辑
                    delay(200)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to call boxService.wake()", e)
                    handleHealthCheckFailure("Wake call failed: ${e.message}")
                    return@withContext
                }
            }

            // 检查 3: 网络震荡恢复 - 触发系统重新评估网络连接
            // 这会让 Telegram 等应用收到网络变化通知，重新建立连接
            val currentNetwork = lastKnownNetwork
            if (currentNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Log.i(TAG, "🔄 Triggering network oscillation to refresh app connections...")
                withContext(Dispatchers.IO) {
                    try {
                        // 短暂断开底层网络，让应用层感知到网络变化
                        setUnderlyingNetworks(null)
                        delay(150) // 150ms 足够触发回调
                        setUnderlyingNetworks(arrayOf(currentNetwork))
                        Log.i(TAG, "✅ Network oscillation completed successfully")
                    } catch (e: Exception) {
                        Log.w(TAG, "Network oscillation failed", e)
                    }
                }
            }

            // 检查 4: 主动触发核心网络重置（轻量级）
            requestCoreNetworkReset(reason = "screen_on_health_check", force = false)

            Log.i(TAG, "✅ Screen-on health check passed, VPN connection should be healthy now")
            consecutiveHealthCheckFailures = 0

        } catch (e: Exception) {
            Log.e(TAG, "Screen-on health check failed", e)
            handleHealthCheckFailure("Screen-on check exception: ${e.message}")
        }
    }

    /**
     * 轻量级健康检查
     * 用于网络恢复等场景，只做基本验证而不触发完整的重启流程
     */
    private suspend fun performLightweightHealthCheck() {
        if (!isRunning) return

        try {
            Log.d(TAG, "Performing lightweight health check...")

            // 检查 VPN 接口和 boxService 基本状态
            val vpnInterfaceValid = vpnInterface?.fileDescriptor?.valid() == true
            val boxServiceValid = boxService != null

            if (!vpnInterfaceValid || !boxServiceValid) {
                Log.w(TAG, "Lightweight health check found issues (vpnInterface=$vpnInterfaceValid, boxService=$boxServiceValid)")
                // 不立即触发重启，只记录，让定期检查来处理
                return
            }

            Log.d(TAG, "✓ Lightweight health check passed")
        } catch (e: Exception) {
            Log.w(TAG, "Lightweight health check failed", e)
        }
    }

    // Auto reconnect
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null
    private var defaultInterfaceName: String = ""
    private var lastKnownNetwork: Network? = null
    private var vpnHealthJob: Job? = null
    @Volatile private var vpnLinkValidated: Boolean = false

    // Auto reconnect states
    private var autoReconnectEnabled: Boolean = false
    private var lastAutoReconnectAttemptMs: Long = 0L
    private val autoReconnectDebounceMs: Long = 10000L
    private var autoReconnectJob: Job? = null

    // 网络就绪标志：确保 Libbox 启动前网络回调已完成初始采样
    @Volatile private var networkCallbackReady: Boolean = false
    @Volatile private var noPhysicalNetworkWarningLogged: Boolean = false
    @Volatile private var postTunRebindJob: Job? = null

    // setUnderlyingNetworks 防抖机制 - 避免频繁调用触发系统提示音
    private val lastSetUnderlyingNetworksAtMs = AtomicLong(0)
    private val setUnderlyingNetworksDebounceMs: Long = 2000L // 2秒防抖
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // 屏幕状态监听器 - 用于在屏幕唤醒时检查连接健康
    private var screenStateReceiver: BroadcastReceiver? = null
    @Volatile private var lastScreenOnCheckMs: Long = 0L
    private val screenOnCheckDebounceMs: Long = 3000L // 屏幕开启后 3 秒才检查，避免频繁触发

    // Periodic health check states
    private var periodicHealthCheckJob: Job? = null
    private val healthCheckIntervalMs: Long = 15000L // 每 15 秒检查一次
    @Volatile private var consecutiveHealthCheckFailures: Int = 0
    private val maxConsecutiveHealthCheckFailures: Int = 3 // 连续失败 3 次触发重启

    // Platform interface implementation
private val platformInterface = object : PlatformInterface {
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
        return com.kunk.singbox.core.LocalResolverImpl
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        val result = protect(fd)
        if (result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "autoDetectInterfaceControl: protect($fd) success")
            }
        } else {
            Log.e(TAG, "autoDetectInterfaceControl: protect($fd) failed")
            // 记录到用户日志，方便反馈
            runCatching {
                com.kunk.singbox.repository.LogRepository.getInstance()
                    .addLog("ERROR: protect($fd) failed")
            }
        }
    }
    
    override fun openTun(options: TunOptions?): Int {
            Log.v(TAG, "openTun called")
            if (options == null) return -1
            isConnectingTun.set(true)

            try {
                // Close existing interface if any to prevent fd leaks and "zombie" states
                synchronized(this@SingBoxService) {
                    vpnInterface?.let {
                        Log.w(TAG, "Closing stale vpnInterface before establishing new one")
                        try { it.close() } catch (_: Exception) {}
                        vpnInterface = null
                    }
                }

                val settings = currentSettings
                val builder = Builder()
                    .setSession("KunBox VPN")
                    .setMtu(if (options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500))

                // 添加地址
                builder.addAddress("172.19.0.1", 30)
                builder.addAddress("fd00::1", 126)

                // 添加路由
                val routeMode = settings?.vpnRouteMode ?: VpnRouteMode.GLOBAL
                val cidrText = settings?.vpnRouteIncludeCidrs.orEmpty()
                val cidrs = cidrText
                    .split("\n", "\r", ",", ";", " ", "\t")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                fun addCidrRoute(cidr: String): Boolean {
                    val parts = cidr.split("/")
                    if (parts.size != 2) return false
                    val ip = parts[0].trim()
                    val prefix = parts[1].trim().toIntOrNull() ?: return false
                    return try {
                        val addr = InetAddress.getByName(ip)
                        builder.addRoute(addr, prefix)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                val usedCustomRoutes = if (routeMode == VpnRouteMode.CUSTOM) {
                    var okCount = 0
                    cidrs.forEach { if (addCidrRoute(it)) okCount++ }
                    okCount > 0
                } else {
                    false
                }

                if (!usedCustomRoutes) {
                    // fallback: 全局接管
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                }

                // 添加 DNS (优先使用设置中的 DNS)
                val dnsServers = mutableListOf<String>()
                if (settings != null) {
                    if (isNumericAddress(settings.remoteDns)) dnsServers.add(settings.remoteDns)
                    if (isNumericAddress(settings.localDns)) dnsServers.add(settings.localDns)
                }

                if (dnsServers.isEmpty()) {
                    dnsServers.add("223.5.5.5")
                    dnsServers.add("119.29.29.29")
                    dnsServers.add("1.1.1.1")
                }

                dnsServers.distinct().forEach {
                    try {
                        builder.addDnsServer(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add DNS server: $it", e)
                    }
                }

                // 分应用
                fun parsePackageList(raw: String): List<String> {
                    return raw
                        .split("\n", "\r", ",", ";", " ", "\t")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                }

                val appMode = settings?.vpnAppMode ?: VpnAppMode.ALL
                val allowPkgs = parsePackageList(settings?.vpnAllowlist.orEmpty())
                val blockPkgs = parsePackageList(settings?.vpnBlocklist.orEmpty())

                try {
                    when (appMode) {
                        VpnAppMode.ALL -> {
                            builder.addDisallowedApplication(packageName)
                        }
                        VpnAppMode.ALLOWLIST -> {
                            if (allowPkgs.isEmpty()) {
                                Log.w(TAG, "Allowlist is empty, falling back to ALL mode (excluding self)")
                                builder.addDisallowedApplication(packageName)
                            } else {
                                var addedCount = 0
                                allowPkgs.forEach { pkg ->
                                    if (pkg == packageName) return@forEach
                                    try {
                                        builder.addAllowedApplication(pkg)
                                        addedCount++
                                    } catch (e: PackageManager.NameNotFoundException) {
                                        Log.w(TAG, "Allowed app not found: $pkg")
                                    }
                                }
                                if (addedCount == 0) {
                                    Log.w(TAG, "No valid apps in allowlist, falling back to ALL mode")
                                    builder.addDisallowedApplication(packageName)
                                }
                            }
                        }
                        VpnAppMode.BLOCKLIST -> {
                            blockPkgs.forEach { pkg ->
                                try {
                                    builder.addDisallowedApplication(pkg)
                                } catch (e: PackageManager.NameNotFoundException) {
                                    Log.w(TAG, "Disallowed app not found: $pkg")
                                }
                            }
                            builder.addDisallowedApplication(packageName)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply per-app VPN settings")
                }

                // === CRITICAL SECURITY SETTINGS (Best practices from open-source VPN projects) ===

                // 1. Kill Switch: Prevent apps from bypassing VPN (like Clash/SagerNet)
                // This ensures NO traffic can leak outside VPN tunnel
                // NOTE: In Android API, NOT calling allowBypass() means bypass is disabled by default
                // We explicitly skip calling allowBypass() to ensure kill switch is active
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // Do NOT call builder.allowBypass() - this enables kill switch
                    Log.i(TAG, "Kill switch enabled: NOT calling allowBypass() (bypass disabled by default)")
                }

                // 2. Blocking mode: Prevent connection leaks during VPN startup
                // This blocks network operations until VPN is fully established
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        builder.setBlocking(true)
                        Log.i(TAG, "Blocking mode enabled: setBlocking(true)")
                    } catch (e: Exception) {
                        Log.w(TAG, "setBlocking not supported on this device", e)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)

                    // 追加 HTTP 代理至 VPN
                    if (settings?.appendHttpProxy == true && settings.proxyPort > 0) {
                        try {
                            builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", settings.proxyPort))
                            Log.i(TAG, "HTTP Proxy appended to VPN: 127.0.0.1:${settings.proxyPort}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to set HTTP proxy for VPN", e)
                        }
                    }
                }

                // 设置底层网络 - 关键！让 VPN 流量可以通过物理网络出去
                // 修复: 延迟设置,避免在 TUN 建立瞬间就暴露网络(导致应用过早发起连接)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val activePhysicalNetwork = findBestPhysicalNetwork()

                    if (activePhysicalNetwork != null) {
                        val caps = connectivityManager?.getNetworkCapabilities(activePhysicalNetwork)
                        val capsStr = buildString {
                            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) append("INTERNET ")
                            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) append("NOT_VPN ")
                            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) append("VALIDATED ")
                            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) append("WIFI ")
                            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) append("CELLULAR ")
                        }

                        // 关键修复: 先不设置底层网络,等 VPN 核心就绪后再设置
                        // 这样可以防止应用在 VPN 未完全就绪时就开始发送流量
                        // builder.setUnderlyingNetworks(arrayOf(activePhysicalNetwork))

                        Log.i(TAG, "Physical network detected: $activePhysicalNetwork (caps: $capsStr) - will be set after core ready")
                        com.kunk.singbox.repository.LogRepository.getInstance()
                            .addLog("INFO openTun: found network = $activePhysicalNetwork ($capsStr)")
                    } else {
                        // 无物理网络，记录一次性警告
                        if (!noPhysicalNetworkWarningLogged) {
                            noPhysicalNetworkWarningLogged = true
                            Log.w(TAG, "No physical network found for underlying networks - VPN may not work correctly!")
                            com.kunk.singbox.repository.LogRepository.getInstance()
                                .addLog("WARN openTun: No physical network found - traffic may be blackholed!")
                        }
                        builder.setUnderlyingNetworks(null) // Let system decide
                        schedulePostTunRebind("openTun_no_physical")
                    }
                }

                val alwaysOnPkg = runCatching {
                    Settings.Secure.getString(contentResolver, "always_on_vpn_app")
                }.getOrNull() ?: runCatching {
                    Settings.Global.getString(contentResolver, "always_on_vpn_app")
                }.getOrNull()

                val lockdownValueSecure = runCatching {
                    Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0)
                }.getOrDefault(0)
                val lockdownValueGlobal = runCatching {
                    Settings.Global.getInt(contentResolver, "always_on_vpn_lockdown", 0)
                }.getOrDefault(0)
                val lockdown = lockdownValueSecure != 0 || lockdownValueGlobal != 0

                if (!alwaysOnPkg.isNullOrBlank() || lockdown) {
                    Log.i(TAG, "Always-on VPN status: pkg=$alwaysOnPkg lockdown=$lockdown")
                }

                if (lockdown && !alwaysOnPkg.isNullOrBlank() && alwaysOnPkg != packageName) {
                    throw IllegalStateException("VPN lockdown enabled by $alwaysOnPkg")
                }

                val backoffMs = longArrayOf(0L, 250L, 250L, 500L, 500L, 1000L, 1000L, 2000L, 2000L, 2000L)
                var lastFd = -1
                var attempt = 0
                for (sleepMs in backoffMs) {
                    if (isStopping) {
                        throw IllegalStateException("VPN stopping")
                    }
                    if (sleepMs > 0) {
                        SystemClock.sleep(sleepMs)
                    }
                    attempt++
                    vpnInterface = builder.establish()
                    lastFd = vpnInterface?.fd ?: -1
                    if (vpnInterface != null && lastFd >= 0) {
                        break
                    }
                    try { vpnInterface?.close() } catch (_: Exception) {}
                    vpnInterface = null
                }

                val fd = lastFd
                if (vpnInterface == null || fd < 0) {
                    val cm = connectivityManager
                    val otherVpnActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
                        runCatching {
                            cm.allNetworks.any { network ->
                                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            }
                        }.getOrDefault(false)
                    } else {
                        false
                    }
                    val reason = "VPN interface establish failed (fd=$fd, alwaysOn=$alwaysOnPkg, lockdown=$lockdown, otherVpn=$otherVpnActive)"
                    Log.e(TAG, reason)
                    throw IllegalStateException(reason)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val bestNetwork = findBestPhysicalNetwork()
                    if (bestNetwork != null) {
                        try {
                            // === 关键修复: 立即清空底层网络,阻止应用过早建连 ===
                            // 问题: establish() 可能会自动绑定底层网络,导致应用立即可以发送流量
                            // 解决: 强制设置为 null,延迟到核心就绪后再设置
                            setUnderlyingNetworks(null)
                            lastKnownNetwork = bestNetwork
                            Log.i(TAG, "Physical network cached: $bestNetwork, underlying set to NULL (will be set after core ready)")
                        } catch (_: Exception) {
                        }
                    }
                }

                Log.i(TAG, "TUN interface established with fd: $fd, underlying networks = NULL (blocked until core ready)")

                // === 移除旧的 Stage 1 震荡逻辑,改为延迟设置底层网络 ===
                // 新策略: TUN 建立后不暴露底层网络,等核心就绪后一次性设置
                // 这样可以避免应用在 VPN 未就绪时发起连接

                // === CRITICAL: Trigger immediate network validation (Best practice from NekoBox) ===
                // Network reset is handled by requestCoreNetworkReset below
                // BUG修复: 移除 reportNetworkConnectivity() 调用,避免在华为等设备上触发持续的系统提示音
                // 参考: Android VPN 最佳实践,成熟 VPN 项目不使用 reportNetworkConnectivity 主动触发网络验证
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.d(TAG, "VPN network established, relying on requestCoreNetworkReset for app reconnection")
                }

                // Force a network reset after TUN is ready to clear any stale connections
                // force=true to ensure immediate update, skipping debounce
                requestCoreNetworkReset(reason = "openTun:success", force = true)
                
                return fd
            } finally {
                isConnectingTun.set(false)
            }
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun useProcFS(): Boolean {
            val procPaths = listOf(
                "/proc/net/tcp",
                "/proc/net/tcp6",
                "/proc/net/udp",
                "/proc/net/udp6"
            )

            fun hasUidHeader(path: String): Boolean {
                return try {
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) return false
                    val header = file.bufferedReader().use { it.readLine() } ?: return false
                    header.contains("uid")
                } catch (_: Exception) {
                    false
                }
            }

            val readable = procPaths.all { path -> hasUidHeader(path) }

            if (!readable) {
                connectionOwnerLastEvent = "procfs_unreadable_or_no_uid -> force findConnectionOwner"
            }

            return readable
        }
         
        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String?,
            sourcePort: Int,
            destinationAddress: String?,
            destinationPort: Int
        ): Int {
            connectionOwnerCalls.incrementAndGet()

            fun findUidFromProcFsBySourcePort(protocol: Int, srcPort: Int): Int {
                if (srcPort <= 0) return 0

                val procFiles = when (protocol) {
                    OsConstants.IPPROTO_TCP -> listOf("/proc/net/tcp", "/proc/net/tcp6")
                    OsConstants.IPPROTO_UDP -> listOf("/proc/net/udp", "/proc/net/udp6")
                    else -> emptyList()
                }
                if (procFiles.isEmpty()) return 0

                val targetPortHex = srcPort.toString(16).uppercase().padStart(4, '0')

                fun parseUidFromLine(parts: List<String>): Int {
                    if (parts.size < 9) return 0
                    val uidStr = parts.getOrNull(7) ?: return 0
                    return uidStr.toIntOrNull() ?: 0
                }

                for (path in procFiles) {
                    try {
                        val file = File(path)
                        if (!file.exists() || !file.canRead()) continue
                        var resultUid = 0
                        file.bufferedReader().useLines { lines ->
                            for (line in lines.drop(1)) {
                                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                                val local = parts.getOrNull(1) ?: continue
                                val portHex = local.substringAfter(':', "").uppercase()
                                if (portHex == targetPortHex) {
                                    val uid = parseUidFromLine(parts)
                                    if (uid > 0) {
                                        resultUid = uid
                                        break
                                    }
                                }
                            }
                        }
                        if (resultUid > 0) return resultUid
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to read proc file: $path", e)
                    }
                }
                return 0
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                connectionOwnerInvalidArgs.incrementAndGet()
                connectionOwnerLastEvent = "api<29"
                return 0
            }

            fun parseAddress(value: String?): InetAddress? {
                if (value.isNullOrBlank()) return null
                // Remove brackets for IPv6 [::1] -> ::1 and remove scope ID
                val cleaned = value.trim().replace("[", "").replace("]", "").substringBefore("%")
                return try {
                    InetAddress.getByName(cleaned)
                } catch (_: Exception) {
                    null
                }
            }

            val sourceIp = parseAddress(sourceAddress)
            val destinationIp = parseAddress(destinationAddress)

            val protocol = when (ipProtocol) {
                OsConstants.IPPROTO_TCP -> OsConstants.IPPROTO_TCP
                OsConstants.IPPROTO_UDP -> OsConstants.IPPROTO_UDP
                else -> ipProtocol
            }

            if (sourceIp == null || sourcePort <= 0 || destinationIp == null || destinationPort <= 0) {
                val uid = findUidFromProcFsBySourcePort(protocol, sourcePort)
                if (uid > 0) {
                    connectionOwnerUidResolved.incrementAndGet()
                    connectionOwnerLastUid = uid
                    connectionOwnerLastEvent =
                        "procfs_fallback uid=$uid proto=$protocol src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort"
                    return uid
                }

                connectionOwnerInvalidArgs.incrementAndGet()
                connectionOwnerLastEvent =
                    "invalid_args src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort proto=$ipProtocol"
                return 0
            }

            return try {
                val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java) ?: return 0
                val uid = cm.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(sourceIp, sourcePort),
                    InetSocketAddress(destinationIp, destinationPort)
                )
                if (uid > 0) {
                    connectionOwnerUidResolved.incrementAndGet()
                    connectionOwnerLastUid = uid
                    connectionOwnerLastEvent =
                        "resolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                    uid
                } else {
                    connectionOwnerLastEvent =
                        "unresolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                    0
                }
            } catch (e: SecurityException) {
                connectionOwnerSecurityDenied.incrementAndGet()
                connectionOwnerLastEvent =
                    "SecurityException findConnectionOwner proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                if (!connectionOwnerPermissionDeniedLogged) {
                    connectionOwnerPermissionDeniedLogged = true
                    Log.w(TAG, "findConnectionOwner permission denied; app routing may not work on this ROM", e)
                    com.kunk.singbox.repository.LogRepository.getInstance()
                        .addLog("WARN SingBoxService: findConnectionOwner permission denied; per-app routing (package_name) disabled on this ROM")
                }
                0
            } catch (e: Exception) {
                connectionOwnerOtherException.incrementAndGet()
                connectionOwnerLastEvent = "Exception ${e.javaClass.simpleName}: ${e.message}"
                0
            }
        }
        
        override fun packageNameByUid(uid: Int): String {
            if (uid <= 0) return ""
            return try {
                val pkgs = packageManager.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) {
                    pkgs[0]
                } else {
                    val name = runCatching { packageManager.getNameForUid(uid) }.getOrNull().orEmpty()
                    if (name.isNotBlank()) {
                        cacheUidToPackage(uid, name)
                        name
                    } else {
                        uidToPackageCache[uid] ?: ""
                    }
                }
            } catch (_: Exception) {
                val name = runCatching { packageManager.getNameForUid(uid) }.getOrNull().orEmpty()
                if (name.isNotBlank()) {
                    cacheUidToPackage(uid, name)
                    name
                } else {
                    uidToPackageCache[uid] ?: ""
                }
            }
        }
        
        override fun uidByPackageName(packageName: String?): Int {
            if (packageName.isNullOrBlank()) return 0
            return try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val uid = appInfo.uid
                if (uid > 0) uid else 0
            } catch (_: Exception) {
                0
            }
        }
        
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            Log.v(TAG, "startDefaultInterfaceMonitor")
            currentInterfaceListener = listener
            
            connectivityManager = getSystemService(ConnectivityManager::class.java)
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = connectivityManager?.getNetworkCapabilities(network)
                    val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    Log.i(TAG, "Network available: $network (isVpn=$isVpn)")
                    if (!isVpn) {
                        // Check if this network is the system's active default network
                        val isActiveDefault = connectivityManager?.activeNetwork == network
                        if (isActiveDefault) {
                            networkCallbackReady = true
                            updateDefaultInterface(network)
                            // Ensure libbox is aware of the new physical interface immediately
                            requestCoreNetworkReset(reason = "networkAvailable:$network", force = true)

                            // 在网络恢复时触发一次轻量级健康检查
                            // 这可以帮助在网络切换（如 WiFi <-> 移动数据）时快速恢复连接
                            serviceScope.launch {
                                delay(500) // 等待网络稳定
                                if (isRunning) {
                                    Log.d(TAG, "🔍 Triggering health check after network recovery")
                                    performLightweightHealthCheck()
                                }
                            }
                        } else {
                            Log.v(TAG, "Network available but not active default, ignoring for now: $network")
                        }
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.i(TAG, "Network lost: $network")
                    if (network == lastKnownNetwork) {
                        // Check if there is another active network immediately
                        val newActive = connectivityManager?.activeNetwork
                        if (newActive != null) {
                             Log.i(TAG, "Old network lost, switching to new active: $newActive")
                             updateDefaultInterface(newActive)
                             requestCoreNetworkReset(reason = "networkLost_switch:$network->$newActive", force = true)
                        } else {
                             lastKnownNetwork = null
                             currentInterfaceListener?.updateDefaultInterface("", 0, false, false)
                        }
                    } else {
                        // Even if a non-active network is lost, notify libbox to update interfaces
                        // This prevents "no such network interface" errors when libbox tries to use a stale interface ID
                        requestCoreNetworkReset(reason = "networkLost_other:$network", force = false)
                    }
                }
                
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    if (!isVpn) {
                        // Only react if this is the active network
                        if (connectivityManager?.activeNetwork == network) {
                            networkCallbackReady = true
                            updateDefaultInterface(network)
                            // Trigger reset to ensure libbox updates its interface list if properties changed
                            requestCoreNetworkReset(reason = "networkCapsChanged:$network", force = false)
                        }
                    }
                }
            }
            
            // Listen to all networks but filter in callback to respect system default
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)

            // VPN Health Monitor with enhanced rebind logic
            vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!isRunning) return
                    val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (isValidated) {
                        vpnLinkValidated = true
                        if (vpnHealthJob?.isActive == true) {
                            Log.i(TAG, "VPN link validated, cancelling recovery job")
                            vpnHealthJob?.cancel()
                        }
                        // One-time warmup for native URLTest to avoid cold-start -1 on Nodes page
                        // NOTE: Service-side URLTest warmup removed to avoid cross-process bbolt
                        // database race condition with Dashboard's ping test. The Dashboard already
                        // triggers a ping test when VPN becomes connected (via SingBoxRemote.isRunning),
                        // so this warmup was redundant and caused "page already freed" panic when
                        // both processes accessed the same bbolt db simultaneously.
                        // See: https://github.com/sagernet/bbolt - concurrent access from multiple
                        // processes to the same db file without proper locking causes corruption.
                    } else {
                        // Start a delayed recovery if not already running
                        if (vpnHealthJob?.isActive != true) {
                            Log.w(TAG, "VPN link not validated, scheduling recovery in 5s")
                            vpnHealthJob = serviceScope.launch {
                                delay(5000)
                                if (isRunning && !isStarting && !isManuallyStopped && lastConfigPath != null) {
                                    Log.w(TAG, "VPN link still not validated after 5s, attempting rebind and reset")
                                    try {
                                        // 尝试重新绑定底层网络
                                        val bestNetwork = findBestPhysicalNetwork()
                                        if (bestNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                                            setUnderlyingNetworks(arrayOf(bestNetwork))
                                            lastKnownNetwork = bestNetwork
                                            Log.i(TAG, "Rebound underlying network to $bestNetwork during health recovery")
                                            com.kunk.singbox.repository.LogRepository.getInstance()
                                                .addLog("INFO VPN health recovery: rebound to $bestNetwork")
                                        }
                                        requestCoreNetworkReset(reason = "vpnHealthRecovery", force = false)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to reset network stack during health recovery", e)
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    vpnHealthJob?.cancel()
                }
            }

            val vpnRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            try {
                connectivityManager?.registerNetworkCallback(vpnRequest, vpnNetworkCallback!!)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register VPN network callback", e)
            }
            
            // Get current default interface - 立即采样一次以初始化 lastKnownNetwork
            val activeNet = connectivityManager?.activeNetwork
            if (activeNet != null) {
                val caps = connectivityManager?.getNetworkCapabilities(activeNet)
                val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (!isVpn && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                    networkCallbackReady = true
                    updateDefaultInterface(activeNet)
                }
            }
        }
        
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            Log.v(TAG, "closeDefaultInterfaceMonitor")
            networkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }
            vpnNetworkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }
            vpnHealthJob?.cancel()
            postTunRebindJob?.cancel()
            postTunRebindJob = null
            vpnNetworkCallback = null
            networkCallback = null
            currentInterfaceListener = null
            networkCallbackReady = false
        }
        
        override fun getInterfaces(): NetworkInterfaceIterator? {
            return try {
                val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                object : NetworkInterfaceIterator {
                    private val iterator = interfaces.filter {
                        // Do not filter by isUp. During VPN restart, interfaces might briefly flap.
                        // libbox needs to see all interfaces to configure routing correctly.
                        !it.isLoopback
                    }.iterator()
                    
                    override fun hasNext(): Boolean = iterator.hasNext()
                    
                    override fun next(): io.nekohasekai.libbox.NetworkInterface {
                        val iface = iterator.next()
                        return io.nekohasekai.libbox.NetworkInterface().apply {
                            name = iface.name
                            index = iface.index
                            mtu = iface.mtu
                            
                            // type = ... (Field removed in v1.10)
                            
                            // Flags
                            var flagsStr = 0
                            if (iface.isUp) flagsStr = flagsStr or 1
                            if (iface.isLoopback) flagsStr = flagsStr or 4
                            if (iface.isPointToPoint) flagsStr = flagsStr or 8
                            if (iface.supportsMulticast()) flagsStr = flagsStr or 16
                            flags = flagsStr
                            
                            // Addresses
                            val addrList = ArrayList<String>()
                            for (addr in iface.interfaceAddresses) {
                                val ip = addr.address.hostAddress
                                // Remove %interface suffix if present (IPv6)
                                val cleanIp = if (ip != null && ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                                if (cleanIp != null) {
                                    addrList.add("$cleanIp/${addr.networkPrefixLength}")
                                }
                            }
                            addresses = StringIteratorImpl(addrList)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get interfaces", e)
                null
            }
        }
        
        override fun underNetworkExtension(): Boolean = false
        
        override fun includeAllNetworks(): Boolean = false
        
        override fun readWIFIState(): WIFIState? = null
        
        override fun clearDNSCache() {}
        
        override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {}
        
        override fun systemCertificates(): StringIterator? = null
        
        override fun writeLog(message: String?) {
            if (message.isNullOrBlank()) return
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "libbox: $message")
            }
            com.kunk.singbox.repository.LogRepository.getInstance().addLog(message)
        }
    }
    
    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }
    
    /**
     * 查找最佳物理网络（非 VPN、有 Internet 能力，优先 VALIDATED）
     */
    private fun findBestPhysicalNetwork(): Network? {
        val cm = connectivityManager ?: return null
        
        // 优先使用已缓存的 lastKnownNetwork（如果仍然有效）
        lastKnownNetwork?.let { cached ->
            val caps = cm.getNetworkCapabilities(cached)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return cached
            }
        }
        
        // 遍历所有网络，筛选物理网络
        // [Fix] 优先返回系统默认的 Active Network，只有当其无效时才自己筛选
        // Android 系统会自动处理 WiFi/流量切换，我们强行选择可能导致与系统路由表冲突
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            val caps = cm.getNetworkCapabilities(activeNetwork)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                // 如果系统已经选好了一个物理网络，直接用它，不要自己选
                // 这能最大程度避免 Sing-box 选了 WiFi 但系统正在切流量（或反之）导致的 operation not permitted
                Log.d(TAG, "findBestPhysicalNetwork: using system active network $activeNetwork")
                return activeNetwork
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val allNetworks = cm.allNetworks
            var bestNetwork: Network? = null
            var bestScore = -1
            
            for (net in allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                
                if (hasInternet && notVpn) {
                    var score = 0
                    if (validated) {
                        if (isEthernet) score = 5
                        else if (isWifi) score = 4
                        else if (isCellular) score = 3
                    } else {
                        if (isEthernet) score = 2
                        else if (isWifi) score = 2
                        else if (isCellular) score = 1
                    }
                    
                    if (score > bestScore) {
                        bestScore = score
                        bestNetwork = net
                    }
                }
            }
            
            if (bestNetwork != null) {
                Log.d(TAG, "findBestPhysicalNetwork: fallback selected $bestNetwork (score=$bestScore)")
                return bestNetwork
            }
        }
        
        // fallback: 使用 activeNetwork
        return cm.activeNetwork?.takeIf {
            val caps = cm.getNetworkCapabilities(it)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        }
    }

    /**
     * DNS 预热: 预解析常见域名,避免首次查询超时导致用户感知延迟
     * 这是解决 VPN 启动后首次访问 GitHub 等网站时加载缓慢的关键优化
     */
    private fun warmupDnsCache() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting DNS warmup...")
                val startTime = System.currentTimeMillis()

                // 常见域名列表 (根据用户使用场景调整)
                val domains = listOf(
                    "www.google.com",
                    "github.com",
                    "api.github.com",
                    "www.youtube.com",
                    "twitter.com",
                    "facebook.com"
                )

                // 并发预解析,最多等待 1.5 秒
                withTimeoutOrNull(1500L) {
                    domains.map { domain ->
                        async {
                            try {
                                // 通过 InetAddress 触发系统 DNS 解析,结果会被缓存
                                InetAddress.getByName(domain)
                                Log.v(TAG, "DNS warmup: resolved $domain")
                            } catch (e: Exception) {
                                Log.v(TAG, "DNS warmup: failed to resolve $domain (${e.javaClass.simpleName})")
                            }
                        }
                    }.awaitAll()
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "DNS warmup completed in ${elapsed}ms")
            } catch (e: Exception) {
                Log.w(TAG, "DNS warmup failed", e)
            }
        }
    }

    private fun updateDefaultInterface(network: Network) {
        try {
            // 验证网络是否为有效的物理网络
            val caps = connectivityManager?.getNetworkCapabilities(network)
            val isValidPhysical = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                                  caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
            
            if (!isValidPhysical) {
                Log.v(TAG, "updateDefaultInterface: network $network is not a valid physical network, skipping")
                return
            }
            
            val linkProperties = connectivityManager?.getLinkProperties(network)
            val interfaceName = linkProperties?.interfaceName ?: ""
            val upstreamChanged = interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName

            // 检查当前网络是否真的是 Active Network
            // 如果网络正在切换，activeNetwork 可能短暂为 null 或旧网络，我们需要信任回调传入的 network
            // 但如果 activeNetwork 明确指向另一个网络，我们应该谨慎
            val systemActive = connectivityManager?.activeNetwork
            if (systemActive != null && systemActive != network) {
                 Log.w(TAG, "updateDefaultInterface: requested $network but system active is $systemActive. Potential conflict.")
                 // 仍然设置，因为回调可能比 activeNetwork 属性更新
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && (network != lastKnownNetwork || upstreamChanged)) {
                // 防抖检查：避免频繁调用 setUnderlyingNetworks 触发系统提示音
                val now = SystemClock.elapsedRealtime()
                val lastSet = lastSetUnderlyingNetworksAtMs.get()
                val timeSinceLastSet = now - lastSet

                // 只在距离上次设置超过防抖时间，或网络真正变化时才设置
                if (timeSinceLastSet >= setUnderlyingNetworksDebounceMs || network != lastKnownNetwork) {
                    setUnderlyingNetworks(arrayOf(network))
                    lastSetUnderlyingNetworksAtMs.set(now)
                    lastKnownNetwork = network
                    noPhysicalNetworkWarningLogged = false // 重置警告标志
                    postTunRebindJob?.cancel()
                    postTunRebindJob = null
                    Log.i(TAG, "Switched underlying network to $network (upstream=$interfaceName, debounce=${timeSinceLastSet}ms)")

                    // 强制重置，因为网络变更通常伴随着 IP/Interface 变更
                    requestCoreNetworkReset(reason = "underlyingNetworkChanged", force = true)
                } else {
                    Log.v(TAG, "Skipped setUnderlyingNetworks due to debounce (${timeSinceLastSet}ms < ${setUnderlyingNetworksDebounceMs}ms)")
                }
            }

            val now = System.currentTimeMillis()
            if (autoReconnectEnabled && !isRunning && !isStarting && lastConfigPath != null) {
                if (!isManuallyStopped && now - lastAutoReconnectAttemptMs >= autoReconnectDebounceMs) {
                    Log.i(TAG, "Auto-reconnect triggered: interface=$interfaceName")
                    lastAutoReconnectAttemptMs = now
                    autoReconnectJob?.cancel()
                    autoReconnectJob = serviceScope.launch {
                        delay(800)
                        if (!isRunning && !isStarting && !isManuallyStopped && lastConfigPath != null) {
                            Log.i(TAG, "Auto-reconnecting now executing startVpn")
                            startVpn(lastConfigPath!!)
                        }
                    }
                } else {
                    Log.d(TAG, "Auto-reconnect skipped: manuallyStopped=$isManuallyStopped, debounce=${now - lastAutoReconnectAttemptMs < autoReconnectDebounceMs}")
                }
            }

            if (interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName) {
                defaultInterfaceName = interfaceName
                val index = try {
                    NetworkInterface.getByName(interfaceName)?.index ?: 0
                } catch (e: Exception) { 0 }
                val caps = connectivityManager?.getNetworkCapabilities(network)
                val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                val isConstrained = false
                Log.i(TAG, "Default interface updated: $interfaceName (index: $index)")
                currentInterfaceListener?.updateDefaultInterface(interfaceName, index, isExpensive, isConstrained)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update default interface", e)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "SingBoxService onCreate: pid=${android.os.Process.myPid()} instance=${System.identityHashCode(this)}")
        instance = this
        
        // Restore manually stopped state from persistent storage
        isManuallyStopped = VpnStateStore.isManuallyStopped(applicationContext)
        Log.i(TAG, "Restored isManuallyStopped state: $isManuallyStopped")

        createNotificationChannel()
        // 初始化 ConnectivityManager
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        serviceScope.launch {
            lastErrorFlow.collect {
                requestRemoteStateUpdate(force = false)
            }
        }

        serviceScope.launch {
            SettingsRepository.getInstance(this@SingBoxService)
                .settings
                .map { it.autoReconnect }
                .distinctUntilChanged()
                .collect { enabled ->
                    autoReconnectEnabled = enabled
                }
        }
        
        // 监听活动节点变化，更新通知
        serviceScope.launch {
            ConfigRepository.getInstance(this@SingBoxService).activeNodeId.collect { activeNodeId ->
                if (isRunning) {
                    requestNotificationUpdate(force = false)
                    requestRemoteStateUpdate(force = false)
                }
            }
        }
        
        // 监听通知栏速度显示设置变化
        serviceScope.launch {
            SettingsRepository.getInstance(this@SingBoxService)
                .settings
                .map { it.showNotificationSpeed }
                .distinctUntilChanged()
                .collect { enabled ->
                    showNotificationSpeed = enabled
                    if (isRunning) {
                        requestNotificationUpdate(force = true)
                    }
                }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        runCatching {
            LogRepository.getInstance().addLog("INFO SingBoxService: onStartCommand action=${intent?.action}")
        }
        when (intent?.action) {
            ACTION_START -> {
                isManuallyStopped = false
                VpnStateStore.setManuallyStopped(applicationContext, false)
                VpnTileService.persistVpnPending(applicationContext, "starting")
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                val cleanCache = intent.getBooleanExtra(EXTRA_CLEAN_CACHE, false)
                
                if (configPath != null) {
                    updateServiceState(ServiceState.STARTING)
                    synchronized(this) {
                        // FIX: Ensure pendingCleanCache is set from intent even for cold start
                        if (cleanCache) pendingCleanCache = true

                        if (isStarting) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                            // Return STICKY to allow system to restart VPN if killed due to memory pressure
                            return START_STICKY
                        }
                        if (isStopping) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                            // Return STICKY to allow system to restart VPN if killed due to memory pressure
                            return START_STICKY
                        }
                        // If already running, do a clean restart to avoid half-broken tunnel state
                        if (isRunning) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                        }
                    }
                    if (isRunning) {
                        // 2025-fix: 优先尝试热切换节点，避免重启 VPN 导致连接断开
                        // 只有当需要更改核心配置（如路由规则、DNS 等）时才重启
                        // 目前所有切换都视为可能包含核心变更，但我们可以尝试检测
                        // 暂时保持重启逻辑作为兜底，但在此之前尝试热切换
                        // 注意：如果只是切换节点，并不需要重启 VPN，直接 selectOutbound 即可
                        // 但我们需要一种机制来通知 Service 是在切换节点还是完全重载
                        stopVpn(stopService = false)
                    } else {
                        startVpn(configPath)
                    }
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "Received ACTION_STOP (manual) -> stopping VPN")
                isManuallyStopped = true
                VpnStateStore.setManuallyStopped(applicationContext, true)
                VpnTileService.persistVpnPending(applicationContext, "stopping")
                updateServiceState(ServiceState.STOPPING)
                requestNotificationUpdate(force = true)
                synchronized(this) {
                    pendingStartConfigPath = null
                }
                stopVpn(stopService = true)
            }
            ACTION_SWITCH_NODE -> {
                Log.i(TAG, "Received ACTION_SWITCH_NODE -> switching node")
                // 从 Intent 中获取目标节点 ID，如果未提供则切换下一个
                val targetNodeId = intent.getStringExtra("node_id")
                val outboundTag = intent.getStringExtra("outbound_tag")
                runCatching {
                    LogRepository.getInstance().addLog(
                        "INFO SingBoxService: ACTION_SWITCH_NODE nodeId=${targetNodeId.orEmpty()} outboundTag=${outboundTag.orEmpty()}"
                    )
                }
                // Remember latest config path for fallback restart if hot switch doesn't apply.
                val fallbackConfigPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (!fallbackConfigPath.isNullOrBlank()) {
                    synchronized(this) {
                        pendingHotSwitchFallbackConfigPath = fallbackConfigPath
                    }
                    runCatching {
                        LogRepository.getInstance().addLog("INFO SingBoxService: SWITCH_NODE fallback configPath=$fallbackConfigPath")
                    }
                }
                if (targetNodeId != null) {
                    performHotSwitch(targetNodeId, outboundTag)
                } else {
                    switchNextNode()
                }
            }
            ACTION_UPDATE_SETTING -> {
                val key = intent.getStringExtra(EXTRA_SETTING_KEY)
                if (key == "show_notification_speed") {
                    val value = intent.getBooleanExtra(EXTRA_SETTING_VALUE_BOOL, true)
                    Log.i(TAG, "Received setting update: $key = $value")
                    showNotificationSpeed = value
                    if (isRunning) {
                        requestNotificationUpdate(force = true)
                    }
                }
            }
        }
        // Use START_STICKY to allow system auto-restart if killed due to memory pressure
        // This prevents "VPN mysteriously stops" issue on Android 14+
        // System will restart service with null intent, we handle it gracefully above
        return START_STICKY
    }

    /**
     * 执行热切换：直接调用内核 selectOutbound
     */
    private fun performHotSwitch(nodeId: String, outboundTag: String?) {
        serviceScope.launch {
            val configRepository = ConfigRepository.getInstance(this@SingBoxService)
            val node = configRepository.getNodeById(nodeId)
            
            // 如果提供了 outboundTag，即使 node 找不到也尝试切换
            // 因为 Service 进程中的 configRepository 数据可能滞后于 UI 进程
            val nodeTag = outboundTag ?: node?.name
            
            if (nodeTag == null) {
                Log.w(TAG, "Hot switch failed: node not found $nodeId and no outboundTag provided")
                return@launch
            }

            val success = hotSwitchNode(nodeTag)
            
            if (success) {
                Log.i(TAG, "Hot switch successful for $nodeTag")
                // Ensure notification reflects the newly selected node immediately.
                // writeGroups callback may be delayed or missing on some cores/ROMs.
                val displayName = node?.name ?: nodeTag
                realTimeNodeName = displayName
                runCatching { configRepository.syncActiveNodeFromProxySelection(displayName) }
                requestNotificationUpdate(force = false)
            } else {
                Log.w(TAG, "Hot switch failed for $nodeTag, falling back to restart")
                // Fallback: restart VPN
                val configPath = intentConfigPath()
                val restartIntent = Intent(this@SingBoxService, SingBoxService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_CONFIG_PATH, configPath)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            }
        }
    }

    private fun intentConfigPath(): String {
        return pendingHotSwitchFallbackConfigPath 
            ?: File(filesDir, "running_config.json").absolutePath
    }

    @Volatile private var pendingHotSwitchFallbackConfigPath: String? = null

    private fun switchNextNode() {

        serviceScope.launch {
            val configRepository = ConfigRepository.getInstance(this@SingBoxService)
            val nodes = configRepository.nodes.value
            if (nodes.isEmpty()) return@launch
            
            val activeNodeId = configRepository.activeNodeId.value
            val currentIndex = nodes.indexOfFirst { it.id == activeNodeId }
            val nextIndex = (currentIndex + 1) % nodes.size
            val nextNode = nodes[nextIndex]
            
            val success = configRepository.setActiveNode(nextNode.id)
            if (success) {
                requestNotificationUpdate(force = false)
            }
        }
    }
    
    private fun startVpn(configPath: String) {
        synchronized(this) {
            if (isRunning) {
                Log.w(TAG, "VPN already running, ignore start request")
                return
            }
            if (isStarting) {
                Log.w(TAG, "VPN is already in starting process, ignore start request")
                return
            }
            if (isStopping) {
                Log.w(TAG, "VPN is stopping, queue start request")
                pendingStartConfigPath = configPath
                stopSelfRequested = false
                lastConfigPath = configPath
                // Keep pendingCleanCache as is
                return
            }
            isStarting = true
            realTimeNodeName = null
            vpnLinkValidated = false  // Reset validation flag
        }

        updateServiceState(ServiceState.STARTING)
        setLastError(null)
        
        lastConfigPath = configPath
        Log.d(TAG, "Attempting to start foreground service with ID: $NOTIFICATION_ID")
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires foreground service type
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10-13
                startForeground(NOTIFICATION_ID, notification)
            } else {
                // Android 9-
                startForeground(NOTIFICATION_ID, notification)
            }
            hasForegroundStarted.set(true) // 标记已调用 startForeground()
            Log.d(TAG, "startForeground called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call startForeground", e)
        }
        
        startVpnJob?.cancel()
        startVpnJob = serviceScope.launch {
            try {
                // Critical fix: wait for any pending cleanup to finish before starting new instance
                // This prevents resource conflict (TUN fd, UDP ports) between old and new libbox instances
                val cleanup = cleanupJob
                if (cleanup != null && cleanup.isActive) {
                    Log.i(TAG, "Waiting for previous service cleanup...")
                    cleanup.join()
                    Log.i(TAG, "Previous cleanup finished")
                }

                // Acquire locks
                try {
                    val pm = getSystemService(PowerManager::class.java)
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KunBox:VpnService")
                    wakeLock?.setReferenceCounted(false)
                    wakeLock?.acquire(24 * 60 * 60 * 1000L) // Limit to 24h just in case

                    val wm = getSystemService(WifiManager::class.java)
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KunBox:VpnService")
                    wifiLock?.setReferenceCounted(false)
                    wifiLock?.acquire()
                    Log.i(TAG, "WakeLock and WifiLock acquired")

                    // 注册屏幕状态监听器 - 这是修复 Telegram 切换回来后卡住的关键
                    registerScreenStateReceiver()

                    // 检查电池优化状态,如果未豁免则记录警告日志
                    if (!com.kunk.singbox.utils.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@SingBoxService)) {
                        Log.w(TAG, "⚠️ Battery optimization is enabled - VPN may be killed during screen-off!")
                        LogRepository.getInstance().addLog(
                            "WARNING: 电池优化未关闭,息屏时 VPN 可能被系统杀死。建议在设置中关闭电池优化。"
                        )
                    } else {
                        Log.i(TAG, "✓ Battery optimization exempted - VPN protected during screen-off")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to acquire locks", e)
                }

                val prepareIntent = VpnService.prepare(this@SingBoxService)
                if (prepareIntent != null) {
                    val msg = getString(R.string.profiles_camera_permission_required) // TODO: Better string for VPN permission
                    Log.w(TAG, msg)
                    setLastError(msg)
                    VpnTileService.persistVpnState(applicationContext, false)
                    VpnTileService.persistVpnPending(applicationContext, "")
                    updateServiceState(ServiceState.STOPPED)
                    updateTileState()

                    runCatching {
                        prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(prepareIntent)
                    }.onFailure {
                        runCatching {
                            val manager = getSystemService(NotificationManager::class.java)
                            val pi = PendingIntent.getActivity(
                                this@SingBoxService,
                                2002,
                                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            val notification = Notification.Builder(this@SingBoxService, CHANNEL_ID)
                                .setContentTitle("VPN Permission Required")
                                .setContentText("Tap to grant VPN permission and start")
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentIntent(pi)
                                .setAutoCancel(true)
                                .build()
                            manager.notify(NOTIFICATION_ID + 3, notification)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        try {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } catch (_: Exception) {
                        }
                        stopSelf()
                    }
                    return@launch
                }

                startForeignVpnMonitor()

                // 恢复串行启动，确保网络环境稳定
                // 任务 1: 确保网络回调和物理网络就绪 (超时缩短至 3s，平衡速度与稳定性)
                ensureNetworkCallbackReadyWithTimeout(timeoutMs = 1500L)
                val physicalNetwork = waitForUsablePhysicalNetwork(timeoutMs = 3000L)
                if (physicalNetwork == null) {
                    throw IllegalStateException("No usable physical network (NOT_VPN+INTERNET) before VPN start")
                } else {
                    lastKnownNetwork = physicalNetwork
                    networkCallbackReady = true
                }

                // 任务 2: 确保规则集就绪
                // 如果本地缓存不存在，允许网络下载；如果下载失败也继续启动
                try {
                    val ruleSetRepo = RuleSetRepository.getInstance(this@SingBoxService)
                    val now = System.currentTimeMillis()
                    val shouldForceUpdate = now - lastRuleSetCheckMs >= ruleSetCheckIntervalMs
                    if (shouldForceUpdate) {
                        lastRuleSetCheckMs = now
                    }
                    // Always allow network download if rule sets are missing
                    val allReady = ruleSetRepo.ensureRuleSetsReady(
                        forceUpdate = false,
                        allowNetwork = false
                    ) { progress ->
                        Log.v(TAG, "Rule set update: $progress")
                    }
                    if (!allReady) {
                        Log.w(TAG, "Some rule sets are not ready, proceeding with available cache")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update rule sets", e)
                }

                // 加载最新设置
                currentSettings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
                Log.v(TAG, "Settings loaded: tunEnabled=${currentSettings?.tunEnabled}")

                // 配置日志级别
                val logLevel = if (currentSettings?.debugLoggingEnabled == true) "debug" else "info"

                // 读取配置文件
                val configFile = File(configPath)
                if (!configFile.exists()) {
                    Log.e(TAG, "Config file not found: $configPath")
                    setLastError("Config file not found: $configPath")
                    withContext(Dispatchers.Main) { stopSelf() }
                    return@launch
                }
                var configContent = configFile.readText()
                
                // Patch config runtime options
                try {
                    val configObj = gson.fromJson(configContent, SingBoxConfig::class.java)
                    
                    // Patch log config
                    val logConfig = configObj.log?.copy(level = logLevel) ?: com.kunk.singbox.model.LogConfig(
                        level = logLevel,
                        timestamp = true,
                        output = "box.log"
                    )

                    var newConfig = configObj.copy(log = logConfig)

                    if (newConfig.inbounds != null) {
                        // Using 'orEmpty()' to ensure non-null list for mapping, although check above handles null
                        val newInbounds = newConfig.inbounds.orEmpty().map { inbound ->
                            if (inbound.type == "tun") {
                                // Apply runtime settings to tun inbound
                                inbound.copy(
                                    autoRoute = currentSettings?.autoRoute ?: false
                                )
                            } else {
                                inbound
                            }
                        }
                        newConfig = newConfig.copy(inbounds = newInbounds)
                    }
                    configContent = gson.toJson(newConfig)
                    Log.i(TAG, "Patched config: auto_route=${currentSettings?.autoRoute}, log_level=$logLevel")

                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch config: ${e.message}")
                }

                Log.v(TAG, "Config loaded, length: ${configContent.length}")
                
                try {
                    SingBoxCore.ensureLibboxSetup(this@SingBoxService)
                } catch (e: Exception) {
                    Log.w(TAG, "Libbox setup warning: ${e.message}")
                }
                
                // 如果有清理缓存请求（跨配置切换），在启动前删除 cache.db
                // 这确保 sing-box 启动时使用配置文件中的默认选中项，而不是恢复旧的（可能无效的）状态
                val cleanCache = synchronized(this@SingBoxService) {
                    val c = pendingCleanCache
                    pendingCleanCache = false
                    c
                }
                if (cleanCache) {
                    runCatching {
                        val cacheDir = File(filesDir, "singbox_data")
                        val cacheDb = File(cacheDir, "cache.db")
                        if (cacheDb.exists()) {
                            if (cacheDb.delete()) {
                                Log.i(TAG, "Deleted cache.db on start (clean_cache=true)")
                            } else {
                                Log.w(TAG, "Failed to delete cache.db on start")
                            }
                        }
                    }
                }

                // 创建并启动 BoxService
                boxService = Libbox.newService(configContent, platformInterface)
                boxService?.start()
                Log.i(TAG, "BoxService started")

                // Wait for VPN link validation before resetting network
                // This prevents connection leaks during VPN startup window
                serviceScope.launch {
                    try {
                        // Wait up to 2s for VPN link to be validated (reduced from 3s)
                        var waited = 0L
                        while (!vpnLinkValidated && waited < 2000L) {
                            delay(100)
                            waited += 100
                        }

                        if (vpnLinkValidated) {
                            Log.i(TAG, "VPN link validated, calling resetNetwork() after ${waited}ms")
                        } else {
                            Log.w(TAG, "VPN link validation timeout after ${waited}ms, calling resetNetwork() anyway")
                        }

                        boxService?.resetNetwork()
                        Log.i(TAG, "Initial boxService.resetNetwork() called")

                        // 关键修复:等待 sing-box 核心完全初始化
                        // 延长到 2.5 秒,让应用层等待而非发起连接后失败
                        Log.i(TAG, "Waiting for sing-box core to fully initialize (2.5s)...")
                        delay(2500)
                        Log.i(TAG, "Core initialization wait completed")

                        // === 核心就绪后首次设置底层网络 ===
                        // 新策略: TUN 建立时不设置底层网络,延迟到核心就绪后首次设置
                        // 这样可以避免应用在 VPN 未完全就绪时发起连接
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            try {
                                Log.d(TAG, "Starting to configure underlying network...")
                                val currentNetwork = lastKnownNetwork ?: findBestPhysicalNetwork()
                                if (currentNetwork != null) {
                                    Log.i(TAG, "Setting underlying network for the first time (network=$currentNetwork)")
                                    setUnderlyingNetworks(arrayOf(currentNetwork))
                                    delay(300) // 等待网络设置生效,让系统识别到网络可用
                                    Log.i(TAG, "Underlying network configured successfully")
                                    LogRepository.getInstance().addLog("INFO: VPN 底层网络已配置,开始路由流量")
                                } else {
                                    Log.w(TAG, "No physical network found after core ready")
                                    LogRepository.getInstance().addLog("WARN: 未找到物理网络,VPN 可能无法正常工作")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to set underlying network", e)
                                LogRepository.getInstance().addLog("ERROR: 设置底层网络失败: ${e.message}")
                            }
                        } else {
                            Log.i(TAG, "Skipping underlying network configuration (Android < 5.1)")
                        }

                        // DNS 预热: 预解析常见域名,避免首次查询超时导致用户感知延迟
                        try {
                            Log.d(TAG, "Starting DNS warmup...")
                            warmupDnsCache()
                            Log.d(TAG, "DNS warmup completed")
                        } catch (e: Exception) {
                            Log.w(TAG, "DNS warmup failed", e)
                        }

                // === 关键修复: 使用 libbox resetNetwork() 强制应用重连 ===
                // 解决问题: VPN 启动瞬间建立的应用层连接会永久卡死
                // 原理: 使用 libbox 的 resetNetwork() 代替 reportNetworkConnectivity()
                // BUG修复: reportNetworkConnectivity() 在华为EMUI等系统上会触发持续的系统提示音
                // 参考: Android VPN 最佳实践,成熟 VPN 项目不使用 reportNetworkConnectivity
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Log.d(TAG, "Starting network connectivity notification...")

                        // 仅使用 libbox resetNetwork() 方法强制应用重连
                        // 避免使用 reportNetworkConnectivity() 触发系统提示音
                        try {
                            boxService?.resetNetwork()
                            Log.i(TAG, "Network reset triggered via libbox, apps should reconnect now")
                            LogRepository.getInstance().addLog("INFO: 已通知应用网络已切换,强制重新建立连接")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to reset network via libbox", e)
                            LogRepository.getInstance().addLog("WARN: 触发应用重连失败: ${e.message}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to trigger connectivity change notification", e)
                    }
                }

                        Log.i(TAG, "Sing-box core initialization complete, VPN is now fully ready")
                        LogRepository.getInstance().addLog("INFO: VPN 核心已完全就绪,网络连接可用")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to call initial resetNetwork", e)
                    }
                }

                tryRegisterRunningServiceForLibbox()

                // 启动 CommandServer 和 CommandClient 以监听实时节点变化
                try {
                    startCommandServerAndClient()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Command Server/Client", e)
                }

                // 重置流量计数器
                lastUplinkTotal = 0
                lastDownlinkTotal = 0

                // 处理排队的热切换请求
                val pendingHotSwitch = synchronized(this@SingBoxService) {
                    val p = pendingHotSwitchNodeId
                    pendingHotSwitchNodeId = null
                    p
                }
                if (pendingHotSwitch != null) {
                    // 这里我们只有 nodeId，需要转换为 tag。
                    // 但 Service 刚启动，应该使用配置文件中的默认值，所以这里可能不需要做额外操作
                    // 除非 pendingHotSwitch 是在启动后立即设置的
                    Log.i(TAG, "Pending hot switch processed (implicitly by config): $pendingHotSwitch")
                }
                
                isRunning = true
                stopForeignVpnMonitor()
                setLastError(null)
                Log.i(TAG, "KunBox VPN started successfully")

                // 立即重置 isStarting 标志,确保UI能正确显示已连接状态
                isStarting = false

                VpnTileService.persistVpnState(applicationContext, true)
                VpnStateStore.setMode(applicationContext, VpnStateStore.CoreMode.VPN)

                // 启动 TrafficStats 速度监控 (在状态持久化之后)
                startTrafficStatsMonitor()
                VpnTileService.persistVpnPending(applicationContext, "")
                updateServiceState(ServiceState.RUNNING)
                updateTileState()

                startRouteGroupAutoSelect(configContent)

                // 启动周期性健康检查,防止 boxService native 崩溃导致僵尸状态
                startPeriodicHealthCheck()
                Log.i(TAG, "Periodic health check started")

                // 调度 WorkManager 保活任务,防止息屏时进程被系统杀死
                VpnKeepaliveWorker.schedule(applicationContext)
                Log.i(TAG, "VPN keepalive worker scheduled")

            } catch (e: CancellationException) {
                Log.i(TAG, "startVpn cancelled")
                // Do not treat cancellation as failure. stopVpn() is already responsible for cleanup.
                return@launch
            } catch (e: Exception) {
                var reason = "Failed to start VPN: ${e.javaClass.simpleName}: ${e.message}"

                val msg = e.message.orEmpty()
                val isTunEstablishFail = msg.contains("VPN interface establish failed", ignoreCase = true) ||
                    msg.contains("configure tun interface", ignoreCase = true) ||
                    msg.contains("fd=-1", ignoreCase = true)

                val isLockdown = msg.contains("VPN lockdown enabled by", ignoreCase = true)

                // 更友好的错误提示
                if (isLockdown) {
                    val lockedBy = msg.substringAfter("VPN lockdown enabled by ").trim().ifBlank { "unknown" }
                    reason = "Start failed: system lockdown VPN enabled ($lockedBy). Please disable it in system settings."
                    isManuallyStopped = true
                } else if (isTunEstablishFail) {
                    reason = "Start failed: could not establish VPN interface (fd=-1). Please check system VPN settings."
                    isManuallyStopped = true
                } else if (e is NullPointerException && e.message?.contains("establish") == true) {
                    reason = "Start failed: system refused to create VPN interface. Check permissions or conflicts."
                    isManuallyStopped = true
                }

                Log.e(TAG, reason, e)
                setLastError(reason)
                VpnTileService.persistVpnPending(applicationContext, "")

                if (isLockdown || isTunEstablishFail) {
                    runCatching {
                        val manager = getSystemService(NotificationManager::class.java)
                        val intent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val pi = PendingIntent.getActivity(
                            this@SingBoxService,
                            2001,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val notification = Notification.Builder(this@SingBoxService, CHANNEL_ID)
                            .setContentTitle("VPN Start Failed")
                            .setContentText("May be blocked by other VPN settings. Tap to open system settings.")
                            .setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .build()
                        manager.notify(NOTIFICATION_ID + 2, notification)
                    }
                }
                withContext(Dispatchers.Main) {
                    isRunning = false
                    updateServiceState(ServiceState.STOPPED)
                    stopVpn(stopService = true)
                }
                // 启动失败后，尝试重试一次（如果是自动重连触发的，可能因为网络刚切换还不稳定）
                if (lastConfigPath != null && !isManuallyStopped) {
                    Log.i(TAG, "Retrying start VPN in 2 seconds...")
                    delay(2000)
                    if (!isRunning && !isManuallyStopped) {
                        startVpn(lastConfigPath!!)
                    }
                }
            } finally {
                isStarting = false
                startVpnJob = null
                // Ensure tile state is refreshed after start attempt finishes
                updateTileState()
                runCatching {
                    val intent = Intent(VpnTileService.ACTION_REFRESH_TILE).apply {
                        `package` = packageName
                    }
                    sendBroadcast(intent)
                }
            }
        }
    }

    private fun isAnyVpnActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = try {
            getSystemService(ConnectivityManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return false

        return runCatching {
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(false)
    }

    private fun stopVpn(stopService: Boolean) {
        synchronized(this) {
            stopSelfRequested = stopSelfRequested || stopService
            if (isStopping) {
                return
            }
            isStopping = true
        }
        updateServiceState(ServiceState.STOPPING)
                requestNotificationUpdate(force = true)
        updateTileState() // Force tile update immediately upon stopping
        runCatching {
            val intent = Intent(VpnTileService.ACTION_REFRESH_TILE).apply {
                `package` = packageName
            }
            sendBroadcast(intent)
        }
        stopForeignVpnMonitor()

        val jobToJoin = startVpnJob
        startVpnJob = null
        jobToJoin?.cancel()

        vpnHealthJob?.cancel()
        vpnHealthJob = null

        periodicHealthCheckJob?.cancel()
        periodicHealthCheckJob = null
        consecutiveHealthCheckFailures = 0

        // 取消 WorkManager 保活任务
        VpnKeepaliveWorker.cancel(applicationContext)
        Log.i(TAG, "VPN keepalive worker cancelled")

        coreNetworkResetJob?.cancel()
        coreNetworkResetJob = null

        notificationUpdateJob?.cancel()
        notificationUpdateJob = null

        // 重置前台服务标志,以便下次启动时重新调用 startForeground()
        hasForegroundStarted.set(false)

        remoteStateUpdateJob?.cancel()
        remoteStateUpdateJob = null
        // nodePollingJob?.cancel()
        // nodePollingJob = null

        routeGroupAutoSelectJob?.cancel()
        routeGroupAutoSelectJob = null

        if (stopService) {
            networkCallbackReady = false
            vpnLinkValidated = false
            lastKnownNetwork = null
            noPhysicalNetworkWarningLogged = false
            defaultInterfaceName = ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                runCatching { setUnderlyingNetworks(null) }
            }
        } else {
            noPhysicalNetworkWarningLogged = false
        }

        tryClearRunningServiceForLibbox()

        Log.i(TAG, "stopVpn(stopService=$stopService) isManuallyStopped=$isManuallyStopped")

        autoReconnectJob?.cancel()
        autoReconnectJob = null
        postTunRebindJob?.cancel()
        postTunRebindJob = null

        realTimeNodeName = null
        isRunning = false

        val listener = currentInterfaceListener
        val serviceToClose = boxService
        boxService = null

        val interfaceToClose = vpnInterface
        vpnInterface = null

        // Release locks
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
            Log.i(TAG, "WakeLock and WifiLock released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release locks", e)
        }

        // 注销屏幕状态监听器
        unregisterScreenStateReceiver()

        // Stop command client/server
        try {
            commandClient?.disconnect()
            commandClient = null
            commandClientLogs?.disconnect()
            commandClientLogs = null
            commandClientConnections?.disconnect()
            commandClientConnections = null
            commandServer?.close()
            commandServer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing command server/client", e)
        }

        cleanupJob = cleanupScope.launch(NonCancellable) {
            try {
                // Wait for start job to finish
                jobToJoin?.join()
            } catch (_: Exception) {}

            try {
                platformInterface.closeDefaultInterfaceMonitor(listener)
            } catch (_: Exception) {}

            try {
                // Attempt graceful close first
                withTimeout(2000L) {
                    try { serviceToClose?.close() } catch (_: Exception) {}
                    try { interfaceToClose?.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Graceful close failed or timed out", e)
            }

            withContext(Dispatchers.Main) {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping foreground", e)
                }
                if (stopSelfRequested) {
                    stopSelf()
                }
                Log.i(TAG, "VPN stopped")
                VpnTileService.persistVpnState(applicationContext, false)
                VpnStateStore.setMode(applicationContext, VpnStateStore.CoreMode.NONE)
                VpnTileService.persistVpnPending(applicationContext, "")
                updateServiceState(ServiceState.STOPPED)
                updateTileState()
            }

            val startAfterStop = synchronized(this@SingBoxService) {
                isStopping = false
                val pending = pendingStartConfigPath
                pendingStartConfigPath = null
                val shouldStart = !pending.isNullOrBlank()
                stopSelfRequested = false
                pending?.takeIf { shouldStart }
            }

            if (!startAfterStop.isNullOrBlank()) {
                waitForSystemVpnDown(timeoutMs = 1500L)
                withContext(Dispatchers.Main) {
                    startVpn(startAfterStop)
                }
            }
        }
    }

    private suspend fun waitForSystemVpnDown(timeoutMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cm = try {
            getSystemService(ConnectivityManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return

        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val hasVpn = runCatching {
                cm.allNetworks.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }.getOrDefault(false)

            if (!hasVpn) return
            delay(50)
        }
    }

    private fun updateTileState() {
        try {
            TileService.requestListeningState(this, ComponentName(this, VpnTileService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tile state", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // Cleanup old channel
            try {
                manager.deleteNotificationChannel("singbox_vpn")
            } catch (_: Exception) {}

            Log.d(TAG, "Creating notification channel: $CHANNEL_ID")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KunBox VPN",
                NotificationManager.IMPORTANCE_LOW // 静音通知
            ).apply {
                description = "VPN Service Notification"
                setShowBadge(false) // 不显示角标
                enableVibration(false) // 禁用振动
                enableLights(false) // 禁用指示灯
                setSound(null, null) // 显式禁用声音
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC // 锁屏可见
            }
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created with IMPORTANCE_LOW")
        }
    }
    
    private fun updateNotification() {
        val notification = createNotification()

        val text = runCatching {
            notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }.getOrNull()
        if (!text.isNullOrBlank() && text != lastNotificationTextLogged) {
            lastNotificationTextLogged = text
            Log.i(TAG, "Notification content: $text")
        }

        // BUG修复(华为设备): 避免频繁调用 startForeground() 触发系统提示音
        // 原因: 华为EMUI等系统在每次 startForeground() 调用时可能播放提示音
        // 解决: 只在首次启动时调用 startForeground(),后续使用 NotificationManager.notify() 更新
        val manager = getSystemService(NotificationManager::class.java)
        if (!hasForegroundStarted.get()) {
            // 首次启动,尝试调用 startForeground
            runCatching {
                startForeground(NOTIFICATION_ID, notification)
                hasForegroundStarted.set(true)
                Log.d(TAG, "First foreground notification set via startForeground()")
            }.onFailure { e ->
                Log.w(TAG, "Failed to call startForeground, fallback to notify()", e)
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            // 后续更新,只使用 notify() 避免触发提示音
            runCatching {
                manager.notify(NOTIFICATION_ID, notification)
            }.onFailure { e ->
                Log.w(TAG, "Failed to update notification via notify()", e)
            }
        }
    }

    private fun requestNotificationUpdate(force: Boolean = false) {
        if (isStopping) return // Prevent updates during shutdown to avoid flickering
        val now = SystemClock.elapsedRealtime()
        val last = lastNotificationUpdateAtMs.get()

        if (force) {
            lastNotificationUpdateAtMs.set(now)
            notificationUpdateJob?.cancel()
            notificationUpdateJob = null
            updateNotification()
            return
        }

        val delayMs = (notificationUpdateDebounceMs - (now - last)).coerceAtLeast(0L)
        if (delayMs <= 0L) {
            lastNotificationUpdateAtMs.set(now)
            notificationUpdateJob?.cancel()
            notificationUpdateJob = null
            updateNotification()
            return
        }

        if (notificationUpdateJob?.isActive == true) return
        notificationUpdateJob = serviceScope.launch {
            delay(delayMs)
            lastNotificationUpdateAtMs.set(SystemClock.elapsedRealtime())
            updateNotification()
        }
    }

    private fun createNotification(): Notification {
        if (serviceState == ServiceState.STOPPING) {
             return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }.apply {
                setContentTitle("KunBox VPN")
                setContentText(getString(R.string.connection_disconnecting))
                setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                setOngoing(true)
            }.build()
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val switchIntent = Intent(this, SingBoxService::class.java).apply {
            action = ACTION_SWITCH_NODE
        }
        val switchPendingIntent = PendingIntent.getService(
            this, 1, switchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, SingBoxService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val configRepository = ConfigRepository.getInstance(this)
        val activeNodeId = configRepository.activeNodeId.value
        // 优先显示活跃连接的节点，其次显示代理组选中的节点，最后显示配置选中的节点
        val activeNodeName = realTimeNodeName
            ?: configRepository.nodes.value.find { it.id == activeNodeId }?.name
            ?: getString(R.string.connection_connected)

        // 构建通知内容文本
        val contentText = if (showNotificationSpeed) {
            val uploadSpeedStr = formatSpeed(currentUploadSpeed)
            val downloadSpeedStr = formatSpeed(currentDownloadSpeed)
            getString(R.string.notification_speed_format, uploadSpeedStr, downloadSpeedStr)
        } else {
            getString(R.string.connection_connected)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle("KunBox VPN - $activeNodeName")
            setContentText(contentText)
            setSmallIcon(android.R.drawable.ic_lock_lock)
            setContentIntent(pendingIntent)
            setOngoing(true)
            
            // 添加切换节点按钮
            addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_revert,
                    getString(R.string.notification_switch_node),
                    switchPendingIntent
                ).build()
            )
            
            // 添加断开按钮
            addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.notification_disconnect),
                    stopPendingIntent
                ).build()
            )
        }.build()
    }
    
    /**
     * 格式化速度显示
     * @param bytesPerSecond 每秒字节数
     * @return 格式化后的速度字符串，如 "1.5 MB/s"
     */
    private fun formatSpeed(bytesPerSecond: Long): String {
        return android.text.format.Formatter.formatFileSize(this, bytesPerSecond) + "/s"
    }
    
    override fun onDestroy() {
        Log.i(TAG, "onDestroy called -> stopVpn(stopService=false) pid=${android.os.Process.myPid()}")
        TrafficRepository.getInstance(this).saveStats()
        
        // Ensure critical state is saved synchronously before we potentially halt
        if (!isManuallyStopped) {
             // If we are being destroyed but not manually stopped (e.g. app update or system kill),
             // ensure we don't accidentally mark it as manually stopped, but we DO mark VPN as inactive.
             VpnTileService.persistVpnState(applicationContext, false)
             VpnStateStore.setMode(applicationContext, VpnStateStore.CoreMode.NONE)
             Log.i(TAG, "onDestroy: Persisted vpn_active=false, mode=NONE")
        }

        val shouldStop = runCatching {
            synchronized(this@SingBoxService) {
                isRunning || isStopping || boxService != null || vpnInterface != null
            }
        }.getOrDefault(false)

        if (shouldStop) {
            // Note: stopVpn launches a cleanup job on cleanupScope.
            // If we halt() immediately, that job will die.
            // For app updates, the system kills us anyway, so cleanup might be best-effort.
            stopVpn(stopService = false)
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            VpnTileService.persistVpnState(applicationContext, false)
            updateServiceState(ServiceState.STOPPED)
            updateTileState()
        }
        
        serviceSupervisorJob.cancel()
        // cleanupSupervisorJob.cancel() // Allow cleanup to finish naturally
        
        if (instance == this) {
            instance = null
        }
        super.onDestroy()

        // Kill process to fully reset Go runtime state and prevent zombie states.
        // This ensures clean restart if system decides to recreate the service.
        Log.i(TAG, "SingBoxService destroyed. Halting process ${android.os.Process.myPid()}.")

        // Give a tiny breath for logs to flush
        try { Thread.sleep(50) } catch (_: Exception) {}

        Runtime.getRuntime().halt(0)
    }
     
    override fun onRevoke() {
        Log.i(TAG, "onRevoke called -> stopVpn(stopService=true)")
        isManuallyStopped = true
        // Another VPN took over. Persist OFF state immediately so QS tile won't stay active.
        VpnTileService.persistVpnState(applicationContext, false)
        VpnTileService.persistVpnPending(applicationContext, "")
        setLastError("VPN revoked by system (another VPN may have started)")
        updateServiceState(ServiceState.STOPPED)
        updateTileState()
        
        // 记录日志，告知用户原因
        com.kunk.singbox.repository.LogRepository.getInstance()
            .addLog("WARN: VPN permission revoked by system (possibly another VPN app started)")
            
        // 发送通知提醒用户
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN Disconnected")
                .setContentText("VPN permission revoked, possibly by another VPN app.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID + 1, notification)
        }
        
        // 停止服务
        stopVpn(stopService = true)
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If the user swiped away the app, we might want to keep the VPN running 
        // as a foreground service, but some users expect it to stop.
        // Usually, a foreground service continues running.
        // However, if we want to ensure no "zombie" states, we can at least log or check health.
        Log.d(TAG, "onTaskRemoved called")
    }

    private fun startForeignVpnMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (foreignVpnMonitorCallback != null) return
        val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        if (cm == null) return

        preExistingVpnNetworks = runCatching {
            cm.allNetworks.filter { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }.toSet()
        }.getOrDefault(emptySet())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                if (preExistingVpnNetworks.contains(network)) return
                if (isConnectingTun.get()) return
                
                // Do not abort startup if foreign VPN is detected.
                // Android system handles VPN mutual exclusion automatically (revoke).
                // Self-aborting here causes issues during restart when old TUN interface
                // might still be fading out or ghosting.
                if (isStarting && !isRunning) {
                    Log.w(TAG, "Foreign VPN detected during startup, ignoring to prevent self-kill race condition: $network")
                }
            }
        }

        foreignVpnMonitorCallback = callback
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "Failed to register foreign VPN monitor", it) }
    }

    private fun stopForeignVpnMonitor() {
        val cm = connectivityManager ?: return
        foreignVpnMonitorCallback?.let { callback ->
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        foreignVpnMonitorCallback = null
        preExistingVpnNetworks = emptySet()
    }

    private fun isNumericAddress(address: String): Boolean {
        if (address.isBlank()) return false
        // IPv4 regex
        val ipv4Pattern = "^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}$"
        if (address.matches(Regex(ipv4Pattern))) return true
        
        // IPv6 simple check: contains colon and no path separators
        if (address.contains(":") && !address.contains("/")) {
            return try {
                // If it can be parsed as an InetAddress and it's not a hostname (doesn't require lookup)
                // In Android, InetAddress.getByName(numeric) is fast.
                val inetAddress = InetAddress.getByName(address)
                inetAddress.hostAddress == address || address.contains("[")
            } catch (_: Exception) {
                false
            }
        }
        return false
    }
    
    /**
     * 确保网络回调就绪，最多等待指定超时时间
     * 如果超时仍未就绪，尝试主动采样当前活跃网络
     */
    private suspend fun ensureNetworkCallbackReadyWithTimeout(timeoutMs: Long = 2000L) {
        if (networkCallbackReady && lastKnownNetwork != null) {
            Log.v(TAG, "Network callback already ready, lastKnownNetwork=$lastKnownNetwork")
            return
        }
        
        // 先尝试主动采样
        val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        
        val activeNet = cm?.activeNetwork
        if (activeNet != null) {
            val caps = cm.getNetworkCapabilities(activeNet)
            val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val notVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
            
            if (!isVpn && hasInternet && notVpn) {
                lastKnownNetwork = activeNet
                networkCallbackReady = true
                Log.i(TAG, "Pre-sampled physical network: $activeNet")
                return
            }
        }
        
        // 如果主动采样失败，等待回调就绪（带超时）
        val startTime = System.currentTimeMillis()
        while (!networkCallbackReady && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(100)
        }
        
        if (networkCallbackReady) {
            Log.i(TAG, "Network callback ready after waiting, lastKnownNetwork=$lastKnownNetwork")
        } else {
            // 超时后再次尝试查找最佳物理网络
            val bestNetwork = findBestPhysicalNetwork()
            if (bestNetwork != null) {
                lastKnownNetwork = bestNetwork
                networkCallbackReady = true
                Log.i(TAG, "Found physical network after timeout: $bestNetwork")
            } else {
                Log.w(TAG, "Network callback not ready after ${timeoutMs}ms timeout, proceeding without guaranteed physical network")
                com.kunk.singbox.repository.LogRepository.getInstance()
                    .addLog("WARN startVpn: No physical network found after ${timeoutMs}ms - VPN may not work correctly")
            }
        }
    }

    private suspend fun waitForUsablePhysicalNetwork(timeoutMs: Long): Network? {
        val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java).also {
            connectivityManager = it
        } ?: return null

        val start = SystemClock.elapsedRealtime()
        var best: Network? = null
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val candidate = findBestPhysicalNetwork()
            if (candidate != null) {
                val caps = cm.getNetworkCapabilities(candidate)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val notVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
                if (hasInternet && notVpn) {
                    best = candidate
                    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    if (validated) return candidate
                }
            }
            delay(100)
        }
        return best
    }

    /**
     * 执行连通性检查,确保 VPN 隧道真正可用
     * 参考 Clash/NekoBox 的实现: ping 公共 DNS 服务器验证网络连通性
     * @return true 表示连通性检查通过,false 表示失败
     */
    private suspend fun performConnectivityCheck(): Boolean = withContext(Dispatchers.IO) {
        val testTargets = listOf(
            "1.1.1.1" to 53,      // Cloudflare DNS
            "8.8.8.8" to 53,      // Google DNS
            "223.5.5.5" to 53     // Ali DNS (国内备用)
        )

        Log.i(TAG, "Starting connectivity check...")

        for ((host, port) in testTargets) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2000) // 2秒超时
                socket.close()
                Log.i(TAG, "Connectivity check passed: $host:$port reachable")
                LogRepository.getInstance().addLog("INFO: VPN 连通性检查通过 ($host:$port)")
                return@withContext true
            } catch (e: Exception) {
                Log.d(TAG, "Connectivity check failed for $host:$port - ${e.message}")
                // 继续尝试下一个目标
            }
        }

        Log.w(TAG, "Connectivity check failed: all test targets unreachable")
        LogRepository.getInstance().addLog("WARN: VPN 连通性检查失败,所有测试目标均不可达")
        return@withContext false
    }

    private fun startCommandServerAndClient() {
        if (boxService == null) return

        // CommandServer Handler
        val serverHandler = object : CommandServerHandler {
            override fun serviceReload() {
                // No-op for now, or implement reload logic if needed
            }
            override fun postServiceClose() {}
            override fun getSystemProxyStatus(): SystemProxyStatus? = null
            override fun setSystemProxyEnabled(isEnabled: Boolean) {
                // No-op
            }
        }

        // CommandClient Handler
        val clientHandler = object : CommandClientHandler {
            override fun connected() {
                Log.d(TAG, "CommandClient connected")
            }

            override fun disconnected(message: String?) {
                Log.w(TAG, "CommandClient disconnected: $message")
            }

            override fun clearLogs() {
                runCatching {
                    com.kunk.singbox.repository.LogRepository.getInstance().clearLogs()
                }
            }

            override fun writeLogs(messageList: StringIterator?) {
                if (messageList == null) return
                val repo = com.kunk.singbox.repository.LogRepository.getInstance()
                runCatching {
                    while (messageList.hasNext()) {
                        val msg = messageList.next()
                        if (!msg.isNullOrBlank()) {
                            repo.addLog(msg)
                        }
                    }
                }
            }
            override fun writeStatus(message: StatusMessage?) {
                // 速度计算已改为使用 TrafficStats API（在 startTrafficStatsMonitor 中实现）
                // writeStatus 仍然保留用于其他状态信息，但不再用于速度计算
                // 因为 libbox 的 uplinkTotal/downlinkTotal 在某些配置下可能返回0
                message ?: return
                
                // 仅用于 libbox 内部流量统计（如 Clash API 等），但我们主要依赖 TrafficStats
                val currentUp = message.uplinkTotal
                val currentDown = message.downlinkTotal
                val currentTime = System.currentTimeMillis()
                
                // 首次回调或时间倒流时重置
                if (lastSpeedUpdateTime == 0L || currentTime < lastSpeedUpdateTime) {
                    lastSpeedUpdateTime = currentTime
                    lastUplinkTotal = currentUp
                    lastDownlinkTotal = currentDown
                    return
                }

                // 如果 libbox 重启导致计数归零，重置上次计数
                if (currentUp < lastUplinkTotal || currentDown < lastDownlinkTotal) {
                    lastUplinkTotal = currentUp
                    lastDownlinkTotal = currentDown
                    lastSpeedUpdateTime = currentTime
                    return
                }
                
                // 计算增量用于流量归属统计（不用于速度显示）
                val diffUp = currentUp - lastUplinkTotal
                val diffDown = currentDown - lastDownlinkTotal
                
                if (diffUp > 0 || diffDown > 0) {
                    // 归属到当前活跃节点（用于节点流量统计功能）
                    val repo = ConfigRepository.getInstance(this@SingBoxService)
                    val activeNodeId = repo.activeNodeId.value
                    
                    if (activeNodeId != null) {
                        TrafficRepository.getInstance(this@SingBoxService).addTraffic(activeNodeId, diffUp, diffDown)
                    }
                }
                
                lastUplinkTotal = currentUp
                lastDownlinkTotal = currentDown
                lastSpeedUpdateTime = currentTime
            }

            override fun writeGroups(groups: OutboundGroupIterator?) {
                if (groups == null) return
                val configRepo = ConfigRepository.getInstance(this@SingBoxService)
                
                try {
                    var changed = false
                    while (groups.hasNext()) {
                        val group = groups.next()

                        val tag = group.tag
                        val selected = group.selected

                        if (!tag.isNullOrBlank() && !selected.isNullOrBlank()) {
                            val prev = groupSelectedOutbounds.put(tag, selected)
                            if (prev != selected) changed = true
                        }

                        // 兼容旧逻辑：保留对 PROXY 组的同步（用于 UI 选中状态）
                        if (tag.equals("PROXY", ignoreCase = true)) {
                            if (!selected.isNullOrBlank() && selected != realTimeNodeName) {
                                realTimeNodeName = selected
                                Log.i(TAG, "Real-time node update: $selected")
                                serviceScope.launch {
                                    configRepo.syncActiveNodeFromProxySelection(selected)
                                }
                                changed = true
                            }
                        }
                    }

                    if (changed) {
                        requestNotificationUpdate(force = false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing groups update", e)
                }
            }

            override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
            override fun updateClashMode(newMode: String?) {}
            override fun writeConnections(message: Connections?) {
                message ?: return
                try {
                    val iterator = message.iterator()
                    var newestConnection: Connection? = null
                    val ids = ArrayList<String>(64)
                    val egressCounts = LinkedHashMap<String, Int>()

                    val repo = ConfigRepository.getInstance(this@SingBoxService)
                    
                    while (iterator.hasNext()) {
                        val conn = iterator.next()
                        if (conn.closedAt > 0) continue // 忽略已关闭连接
                        
                        // 忽略 DNS 连接 (通常 rule 是 dns-out)
                        if (conn.rule == "dns-out") continue
                        
                        // 找到最新的活跃连接
                        if (newestConnection == null || conn.createdAt > newestConnection.createdAt) {
                            newestConnection = conn
                        }

                        val id = conn.id
                        if (!id.isNullOrBlank()) {
                            ids.add(id)
                        }

                        // 汇总所有活跃连接的 egress：优先使用 conn.rule (通常就是最终 outbound tag)
                        // 仅在 rule 无法识别时才回退使用 chain
                        var candidateTag: String? = conn.rule
                        if (candidateTag.isNullOrBlank() || candidateTag == "dns-out") {
                            candidateTag = null
                        }

                        var resolved: String? = null
                        if (!candidateTag.isNullOrBlank()) {
                            resolved = resolveEgressNodeName(repo, candidateTag)
                                ?: repo.resolveNodeNameFromOutboundTag(candidateTag)
                                ?: candidateTag
                        }

                        if (resolved.isNullOrBlank()) {
                            var lastTag: String? = null
                            runCatching {
                                val chainIter = conn.chain()
                                while (chainIter.hasNext()) {
                                    val tag = chainIter.next()
                                    if (!tag.isNullOrBlank() && tag != "dns-out") {
                                        lastTag = tag
                                    }
                                }
                            }
                            resolved = resolveEgressNodeName(repo, lastTag)
                                ?: repo.resolveNodeNameFromOutboundTag(lastTag)
                                ?: lastTag
                        }

                        if (!resolved.isNullOrBlank()) {
                            egressCounts[resolved] = (egressCounts[resolved] ?: 0) + 1
                        }
                    }

                    recentConnectionIds = ids

                    // 生成一个短标签：单节点直接显示；多节点显示“混合: A + B(+N)”
                    val newLabel: String? = when {
                        egressCounts.isEmpty() -> null
                        egressCounts.size == 1 -> egressCounts.keys.first()
                        else -> {
                            val sorted = egressCounts.entries
                                .sortedByDescending { it.value }
                                .map { it.key }
                            val top = sorted.take(2)
                            val more = sorted.size - top.size
                            if (more > 0) {
                                "Mixed: ${top.joinToString(" + ")}(+$more)"
                            } else {
                                "Mixed: ${top.joinToString(" + ")}"
                            }
                        }
                    }
                    val prevLabel = activeConnectionLabel
                    val labelChanged = newLabel != prevLabel
                    if (labelChanged) {
                        activeConnectionLabel = newLabel

                        // 低噪音日志：只在标签变化时记录一次
                        if (newLabel != lastConnectionsLabelLogged) {
                            lastConnectionsLabelLogged = newLabel
                            Log.i(TAG, "Connections label updated: ${newLabel ?: "(null)"} (active=${egressCounts.size})")
                        }
                    }
                    
                    var newNode: String? = null
                    if (newestConnection != null) {
                        val chainIter = newestConnection.chain()
                        // 遍历 chain 找到最后一个节点
                        while (chainIter.hasNext()) {
                            val tag = chainIter.next()
                            // chain 里可能包含 dns-out 等占位，过滤掉；selector tag 保留，后续再通过 groups 解析到真实节点
                            if (!tag.isNullOrBlank() && tag != "dns-out") newNode = tag
                        }
                        // 如果 chain 为空或者最后一个节点是 selector 名字，可能需要处理
                        // 但通常 chain 的最后一个就是落地节点
                    }
                    
                    // 只有当检测到新的活跃连接节点，或者活跃连接消失（变为null）时才更新
                    // 为了避免闪烁，如果 newNode 为 null，我们保留 activeConnectionNode 一段时间？
                    // 不，直接更新，fallback 逻辑由 createNotification 处理 (回退到 realTimeNodeName)
                    if (newNode != activeConnectionNode || labelChanged) {
                        activeConnectionNode = newNode
                        if (newNode != null) {
                            Log.v(TAG, "Active connection node: $newNode")
                        }
                        requestNotificationUpdate(force = false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing connections update", e)
                }
            }
        }

        // 1. Create and start CommandServer
        commandServer = Libbox.newCommandServer(serverHandler, 300)
        commandServer?.setService(boxService)
        commandServer?.start()
        Log.i(TAG, "CommandServer started")

        // 1. Create and connect CommandClient (Groups + Status)
        val options = CommandClientOptions()
        // CommandGroup | CommandStatus
        options.command = Libbox.CommandGroup or Libbox.CommandStatus
        options.statusInterval = 3000L * 1000L * 1000L // 3s (unit: ns)
        // libbox code: ticker := time.NewTicker(time.Duration(interval))
        // Go's time.Duration is nanoseconds.
        // But let's check how it's passed. Java/Kotlin long -> Go int64.
        // Usually Go bind maps basic types directly.
        // Wait, command_client.go:87 binary.Write(conn, ..., c.options.StatusInterval)
        // So yes, it is nanoseconds. 3s = 3_000_000_000 ns.
        
        commandClient = Libbox.newCommandClient(clientHandler, options)
        commandClient?.connect()
        Log.i(TAG, "CommandClient connected")

        // 2. Create and connect CommandClient for Logs (running logs)
        val optionsLog = CommandClientOptions()
        optionsLog.command = Libbox.CommandLog
        optionsLog.statusInterval = 1500L * 1000L * 1000L // 1.5s (unit: ns)
        commandClientLogs = Libbox.newCommandClient(clientHandler, optionsLog)
        commandClientLogs?.connect()
        Log.i(TAG, "CommandClient (Logs) connected")

        // 3. Create and connect CommandClient for Connections (to show real-time routing)
        val optionsConn = CommandClientOptions()
        optionsConn.command = Libbox.CommandConnections // 14
        optionsConn.statusInterval = 5000L * 1000L * 1000L // 5s
        
        commandClientConnections = Libbox.newCommandClient(clientHandler, optionsConn)
        commandClientConnections?.connect()
        Log.i(TAG, "CommandClient (Connections) connected")

        serviceScope.launch {
            delay(3500)
            val groupsSize = groupSelectedOutbounds.size
            val label = activeConnectionLabel
            if (groupsSize == 0 && label.isNullOrBlank()) {
                Log.w(TAG, "Command callbacks not observed yet (groups=0, label=null). If status bar never changes, check whether CommandConnections is supported by this libbox build.")
            } else {
                Log.i(TAG, "Command callbacks OK (groups=$groupsSize, label=${label ?: "(null)"})")
            }
        }
    }

    /**
     * 如果 openTun 时未找到物理网络，短时间内快速重试绑定，避免等待 5s 健康检查
     */
    private fun schedulePostTunRebind(reason: String) {
        if (postTunRebindJob?.isActive == true) return
        
        postTunRebindJob = serviceScope.launch rebind@{
            // 加大重试密度和时长，应对 Android 16 可能较慢的网络就绪
            val delays = listOf(200L, 500L, 1000L, 2000L, 3000L)
            for (d in delays) {
                delay(d)
                if (isStopping) return@rebind
                
                val bestNetwork = findBestPhysicalNetwork()
                if (bestNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    try {
                        setUnderlyingNetworks(arrayOf(bestNetwork))
                        lastKnownNetwork = bestNetwork
                        noPhysicalNetworkWarningLogged = false
                        Log.i(TAG, "Post-TUN rebind success ($reason): $bestNetwork")
                        com.kunk.singbox.repository.LogRepository.getInstance()
                            .addLog("INFO postTunRebind: $bestNetwork (reason=$reason)")
                        
                        // 强制立即重置，不要防抖
                        requestCoreNetworkReset(reason = "postTunRebind:$reason", force = true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Post-TUN rebind failed ($reason): ${e.message}")
                    }
                    return@rebind
                }
            }
            Log.w(TAG, "Post-TUN rebind failed after retries ($reason)")
        }
    }

    private fun startTrafficStatsMonitor() {
        stopTrafficStatsMonitor()
        
        // 重置平滑缓存
        currentUploadSpeed = 0
        currentDownloadSpeed = 0
        lastSpeedUpdateTime = 0
        
        // 获取当前 TrafficStats 基准值
        val uid = Process.myUid()
        val tx0 = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
        val rx0 = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
        
        trafficStatsBaseTx = tx0
        trafficStatsBaseRx = rx0
        trafficStatsLastTx = tx0
        trafficStatsLastRx = rx0
        trafficStatsLastSampleTime = SystemClock.elapsedRealtime()
        
        // 启动定时采样任务
        trafficStatsMonitorJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                
                val nowElapsed = SystemClock.elapsedRealtime()
                val tx = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
                val rx = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
                
                val dtMs = (nowElapsed - trafficStatsLastSampleTime).coerceAtLeast(1L)
                val dTx = (tx - trafficStatsLastTx).coerceAtLeast(0L)
                val dRx = (rx - trafficStatsLastRx).coerceAtLeast(0L)
                
                val up = (dTx * 1000L) / dtMs
                val down = (dRx * 1000L) / dtMs
                
                // 平滑处理 (指数移动平均)，与首页 DashboardViewModel 保持一致
                // 使用 synchronized 确保线程安全
                synchronized(this@SingBoxService) {
                    val smoothFactor = 0.3
                    currentUploadSpeed = if (currentUploadSpeed == 0L) up else (currentUploadSpeed * (1 - smoothFactor) + up * smoothFactor).toLong()
                    currentDownloadSpeed = if (currentDownloadSpeed == 0L) down else (currentDownloadSpeed * (1 - smoothFactor) + down * smoothFactor).toLong()
                }
                
                if (showNotificationSpeed) {
                    requestNotificationUpdate(force = false)
                }
                
                trafficStatsLastTx = tx
                trafficStatsLastRx = rx
                trafficStatsLastSampleTime = nowElapsed
            }
        }
    }
    
    private fun stopTrafficStatsMonitor() {
        trafficStatsMonitorJob?.cancel()
        trafficStatsMonitorJob = null
        currentUploadSpeed = 0
        currentDownloadSpeed = 0
        trafficStatsBaseTx = 0
        trafficStatsBaseRx = 0
        trafficStatsLastTx = 0
        trafficStatsLastRx = 0
        trafficStatsLastSampleTime = 0
    }
}
