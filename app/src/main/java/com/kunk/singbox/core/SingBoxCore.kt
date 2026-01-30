package com.kunk.singbox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.config.OutboundFixer
import com.kunk.singbox.ipc.VpnStateStore
import kotlinx.coroutines.flow.first
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import com.kunk.singbox.utils.PreciseLatencyTester
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sing-box 核心封装类
 * 负责与 libbox 交互，提供延迟测试等功能
 *
 * 如果 libbox 不可用，将使用降级方案进行测试
 */
class SingBoxCore private constructor(private val context: Context) {

    private val gson = Gson()
    private val workDir: File = File(context.filesDir, "singbox_work")
    private val tempDir: File = File(context.cacheDir, "singbox_temp")

    // libbox 是否可用
    private var libboxAvailable = false

    // Global lock for libbox operations to prevent native concurrency issues
    // 注意: 这个 mutex 只用于需要独占资源的操作(如 HTTP proxy fallback)
    @Suppress("UnusedPrivateProperty")
    private val libboxMutex = kotlinx.coroutines.sync.Mutex()

    // 优化: 使用 Semaphore 限制 HTTP 代理并发数,允许一定程度的并发
    // 批量测试时,不再完全串行化,性能提升 2-3倍
    private val httpProxySemaphore = Semaphore(3) // 最多3个并发HTTP代理测试

