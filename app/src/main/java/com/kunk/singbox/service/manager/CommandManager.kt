package com.kunk.singbox.service.manager

import android.content.Context
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.TrafficRepository
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Command Server/Client 管理器
 * 负责与 libbox 的命令交互，包括：
 * - 日志收集
 * - 状态监控
 * - 连接追踪
 * - 节点组管理
 *
 * 新版 libbox API:
 * - CommandServer 通过 NewCommandServer(handler, platformInterface) 创建
 * - CommandServer.StartOrReloadService(configContent, options) 启动服务
 * - CommandClient 通过 NewCommandClient(handler, options) 创建
 * - CommandClientHandler 接口方法变化
 */
class CommandManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CommandManager"
    }

    // Command Server/Client
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var commandClientLogs: CommandClient? = null
    private var commandClientConnections: CommandClient? = null

    @Volatile
    private var isNonEssentialSuspended: Boolean = false

    // 状态
    private val groupSelectedOutbounds = ConcurrentHashMap<String, String>()
    @Volatile var realTimeNodeName: String? = null
        private set
    @Volatile var activeConnectionNode: String? = null
        private set
    @Volatile var activeConnectionLabel: String? = null
        private set
    var recentConnectionIds: List<String> = emptyList()
        private set

    // 流量统计
    private var lastUplinkTotal: Long = 0
    private var lastDownlinkTotal: Long = 0
    private var lastSpeedUpdateTime: Long = 0L
    private var lastConnectionsLabelLogged: String? = null

    /**
     * 回调接口
     */
    interface Callbacks {
        fun requestNotificationUpdate(force: Boolean)
        fun resolveEgressNodeName(tagOrSelector: String?): String?
        fun onServiceStop(): Unit
        fun onServiceReload(): Unit
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * 创建 CommandServer 并启动服务
     */
    fun createServer(platformInterface: PlatformInterface): Result<CommandServer> = runCatching {
        val serverHandler = object : CommandServerHandler {
            override fun serviceStop() {
                Log.i(TAG, "serviceStop requested")
                callbacks?.onServiceStop()
            }

            override fun serviceReload() {
                Log.i(TAG, "serviceReload requested")
                callbacks?.onServiceReload()
            }

            override fun getSystemProxyStatus(): SystemProxyStatus? = null

            override fun setSystemProxyEnabled(isEnabled: Boolean) {}

            override fun writeDebugMessage(message: String?) {
                if (!message.isNullOrBlank()) {
                    Log.d(TAG, "Debug: $message")
                }
            }
        }

        // 创建 CommandServer
        val server = Libbox.newCommandServer(serverHandler, platformInterface)
        commandServer = server
        Log.i(TAG, "CommandServer created")
        server
    }

    /**
     * 启动 CommandServer
     */
    fun startServer(): Result<Unit> = runCatching {
        commandServer?.start() ?: throw IllegalStateException("CommandServer not created")
        Log.i(TAG, "CommandServer started")

        // 初始化 BoxWrapperManager
        commandServer?.let { server ->
            BoxWrapperManager.init(server)
        }
    }

    /**
     * 启动/重载服务配置
     */
    fun startOrReloadService(configContent: String, options: OverrideOptions? = null): Result<Unit> = runCatching {
        val server = commandServer ?: throw IllegalStateException("CommandServer not created")
        val overrideOptions = options ?: OverrideOptions()
        server.startOrReloadService(configContent, overrideOptions)
        Log.i(TAG, "Service started/reloaded")
    }

    /**
     * 关闭服务
     */
    fun closeService(): Result<Unit> = runCatching {
        commandServer?.closeService()
        Log.i(TAG, "Service closed")
    }

    /**
     * 启动 Command Clients
     */
    fun startClients(): Result<Unit> = runCatching {
        val clientHandler = createClientHandler()

        // 1. 启动 CommandClient (Groups + Status)
        val options = CommandClientOptions()
        options.addCommand(Libbox.CommandGroup)
        options.addCommand(Libbox.CommandStatus)
        options.statusInterval = 3000L * 1000L * 1000L // 3s (nanoseconds)
        commandClient = Libbox.newCommandClient(clientHandler, options)
        commandClient?.connect()
        Log.i(TAG, "CommandClient connected")

        // 2. 启动 CommandClient (Logs)
        val optionsLog = CommandClientOptions()
        optionsLog.addCommand(Libbox.CommandLog)
        optionsLog.statusInterval = 1500L * 1000L * 1000L
        commandClientLogs = Libbox.newCommandClient(clientHandler, optionsLog)
        commandClientLogs?.connect()
        Log.i(TAG, "CommandClient (Logs) connected")

        // 3. 启动 CommandClient (Connections)
        val optionsConn = CommandClientOptions()
        optionsConn.addCommand(Libbox.CommandConnections)
        optionsConn.statusInterval = 5000L * 1000L * 1000L
        commandClientConnections = Libbox.newCommandClient(clientHandler, optionsConn)
        commandClientConnections?.connect()
        Log.i(TAG, "CommandClient (Connections) connected")

        // 验证回调
        serviceScope.launch {
            delay(3500)
            val groupsSize = groupSelectedOutbounds.size
            val label = activeConnectionLabel
            if (groupsSize == 0 && label.isNullOrBlank()) {
                Log.w(TAG, "Command callbacks not observed yet")
            } else {
                Log.i(TAG, "Command callbacks OK (groups=$groupsSize)")
            }
        }
    }

    /**
     * 停止所有 Command Server/Client
     */
    fun stop(): Result<Unit> = runCatching {
        commandClient?.disconnect()
        commandClient = null
        commandClientLogs?.disconnect()
        commandClientLogs = null
        commandClientConnections?.disconnect()
        commandClientConnections = null

        BoxWrapperManager.release()

        // 必须先关闭服务 (释放端口和连接)，再关闭 server
        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "closeService failed: ${it.message}") }
        commandServer?.close()
        commandServer = null
        Log.i(TAG, "Command Server/Client stopped")
    }

    /**
     * 获取 CommandServer
     */
    fun getCommandServer(): CommandServer? = commandServer

    /**
     * 获取 CommandClient (用于连接管理)
     */
    fun getCommandClient(): CommandClient? = commandClient
    fun getConnectionsClient(): CommandClient? = commandClientConnections

    /**
     * 获取指定 group 的选中 outbound
     */
    fun getSelectedOutbound(groupTag: String): String? = groupSelectedOutbounds[groupTag]

    /**
     * 获取所有 group 选中状态的数量
     */
    fun getGroupsCount(): Int = groupSelectedOutbounds.size

    /**
     * 关闭所有连接
     */
    fun closeConnections(): Boolean {
        val clients = listOfNotNull(commandClientConnections, commandClient)
        for (client in clients) {
            try {
                client.closeConnections()
                Log.i(TAG, "Connections closed via CommandClient")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "closeConnections failed: ${e.message}")
            }
        }
        return false
    }

    /**
     * 关闭指定连接
     */
    fun closeConnection(connId: String): Boolean {
        val client = commandClientConnections ?: commandClient ?: return false
        return try {
            val method = client.javaClass.methods.find {
                it.name == "closeConnection" && it.parameterCount == 1
            }
            method?.invoke(client, connId)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createClientHandler(): CommandClientHandler = object : CommandClientHandler {
        override fun connected() {}

        override fun disconnected(message: String?) {
            Log.w(TAG, "CommandClient disconnected: $message")
        }

        override fun setDefaultLogLevel(level: Int) {
            // 设置默认日志级别
        }

        override fun clearLogs() {
            runCatching { LogRepository.getInstance().clearLogs() }
        }

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null) return
            val repo = LogRepository.getInstance()
            runCatching {
                while (messageList.hasNext()) {
                    val entry = messageList.next()
                    val msg = entry?.message
                    if (!msg.isNullOrBlank()) {
                        repo.addLog(msg)
                    }
                }
            }
        }

        override fun writeStatus(message: StatusMessage?) {
            message ?: return
            val currentUp = message.uplinkTotal
            val currentDown = message.downlinkTotal
            val currentTime = System.currentTimeMillis()

            if (lastSpeedUpdateTime == 0L || currentTime < lastSpeedUpdateTime) {
                lastSpeedUpdateTime = currentTime
                lastUplinkTotal = currentUp
                lastDownlinkTotal = currentDown
                return
            }

            if (currentUp < lastUplinkTotal || currentDown < lastDownlinkTotal) {
                lastUplinkTotal = currentUp
                lastDownlinkTotal = currentDown
                lastSpeedUpdateTime = currentTime
                return
            }

            val diffUp = currentUp - lastUplinkTotal
            val diffDown = currentDown - lastDownlinkTotal

            if (diffUp > 0 || diffDown > 0) {
                val repo = ConfigRepository.getInstance(context)
                val activeNodeId = repo.activeNodeId.value
                if (activeNodeId != null) {
                    TrafficRepository.getInstance(context).addTraffic(activeNodeId, diffUp, diffDown)
                }
            }

            lastUplinkTotal = currentUp
            lastDownlinkTotal = currentDown
            lastSpeedUpdateTime = currentTime
        }

        override fun writeGroups(groups: OutboundGroupIterator?) {
            if (groups == null) return
            val configRepo = ConfigRepository.getInstance(context)

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

                    if (tag.equals("PROXY", ignoreCase = true)) {
                        if (!selected.isNullOrBlank() && selected != realTimeNodeName) {
                            realTimeNodeName = selected
                            VpnStateStore.setActiveLabel(selected)
                            Log.i(TAG, "Real-time node update: $selected")
                            serviceScope.launch {
                                configRepo.syncActiveNodeFromProxySelection(selected)
                            }
                            changed = true
                        }
                    }
                }

                if (changed) {
                    callbacks?.requestNotificationUpdate(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing groups update", e)
            }
        }

        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun updateClashMode(newMode: String?) {}

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            events ?: return
            try {
                processConnectionEvents(events)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing connection events", e)
            }
        }
    }

    private fun processConnectionEvents(events: ConnectionEvents) {
        // 处理连接事件
        val iterator = events.iterator()
        var newestConnection: io.nekohasekai.libbox.Connection? = null
        val ids = ArrayList<String>(64)
        val egressCounts = LinkedHashMap<String, Int>()
        val configRepo = ConfigRepository.getInstance(context)

        while (iterator.hasNext()) {
            val event = iterator.next()
            val connection = event.connection ?: continue
            // 跳过关闭的连接
            if (event.closedAt > 0) continue
            // 跳过 dns-out
            val rule = connection.rule
            if (rule == "dns-out") continue

            if (newestConnection == null || connection.createdAt > newestConnection.createdAt) {
                newestConnection = connection
            }

            val id = connection.id
            if (!id.isNullOrBlank()) {
                ids.add(id)
            }

            // 解析 egress
            var candidateTag: String? = rule
            if (candidateTag.isNullOrBlank() || candidateTag == "dns-out") {
                candidateTag = null
            }

            if (!candidateTag.isNullOrBlank()) {
                val resolved = callbacks?.resolveEgressNodeName(candidateTag)
                    ?: configRepo.resolveNodeNameFromOutboundTag(candidateTag)
                    ?: candidateTag
                if (!resolved.isNullOrBlank()) {
                    egressCounts[resolved] = (egressCounts[resolved] ?: 0) + 1
                }
            }
        }

        recentConnectionIds = ids

        // 生成标签
        val newLabel = when {
            egressCounts.isEmpty() -> null
            egressCounts.size == 1 -> egressCounts.keys.first()
            else -> {
                val sorted = egressCounts.entries.sortedByDescending { it.value }.map { it.key }
                val top = sorted.take(2)
                val more = sorted.size - top.size
                if (more > 0) "Mixed: ${top.joinToString(" + ")}(+$more)"
                else "Mixed: ${top.joinToString(" + ")}"
            }
        }

        val labelChanged = newLabel != activeConnectionLabel
        if (labelChanged) {
            activeConnectionLabel = newLabel
            if (newLabel != lastConnectionsLabelLogged) {
                lastConnectionsLabelLogged = newLabel
                Log.d(TAG, "Connections label updated: ${newLabel ?: "(null)"}")
            }
        }

        // 更新活跃连接节点
        var newNode: String? = null
        if (newestConnection != null) {
            // 使用 chain 获取出站链
            val chainIter = newestConnection.chain()
            val chainList = mutableListOf<String>()
            if (chainIter != null) {
                while (chainIter.hasNext()) {
                    val tag = chainIter.next()
                    if (!tag.isNullOrBlank() && tag != "dns-out") {
                        chainList.add(tag)
                    }
                }
            }
            newNode = chainList.lastOrNull()
        }

        if (newNode != activeConnectionNode || labelChanged) {
            activeConnectionNode = newNode
            callbacks?.requestNotificationUpdate(false)
        }
    }

    fun cleanup() {
        stop()
        groupSelectedOutbounds.clear()
        realTimeNodeName = null
        activeConnectionNode = null
        activeConnectionLabel = null
        recentConnectionIds = emptyList()
        callbacks = null
        isNonEssentialSuspended = false
    }

    fun suspendNonEssential() {
        if (isNonEssentialSuspended) return
        isNonEssentialSuspended = true

        commandClientLogs?.disconnect()
        commandClientLogs = null

        commandClientConnections?.disconnect()
        commandClientConnections = null

        Log.i(TAG, "Non-essential clients suspended (Logs, Connections)")
    }

    fun resumeNonEssential() {
        if (!isNonEssentialSuspended) return
        isNonEssentialSuspended = false

        if (commandServer == null) {
            Log.w(TAG, "Cannot resume: no CommandServer")
            return
        }

        val clientHandler = createClientHandler()

        try {
            val optionsLog = CommandClientOptions()
            optionsLog.addCommand(Libbox.CommandLog)
            optionsLog.statusInterval = 1500L * 1000L * 1000L
            commandClientLogs = Libbox.newCommandClient(clientHandler, optionsLog)
            commandClientLogs?.connect()
            Log.i(TAG, "CommandClient (Logs) resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume Logs client", e)
        }

        try {
            val optionsConn = CommandClientOptions()
            optionsConn.addCommand(Libbox.CommandConnections)
            optionsConn.statusInterval = 5000L * 1000L * 1000L
            commandClientConnections = Libbox.newCommandClient(clientHandler, optionsConn)
            commandClientConnections?.connect()
            Log.i(TAG, "CommandClient (Connections) resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume Connections client", e)
        }
    }

    val isNonEssentialActive: Boolean
        get() = !isNonEssentialSuspended && (commandClientLogs != null || commandClientConnections != null)
}