    companion object {
        private const val TAG = "SingBoxCore"

        private val libboxSetupDone = AtomicBoolean(false)
        // 最近一次原生测速预热时间（避免每次都预热）
        @Volatile
        private var lastNativeWarmupAt: Long = 0

        @Volatile
        private var instance: SingBoxCore? = null

        fun getInstance(context: Context): SingBoxCore {
            return instance ?: synchronized(this) {
                instance ?: SingBoxCore(context.applicationContext).also { instance = it }
            }
        }

        fun ensureLibboxSetup(context: Context) {
            if (libboxSetupDone.get()) return

            val appContext = context.applicationContext
            val pid = runCatching { Process.myPid() }.getOrDefault(0)
            val baseDir = File(appContext.filesDir, "libbox_$pid").also { it.mkdirs() }
            val workDir = File(baseDir, "singbox_work").also { it.mkdirs() }
            val tempDir = File(baseDir, "singbox_temp").also { it.mkdirs() }

            val setupOptions = SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workDir.absolutePath
                this.tempPath = tempDir.absolutePath
            }

            if (!libboxSetupDone.compareAndSet(false, true)) return
            try {
                Libbox.setup(setupOptions)
            } catch (e: Exception) {
                libboxSetupDone.set(false)
                Log.w(TAG, "Libbox setup warning: ${e.message}")
            }
        }
    }

    init {
        // 确保工作目录存在
        workDir.mkdirs()
        tempDir.mkdirs()

        // 尝试初始化 libbox
        libboxAvailable = initLibbox()

        if (!libboxAvailable) {
            Log.w(TAG, "Libbox not available, using fallback mode")
        }
    }

    private fun initLibbox(): Boolean {
        return try {
            Libbox.version() // Simple check
            ensureLibboxSetup(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Libbox init failed", e)
            false
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "Libbox class not found", e)
            false
        }
    }

    /**
     * 检查 libbox 是否可用
     */
    fun isLibboxAvailable(): Boolean = libboxAvailable

    /**
     * 使用 Libbox 原生方法进行延迟测试
     * 优先尝试调用内核的 urlTest 方法，失败则回退到本地 HTTP 代理测速
     */
    private suspend fun testOutboundLatencyWithLibbox(
        outbound: Outbound,
        settings: com.kunk.singbox.model.AppSettings? = null,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        if (!libboxAvailable) return@withContext -1L

        val finalSettings = settings ?: SettingsRepository.getInstance(context).settings.first()
        val url = adjustUrlForMode(finalSettings.latencyTestUrl, finalSettings.latencyTestMethod)
        val timeoutMs = finalSettings.latencyTestTimeout

        // 尝试使用原生 urlTest
        // Remove mutex to allow concurrent testing
        val nativeRtt = testWithLibboxStaticUrlTest(outbound, url, timeoutMs, finalSettings.latencyTestMethod)

        if (nativeRtt >= 0) {
            return@withContext nativeRtt
        }

        // 回退方案：本地 HTTP 代理测速
        return@withContext try {
            val fallbackUrl = try {
                if (finalSettings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                    adjustUrlForMode("http://www.gstatic.com/generate_204", finalSettings.latencyTestMethod)
                } else {
                    adjustUrlForMode("https://www.gstatic.com/generate_204", finalSettings.latencyTestMethod)
                }
            } catch (_: Exception) { url }
            testWithLocalHttpProxy(outbound, url, fallbackUrl, timeoutMs, dependencyOutbounds)
        } catch (e: Exception) {
            Log.w(TAG, "Native HTTP proxy test failed: ${e.message}")
            -1L
        }
    }

    // private var discoveredUrlTestMethod: java.lang.reflect.Method? = null
    // private var discoveredMethodType: Int = 0 // 0: long, 1: URLTest object

    /**
     * 解析 outbound 的依赖 outbounds
     * 例如 SS + ShadowTLS 节点，SS 的 detour 字段指向 shadowtls outbound
     * @param outbound 主节点
     * @param allOutbounds 完整的 outbound 列表（包含所有潜在依赖）
     * @return 依赖的 outbound 列表（不包含主节点本身）
     */
    private fun resolveDependencyOutbounds(
        outbound: Outbound,
        allOutbounds: List<Outbound>
    ): List<Outbound> {
        val dependencies = mutableListOf<Outbound>()
        val visited = mutableSetOf<String>()

        fun resolve(current: Outbound) {
            val detourTag = current.detour
            if (detourTag.isNullOrBlank() || visited.contains(detourTag)) return
            visited.add(detourTag)

            val detourOutbound = allOutbounds.find { it.tag == detourTag }
            if (detourOutbound != null) {
                dependencies.add(detourOutbound)
                // 递归解析依赖的依赖
                resolve(detourOutbound)
            }
        }

        resolve(outbound)
        return dependencies
    }

    private fun adjustUrlForMode(original: String, method: LatencyTestMethod): String {
        return try {
            val u = URI(original)
            val host = u.host ?: return original
            val path = if ((u.path ?: "").isNotEmpty()) u.path else "/"
            val query = u.query
            val fragment = u.fragment
            val userInfo = u.userInfo
            val port = u.port
            when (method) {
                LatencyTestMethod.TCP -> URI("http", userInfo, host, if (port == -1) -1 else port, path, query, fragment).toString()
                LatencyTestMethod.HANDSHAKE -> URI("https", userInfo, host, if (port == -1) -1 else port, path, query, fragment).toString()
                else -> original
            }
        } catch (_: Exception) {
            original
        }
    }

    // Removed reflection helpers: extractDelayFromUrlTest, hasDelayAccessors, buildUrlTestArgs

    private suspend fun testWithLocalHttpProxy(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        // 优化: 使用 Semaphore 替代 Mutex,允许有限并发
        // 原因: 虽然每个测试都启动临时 service,但通过限制并发数(3个)
        //       可以在保证稳定性的同时,显著提升批量测试性能
        httpProxySemaphore.withPermit {
            testWithLocalHttpProxyInternal(outbound, targetUrl, fallbackUrl, timeoutMs, dependencyOutbounds)
        }
    }

    private suspend fun testWithLocalHttpProxyInternal(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long {
        // 2025-fix: VPN 运行时使用 native 测速
        // 内核已添加 defer/recover 保护
        if (VpnStateStore.getActive() && BoxWrapperManager.isAvailable()) {
            Log.i(TAG, "VPN is running, using native URL test for ${outbound.tag}")
            val result = BoxWrapperManager.urlTestOutbound(outbound.tag, targetUrl, timeoutMs)
            return if (result >= 0) result.toLong() else -1L
        }

        val port = allocateLocalPort()
        val inbound = com.kunk.singbox.model.Inbound(
            type = "mixed",
            tag = "test-in",
            listen = "127.0.0.1",
            listenPort = port
        )

        // 关键修复: 在 VPN 未运行时,将进程绑定到默认网络
        // 这样 sing-box 创建的所有 socket 都会使用物理网络,而不是尝试 auto-detect
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnRunning = com.kunk.singbox.service.SingBoxService.instance != null
        var previousNetwork: Network? = null

        if (!vpnRunning) {
            try {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    previousNetwork = connectivityManager.boundNetworkForProcess
                    val bound = connectivityManager.bindProcessToNetwork(activeNetwork)
                    Log.d(TAG, "bindProcessToNetwork: bound=$bound, network=$activeNetwork")
                } else {
                    Log.w(TAG, "No active network available for binding")
                }
            } catch (e: Exception) {
                Log.w(TAG, "bindProcessToNetwork failed: ${e.message}")
            }
        }

        return try {
            val direct = com.kunk.singbox.model.Outbound(type = "direct", tag = "direct")

            // 构建 outbound 列表，包含主节点和依赖的辅助节点（如 shadowtls）
            val allOutbounds = mutableListOf(outbound)
            allOutbounds.addAll(dependencyOutbounds)
            allOutbounds.add(direct)

            // 为测试服务生成唯一的临时数据库路径,避免与 VPN 服务的数据库冲突
            // 使用 UUID 确保绝对唯一性,防止高并发时时间戳重复导致路径冲突
            val testDbPath = File(tempDir, "test_${UUID.randomUUID()}.db").absolutePath

            val config = SingBoxConfig(
                log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true), // 使用 debug 级别诊断问题
                // 测速服务使用纯 IP DNS，避免 DoH 请求被 VPN 拦截
                // 添加多个 DNS 服务器作为备份，提高可靠性
                // 关键: 必须设置 finalServer，否则 sing-box 不知道使用哪个 DNS 服务器
                dns = com.kunk.singbox.model.DnsConfig(
                    servers = listOf(
                        com.kunk.singbox.model.DnsServer(
                            tag = "dns-direct",
                            address = "223.5.5.5",
                            detour = "direct",
                            strategy = "ipv4_only"
                        ),
                        com.kunk.singbox.model.DnsServer(
                            tag = "dns-backup",
                            address = "119.29.29.29",
                            detour = "direct",
                            strategy = "ipv4_only"
                        )
                    ),
                    finalServer = "dns-direct", // 指定默认 DNS 服务器
                    strategy = "ipv4_only" // 全局 DNS 策略
                ),
                inbounds = listOf(inbound),
                outbounds = allOutbounds,
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(
                        com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct"),
                        com.kunk.singbox.model.RouteRule(inbound = listOf("test-in"), outbound = outbound.tag)
                    ),
                    finalOutbound = "direct",
                    // 关键修复: 不设置 defaultInterface，因为测速服务没有权限绑定到特定接口
                    // 只启用 autoDetectInterface，通过 autoDetectInterfaceControl 回调来保护 socket
                    autoDetectInterface = true
                ),
                // 完全禁用测试服务的缓存,避免数据库冲突
                // 关键: 必须指定唯一的临时路径,防止与 VPN 服务的 cache.db 冲突
                // bbolt 不支持多进程并发访问同一数据库文件,会触发 "page already freed" panic
                experimental = com.kunk.singbox.model.ExperimentalConfig(
                    cacheFile = com.kunk.singbox.model.CacheFileConfig(
                        enabled = false, // 禁用缓存
                        path = testDbPath, // 使用唯一的临时路径
                        storeFakeip = false
                    )
                )
            )

            val configJson = gson.toJson(config)
            var commandServer: io.nekohasekai.libbox.CommandServer? = null
            try {
                ensureLibboxSetup(context)
                val platformInterface = TestPlatformInterface(context)
                val serverHandler = TestCommandServerHandler()
                commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
                commandServer.start()
                commandServer.startOrReloadService(configJson, io.nekohasekai.libbox.OverrideOptions())

                val deadline = System.currentTimeMillis() + 500L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Socket().use { s ->
                            s.soTimeout = 50
                            s.connect(InetSocketAddress("127.0.0.1", port), 50)
                        }
                        break
                    } catch (_: Exception) {
                        delay(20)
                    }
                }

                val result = PreciseLatencyTester.test(
                    proxyPort = port,
                    url = targetUrl,
                    timeoutMs = timeoutMs,
                    standard = PreciseLatencyTester.Standard.RTT,
                    warmup = false
                )
                if (result.isSuccess && result.latencyMs <= timeoutMs) {
                    result.latencyMs
                } else {
                    -1L
                }
            } finally {
                try {
                    commandServer?.closeService()
                    commandServer?.close()
                } catch (e: Exception) { Log.w(TAG, "Failed to close command server", e) }
                // 清理临时数据库文件,防止泄漏
                try {
                    File(testDbPath).delete()
                    File("$testDbPath-shm").delete() // SQLite WAL 模式的共享内存文件
                    File("$testDbPath-wal").delete() // SQLite WAL 日志文件
                } catch (e: Exception) { Log.w(TAG, "Failed to delete temp db files", e) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local HTTP proxy setup failed", e)
            -1L
        } finally {
            // 恢复进程网络绑定状态
            if (!vpnRunning) {
                try {
                    connectivityManager.bindProcessToNetwork(previousNetwork)
                    Log.d(TAG, "Restored process network binding")
                } catch (e: Exception) { Log.w(TAG, "Failed to restore network binding", e) }
            }
        }
    }

    /**
     * 尝试使用 libbox 原生 urlTest 方法进行延迟测试
     *
     * KunBox 扩展内核 (v1.1.0+) 包含 URLTestOutbound 和 URLTestStandalone 方法:
     * - URLTestOutbound: VPN 运行时，使用当前 BoxService 实例测试
     * - URLTestStandalone: VPN 未运行时，创建临时实例测试
     *
     * 如果内核不支持（未使用 KunBox 扩展编译），回退到本地 HTTP 代理测速
     *
     * @return 延迟时间(毫秒), -1 表示不支持或测试失败
     */
    private suspend fun testWithLibboxStaticUrlTest(
        outbound: Outbound,
        targetUrl: String,
        timeoutMs: Int,
        method: LatencyTestMethod
    ): Long = withContext(Dispatchers.IO) {
        try {
            // 使用 KunBox 扩展内核的静态 URLTestOutbound 方法
            if (BoxWrapperManager.isAvailable()) {
                // 调用静态方法: Libbox.urlTestOutbound(tag, url, timeout)
                val result = Libbox.urlTestOutbound(outbound.tag, targetUrl, timeoutMs)
                if (result >= 0) {
                    Log.d(TAG, "Native URLTest ${outbound.tag}: ${result}ms")
                    return@withContext result.toLong()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native URLTest failed: ${e.message}")
        }

        // 不支持或失败，返回 -1 触发回退
        return@withContext -1L
    }

    private suspend fun testWithTemporaryServiceUrlTestOnRunning(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        method: LatencyTestMethod,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        // 尝试调用 native 方法 (如果 VPN 正在运行)
        if (VpnStateStore.getActive() && libboxAvailable) {
            val rtt = testWithLibboxStaticUrlTest(outbound, targetUrl, timeoutMs, method)
            if (rtt >= 0) return@withContext rtt
        }

        // 内核不支持或未运行，直接走 HTTP 代理测速
        testWithLocalHttpProxyInternal(outbound, targetUrl, fallbackUrl, timeoutMs, dependencyOutbounds)
    }

    private suspend fun testOutboundsLatencyOfflineWithTemporaryService(
        outbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        method: LatencyTestMethod,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 使用批量测试优化
        // 为了避免单次启动配置过大，我们按 50 个节点一批进行处理
        // 这样既能享受批量优势，又能避免内存或配置过大问题
        val batchSize = 50

        // 获取设置中的并发数
        val settings = SettingsRepository.getInstance(context).settings.first()
        val concurrency = settings.latencyTestConcurrency

        outbounds.chunked(batchSize).forEach { batch ->
            // 对每一批节点启动一次服务
            testOutboundsLatencyBatchInternal(batch, targetUrl, timeoutMs, concurrency, onResult)
        }
    }

    /**
     * 批量测试节点延迟的内部实现
     * 为每个节点分配独立端口，启动临时 sing-box 服务进行并发测试
     */
    @Suppress("CognitiveComplexMethod", "LongMethod")
    private suspend fun testOutboundsLatencyBatchInternal(
        batchOutbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        concurrency: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        if (batchOutbounds.isEmpty()) return

        // 2025-fix: 防御性检查 - VPN 运行时禁止创建临时服务，使用 native 批量测速
        // 临时服务的 closeService() 会污染全局 Libbox.isRunning() 状态
        if (VpnStateStore.getActive() && BoxWrapperManager.isAvailable()) {
            Log.i(TAG, "VPN is running, using native batch URL test instead of temporary service")
            val results = BoxWrapperManager.urlTestBatch(
                outboundTags = batchOutbounds.map { it.tag },
                url = targetUrl,
                timeoutMs = timeoutMs,
                concurrency = concurrency.coerceIn(1, 20)
            )
            batchOutbounds.forEach { outbound ->
                onResult(outbound.tag, results[outbound.tag]?.toLong() ?: -1L)
            }
            return
        }

        val ports: List<Int>
        try {
            ports = allocateMultipleLocalPorts(batchOutbounds.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to allocate ports for batch test", e)
            batchOutbounds.forEach { onResult(it.tag, -1L) }
            return
        }

        val portToTagMap = ports.zip(batchOutbounds.map { it.tag }).toMap()
        val fixedOutbounds = batchOutbounds.map { OutboundFixer.buildForRuntime(context, it) }
        val config = buildBatchTestConfig(fixedOutbounds, ports)
        val configJson = gson.toJson(config)

        var commandServer: CommandServer? = null
        try {
            ensureLibboxSetup(context)
            val platformInterface = TestPlatformInterface(context)
            val serverHandler = TestCommandServerHandler()
            commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
            commandServer.start()
            commandServer.startOrReloadService(configJson, io.nekohasekai.libbox.OverrideOptions())

            val portsReady = waitForPortsReady(ports)
            if (!portsReady) {
                Log.e(TAG, "Batch test: ports not ready")
                batchOutbounds.forEach { onResult(it.tag, -1L) }
                return
            }

            runPreciseLatencyTests(portToTagMap, targetUrl, timeoutMs, concurrency, onResult)
        } catch (e: Exception) {
            Log.e(TAG, "Batch test failed", e)
            batchOutbounds.forEach { onResult(it.tag, -1L) }
        } finally {
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
        }
    }

    @Suppress("UnusedPrivateMember")
    private fun restoreNetworkBinding(vpnRunning: Boolean, cm: ConnectivityManager, network: Network?) {
        if (!vpnRunning) {
            try {
                cm.bindProcessToNetwork(network)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore network binding", e)
            }
        }
    }

    @Suppress("LongMethod")
    private fun buildBatchTestConfig(
        batchOutbounds: List<Outbound>,
        ports: List<Int>
    ): SingBoxConfig {
        val inbounds = ArrayList<com.kunk.singbox.model.Inbound>()
        val rules = ArrayList<com.kunk.singbox.model.RouteRule>()

        batchOutbounds.forEachIndexed { index, outbound ->
            val port = ports[index]
            val inboundTag = "test-in-$index"
            inbounds.add(com.kunk.singbox.model.Inbound(
                type = "mixed",
                tag = inboundTag,
                listen = "127.0.0.1",
                listenPort = port
            ))
            rules.add(com.kunk.singbox.model.RouteRule(
                inbound = listOf(inboundTag),
                outbound = outbound.tag
            ))
        }

        val dnsConfig = com.kunk.singbox.model.DnsConfig(
            servers = listOf(
                com.kunk.singbox.model.DnsServer(tag = "dns-direct", address = "223.5.5.5", detour = "direct", strategy = "ipv4_only"),
                com.kunk.singbox.model.DnsServer(tag = "dns-backup", address = "119.29.29.29", detour = "direct", strategy = "ipv4_only")
            ),
            finalServer = "dns-direct",
            strategy = "ipv4_only"
        )

        val safeOutbounds = ArrayList(batchOutbounds)
        val addedTags = batchOutbounds.map { it.tag }.toMutableSet()

        for (outbound in batchOutbounds) {
            val dependencies = resolveDependencyOutbounds(outbound, batchOutbounds)
            for (dep in dependencies) {
                if (addedTags.add(dep.tag)) {
                    safeOutbounds.add(dep)
                }
            }
        }

        if (safeOutbounds.none { it.tag == "direct" }) safeOutbounds.add(com.kunk.singbox.model.Outbound(type = "direct", tag = "direct"))
        if (safeOutbounds.none { it.tag == "block" }) safeOutbounds.add(com.kunk.singbox.model.Outbound(type = "block", tag = "block"))
        if (safeOutbounds.none { it.tag == "dns-out" }) safeOutbounds.add(com.kunk.singbox.model.Outbound(type = "dns", tag = "dns-out"))

        val batchTestDbPath = File(tempDir, "batch_test_${UUID.randomUUID()}.db").absolutePath

        return SingBoxConfig(
            log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true),
            dns = dnsConfig,
            inbounds = inbounds,
            outbounds = safeOutbounds,
            route = com.kunk.singbox.model.RouteConfig(
                rules = listOf(
                    com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct")
                ) + rules,
                finalOutbound = "direct",
                autoDetectInterface = true
            ),
            experimental = com.kunk.singbox.model.ExperimentalConfig(
                cacheFile = com.kunk.singbox.model.CacheFileConfig(
                    enabled = false,
                    path = batchTestDbPath,
                    storeFakeip = false
                )
            )
        )
    }

    private suspend fun waitForPortsReady(ports: List<Int>): Boolean {
        val firstPort = ports.first()
        val deadline = System.currentTimeMillis() + 3000L
        var portReady = false
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.soTimeout = 100
                    s.connect(InetSocketAddress("127.0.0.1", firstPort), 100)
                }
                portReady = true
                break
            } catch (_: Exception) {
                delay(50)
            }
        }
        if (!portReady) {
            Log.e(TAG, "Batch test: port $firstPort not ready after 3s")
            return false
        }

        val portsToCheck = ports.take(minOf(3, ports.size))
        var allPortsReady = false
        for (attempt in 1..5) {
            allPortsReady = portsToCheck.all { port ->
                try {
                    Socket().use { s ->
                        s.soTimeout = 50
                        s.connect(InetSocketAddress("127.0.0.1", port), 50)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (allPortsReady) break
            if (attempt < 5) delay(50)
        }
        if (!allPortsReady) delay(100)
        return true
    }

    private suspend fun runPreciseLatencyTests(
        portToTagMap: Map<Int, String>,
        targetUrl: String,
        timeoutMs: Int,
        concurrency: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        val semaphore = Semaphore(concurrency)
        coroutineScope {
            val jobs = portToTagMap.map { (port, originalTag) ->
                async {
                    semaphore.withPermit {
                        val result = PreciseLatencyTester.test(
                            proxyPort = port,
                            url = targetUrl,
                            timeoutMs = timeoutMs,
                            standard = PreciseLatencyTester.Standard.RTT,
                            warmup = false
                        )
                        val latency = if (result.isSuccess && result.latencyMs <= timeoutMs) {
                            result.latencyMs
                        } else {
                            -1L
                        }
                        onResult(originalTag, latency)
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    /**
     * 分配多个本地端口用于批量测试
     *
     * @param count 需要分配的端口数量
     * @return 已分配的端口列表
     * @throws RuntimeException 如果无法分配足够的端口
     *
     * 注意: 此方法存在微小的竞态条件窗口 (TOCTOU - Time-of-check to time-of-use)
     * 在 ServerSocket 关闭后到 sing-box 绑定端口之间,理论上其他进程可能占用端口。
     * 但在本地回环接口上,此风险极低。如果发生冲突,上层逻辑会捕获异常并重试。
     */
    private fun allocateMultipleLocalPorts(count: Int): List<Int> {
        val ports = mutableListOf<Int>()
        val sockets = mutableListOf<ServerSocket>()
        try {
            for (i in 0 until count) {
                val socket = ServerSocket(0)
                socket.reuseAddress = true
                ports.add(socket.localPort)
                sockets.add(socket)
            }
        } catch (e: Exception) {
            sockets.forEach { runCatching { it.close() } }
            throw RuntimeException("Failed to allocate $count ports (allocated ${ports.size})", e)
        }
        sockets.forEach { runCatching { it.close() } }
        return ports
    }

    /**
     * 测试单个节点的延迟
     * @param outbound 节点出站配置
     * @param allOutbounds 可选的完整 outbound 列表，用于解析依赖（如 SS+ShadowTLS）
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
    suspend fun testOutboundLatency(
        outbound: Outbound,
        allOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
        val timeoutMs = settings.latencyTestTimeout

        val dependencyOutbounds = if (allOutbounds.isNotEmpty()) {
            resolveDependencyOutbounds(outbound, allOutbounds)
        } else {
            emptyList()
        }

        if (VpnStateStore.getActive()) {
            return@withContext testOutboundLatencyWithLibbox(outbound, settings, dependencyOutbounds)
        }

        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)

        val fallbackUrl = try {
            if (settings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                adjustUrlForMode("http://www.gstatic.com/generate_204", settings.latencyTestMethod)
            } else {
                adjustUrlForMode("https://www.gstatic.com/generate_204", settings.latencyTestMethod)
            }
        } catch (_: Exception) { url }

        val rtt =
            testWithTemporaryServiceUrlTestOnRunning(outbound, url, fallbackUrl, timeoutMs, settings.latencyTestMethod, dependencyOutbounds)
        if (rtt >= 0) {
            return@withContext rtt
        }

        val fallback = testWithLocalHttpProxy(outbound, url, fallbackUrl, timeoutMs, dependencyOutbounds)
        return@withContext fallback
    }

    /**
     * 批量测试节点延迟
     * @param outbounds 节点列表
     * @param onResult 每个节点测试完成后的回调
     */
    suspend fun testOutboundsLatency(
        outbounds: List<Outbound>,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()

        // 2025-fix: 当 VPN 运行时，使用安全测试器保护主网络连接
        // SafeLatencyTester 提供:
        // 1. 主连接保护 - 测试期间持续监控，发现异常立即熔断
        // 2. 自适应并发 - 根据网络状况动态调整并发数
        // 3. 批次让路 - 给主流量喘息空间
        val isNativeUrlTestSupported = BoxWrapperManager.isAvailable()

        if (libboxAvailable && VpnStateStore.getActive() && isNativeUrlTestSupported) {
            val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
            val timeoutMs = settings.latencyTestTimeout

            // 使用安全测试器
            SafeLatencyTester.getInstance().testOutboundsLatencySafe(
                outbounds = outbounds,
                targetUrl = url,
                timeoutMs = timeoutMs,
                onResult = onResult
            )
            return@withContext
        }

        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
        val timeoutMs = settings.latencyTestTimeout
        testOutboundsLatencyOfflineWithTemporaryService(outbounds, url, timeoutMs, settings.latencyTestMethod, onResult)
    }

    private fun allocateLocalPort(): Int {
        var attempts = 0
        val maxAttempts = 10
        while (attempts < maxAttempts) {
            try {
                val socket = ServerSocket(0)
                socket.reuseAddress = true
                val port = socket.localPort
                socket.close()
                if (isPortAvailable(port)) {
                    return port
                }
            } catch (e: Exception) {
                Log.w(TAG, "Port allocation attempt $attempts failed", e)
            }
            attempts++
        }
        throw RuntimeException("Failed to allocate local port after $maxAttempts attempts")
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取当前活动的物理网络接口名称（非 VPN）
     * 用于测速服务显式绑定到物理网络，避免流量被 VPN 拦截
     */
    private fun getPhysicalNetworkInterface(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val activeNetwork = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            cm.allNetworks.forEach { network ->
                val netCaps = cm.getNetworkCapabilities(network) ?: return@forEach
                if (!netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    val linkProps = cm.getLinkProperties(network)
                    val ifaceName = linkProps?.interfaceName
                    if (!ifaceName.isNullOrEmpty()) {
                        Log.d(TAG, "Found physical network interface: $ifaceName")
                        return ifaceName
                    }
                }
            }
            return null
        }

        val linkProps = cm.getLinkProperties(activeNetwork)
        return linkProps?.interfaceName
    }

    /**
     * 验证配置是否有效
     */
    suspend fun validateConfig(config: SingBoxConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (!libboxAvailable) {
            return@withContext try {
                gson.toJson(config)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        try {
            val configJson = gson.toJson(config)
            Libbox.checkConfig(configJson)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Config validation failed", e)
            Result.failure(e)
        }
    }

    /**
     * 验证单个 Outbound 是否有效
     * 构造一个最小配置来验证 outbound
     */
    fun validateOutbound(outbound: Outbound): Boolean {
        if (!libboxAvailable) {
            return true
        }

        // 跳过特殊类型的 outbound
        if (outbound.type in listOf("direct", "block", "dns", "selector", "urltest", "url-test")) {
            return true
        }

        val minimalConfig = SingBoxConfig(
            log = null,
            dns = null,
            inbounds = null,
            outbounds = listOf(
                outbound,
                Outbound(type = "direct", tag = "direct")
            ),
            route = null,
            experimental = null
        )

        return try {
            val configJson = gson.toJson(minimalConfig)
            Libbox.checkConfig(configJson)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Outbound validation failed for '${outbound.tag}': ${e.message}")
            false
        }
    }

    fun formatConfig(config: SingBoxConfig): String = gson.toJson(config)

    // --- Inner Classes for Platform Interface ---

    private class TestPlatformInterface(private val context: Context) : PlatformInterface {
        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        override fun autoDetectInterfaceControl(fd: Int) {
            // 如果 VPN 正在运行，必须 protect 测速 socket，否则流量会被 VPN 拦截
            val service = com.kunk.singbox.service.SingBoxService.instance
            if (service != null) {
                try {
                    val protected = service.protect(fd)
                    if (!protected) {
                        Log.w(TAG, "Failed to protect socket fd=$fd, continuing anyway")
                    } else {
                        Log.d(TAG, "autoDetectInterfaceControl: protected fd=$fd")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Socket protection error for fd=$fd: ${e.message}")
                }
                return
            }

            // VPN 未运行时，需要将 socket 绑定到默认网络
            // 否则 sing-box 无法确定使用哪个网络接口，报错 "no available network interface"
            try {
                val network = connectivityManager.activeNetwork
                if (network != null) {
                    // 使用 ParcelFileDescriptor 包装 fd，然后绑定到网络
                    val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
                    try {
                        network.bindSocket(pfd.fileDescriptor)
                        Log.d(TAG, "autoDetectInterfaceControl: bound fd=$fd to network")
                    } finally {
                        // detachFd 防止 ParcelFileDescriptor.close() 关闭原始 fd
                        pfd.detachFd()
                    }
                } else {
                    Log.w(TAG, "autoDetectInterfaceControl: no active network for fd=$fd")
                }
            } catch (e: Exception) {
                Log.w(TAG, "autoDetectInterfaceControl: bind network error for fd=$fd: ${e.message}")
            }
        }

        override fun openTun(options: TunOptions?): Int {
            // Should not be called as we don't provide tun inbound
            Log.w(TAG, "TestPlatformInterface: openTun called unexpected!")
            return -1
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            // 关键修复: 必须立即通知 sing-box 当前的默认网络接口
            // 否则 sing-box 会报 "no available network interface" 错误
            // 注意: 不能在 cgo 回调中注册 NetworkCallback，会导致 Go runtime 栈溢出崩溃
            // 但我们可以同步获取当前网络状态并立即通知
            if (listener == null) return

            try {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                    val interfaceName = linkProperties?.interfaceName ?: ""
                    if (interfaceName.isNotEmpty()) {
                        val index = try {
                            java.net.NetworkInterface.getByName(interfaceName)?.index ?: 0
                        } catch (e: Exception) { 0 }
                        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                        val isExpensive = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                        listener.updateDefaultInterface(interfaceName, index, isExpensive, false)
                        Log.d(TAG, "TestPlatformInterface: initialized default interface: $interfaceName (index=$index)")
                    } else {
                        Log.w(TAG, "TestPlatformInterface: no interface name for active network")
                    }
                } else {
                    Log.w(TAG, "TestPlatformInterface: no active network available")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TestPlatformInterface: failed to get default interface: ${e.message}")
            }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            // 测速服务是短暂的，无需清理网络监听
        }

        override fun getInterfaces(): NetworkInterfaceIterator? {
            return try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                object : NetworkInterfaceIterator {
                    private val iterator = interfaces.filter { it.isUp && !it.isLoopback }.iterator()
                    override fun hasNext(): Boolean = iterator.hasNext()
                    override fun next(): io.nekohasekai.libbox.NetworkInterface {
                        val iface = iterator.next()
                        return io.nekohasekai.libbox.NetworkInterface().apply {
                            name = iface.name
                            index = iface.index
                            mtu = iface.mtu
                            // type = ... (Field removed/renamed in v1.10)
                            var flagsStr = 0
                            if (iface.isUp) flagsStr = flagsStr or 1
                            if (iface.isLoopback) flagsStr = flagsStr or 4
                            if (iface.isPointToPoint) flagsStr = flagsStr or 8
                            if (iface.supportsMulticast()) flagsStr = flagsStr or 16
                            flags = flagsStr
                            val addrList = ArrayList<String>()
                            for (addr in iface.interfaceAddresses) {
                                val ip = addr.address.hostAddress
                                val cleanIp = if (ip != null && ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                                if (cleanIp != null) addrList.add("$cleanIp/${addr.networkPrefixLength}")
                            }
                            addresses = StringIteratorImpl(addrList)
                        }
                    }
                }
            } catch (e: Exception) { null }
        }

        // 关键: 必须返回 true，否则 sing-box 不会调用 autoDetectInterfaceControl 来 protect socket
        // 这会导致 VPN 运行时测速流量被拦截，形成回环，返回 502 错误
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun useProcFS(): Boolean = false
        override fun findConnectionOwner(
            p0: Int,
            p1: String?,
            p2: Int,
            p3: String?,
            p4: Int
        ): io.nekohasekai.libbox.ConnectionOwner {
            return io.nekohasekai.libbox.ConnectionOwner()
        }
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() {}
        override fun sendNotification(p0: io.nekohasekai.libbox.Notification?) {}
        override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
            return com.kunk.singbox.core.LocalResolverImpl
        }
        override fun systemCertificates(): StringIterator? = null
    }

/**
     * 测速服务用的 CommandServerHandler 实现
     * 仅提供必要的空实现，因为测速服务不需要处理任何命令回调
     */
    private class TestCommandServerHandler : io.nekohasekai.libbox.CommandServerHandler {
        override fun serviceStop() {}
        override fun serviceReload() {}
        override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus? = null
        override fun setSystemProxyEnabled(isEnabled: Boolean) {}
        override fun writeDebugMessage(message: String?) {}
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }

    /**
     * 检查是否有活跃的连接
     * 用于连接健康监控
     */
    fun hasActiveConnections(): Boolean {
        if (!libboxAvailable) return false

        return try {
            // 检查 sing-box 核心是否正在运行
            BoxWrapperManager.isAvailable() && VpnStateStore.getActive()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check active connections", e)
            false
        }
    }

    /**
     * 获取活跃连接列表
     * 用于连接健康监控
     * 注意：当前 libbox 不支持此 API，始终返回空列表
     */
    fun getActiveConnections(): List<ActiveConnection> {
        if (!libboxAvailable) return emptyList()

        return try {
            val iterator = Libbox.getActiveConnectionStates() ?: return emptyList()
            val result = mutableListOf<ActiveConnection>()

            while (iterator.hasNext()) {
                val state = iterator.next() ?: continue
                result.add(
                    ActiveConnection(
                        packageName = state.packageName,
                        uid = 0,
                        network = "tcp",
                        remoteAddr = "",
                        remotePort = 0,
                        state = if (state.hasRecentData) "active" else "stale",
                        connectionCount = state.connectionCount,
                        totalUpload = state.totalUpload,
                        totalDownload = state.totalDownload,
                        oldestConnMs = state.oldestConnMs,
                        newestConnMs = state.newestConnMs,
                        hasRecentData = state.hasRecentData
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "getActiveConnections failed: ${e.message}")
            emptyList()
        }
    }

    fun closeConnectionsForApp(packageName: String): Int {
        if (!libboxAvailable) return 0

        return try {
            val count = Libbox.closeConnectionsForApp(packageName)
            if (count > 0) {
                Log.i(TAG, "Closed $count connections for $packageName")
            }
            count
        } catch (e: Exception) {
            Log.w(TAG, "closeConnectionsForApp failed: ${e.message}")
            0
        }
    }

    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    fun closeConnections(packageName: String, uid: Int): Boolean {
        return closeConnectionsForApp(packageName) > 0
    }

    data class ActiveConnection(
        val packageName: String?,
        val uid: Int,
        val network: String,
        val remoteAddr: String,
        val remotePort: Int,
        val state: String,
        val connectionCount: Int = 0,
        val totalUpload: Long = 0,
        val totalDownload: Long = 0,
        val oldestConnMs: Long = 0,
        val newestConnMs: Long = 0,
        val hasRecentData: Boolean = true
    )

    fun cleanup() {
    }
}
