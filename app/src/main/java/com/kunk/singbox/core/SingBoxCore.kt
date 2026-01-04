package com.kunk.singbox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.repository.SettingsRepository
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.reflect.Modifier
import java.lang.reflect.Method
import java.util.Collections
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
    private val libboxMutex = kotlinx.coroutines.sync.Mutex()

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
        
        if (libboxAvailable) {
            Log.i(TAG, "Libbox initialized successfully")
            try {
                Log.i(TAG, "Libbox kernel version: ${Libbox.version()}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read Libbox.version(): ${e.message}")
            }
        } else {
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

    private fun maybeWarmupNative(url: String) {
        // No-op for official libbox without urlTest
    }

    /**
     * 使用 Libbox 原生方法进行延迟测试
     * 优先尝试调用 NekoBox 内核的 urlTest 方法，失败则回退到本地 HTTP 代理测速
     */
    private suspend fun testOutboundLatencyWithLibbox(outbound: Outbound, settings: com.kunk.singbox.model.AppSettings? = null): Long = withContext(Dispatchers.IO) {
        if (!libboxAvailable) return@withContext -1L
        
        val finalSettings = settings ?: SettingsRepository.getInstance(context).settings.first()
        val url = adjustUrlForMode(finalSettings.latencyTestUrl, finalSettings.latencyTestMethod)
        val timeoutMs = finalSettings.latencyTestTimeout
        
        // 尝试使用 NekoBox 原生 urlTest
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
            testWithLocalHttpProxy(outbound, url, fallbackUrl, timeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "Native HTTP proxy test failed: ${e.message}")
            -1L
        }
    }

    // private var discoveredUrlTestMethod: java.lang.reflect.Method? = null
    // private var discoveredMethodType: Int = 0 // 0: long, 1: URLTest object
    
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

    private suspend fun testWithLocalHttpProxy(outbound: Outbound, targetUrl: String, fallbackUrl: String? = null, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        // Mutex required because we're starting a new service instance which might conflict on global resources (e.g. bbolt DB in workingDir)
        libboxMutex.withLock {
            testWithLocalHttpProxyInternal(outbound, targetUrl, fallbackUrl, timeoutMs)
        }
    }

    private suspend fun testWithLocalHttpProxyInternal(outbound: Outbound, targetUrl: String, fallbackUrl: String? = null, timeoutMs: Int): Long {
        val port = allocateLocalPort()
        val inbound = com.kunk.singbox.model.Inbound(
            type = "mixed",
            tag = "test-in",
            listen = "127.0.0.1",
            listenPort = port
        )
        return try {
            val direct = com.kunk.singbox.model.Outbound(type = "direct", tag = "direct")

            val settings = SettingsRepository.getInstance(context).settings.first()

            val config = SingBoxConfig(
                log = com.kunk.singbox.model.LogConfig(level = "warn", timestamp = true),
                dns = com.kunk.singbox.model.DnsConfig(
                    servers = listOf(
                        com.kunk.singbox.model.DnsServer(
                            tag = "dns-bootstrap",
                            address = "223.5.5.5",
                            detour = "direct",
                            strategy = "ipv4_only"
                        ),
                        com.kunk.singbox.model.DnsServer(
                            tag = "local",
                            address = settings.localDns.ifBlank { "https://dns.alidns.com/dns-query" },
                            detour = "direct",
                            addressResolver = "dns-bootstrap"
                        ),
                        com.kunk.singbox.model.DnsServer(
                            tag = "remote",
                            address = settings.remoteDns.ifBlank { "https://dns.google/dns-query" },
                            detour = "direct",
                            addressResolver = "dns-bootstrap"
                        )
                    )
                ),
                inbounds = listOf(inbound),
                outbounds = listOf(outbound, direct),
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(
                        com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct"),
                        com.kunk.singbox.model.RouteRule(inbound = listOf("test-in"), outbound = outbound.tag)
                    ),
                    finalOutbound = "direct",
                    autoDetectInterface = true
                ),
                experimental = null
            )

            val configJson = gson.toJson(config)
            var service: BoxService? = null
            try {
                ensureLibboxSetup(context)
                val platformInterface = TestPlatformInterface(context)
                service = Libbox.newService(configJson, platformInterface)
                service.start()

                // 减少服务启动等待时间以提高测速效率
                val deadline = System.currentTimeMillis() + 500L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Socket().use { s ->
                            s.soTimeout = 100
                            s.connect(InetSocketAddress("127.0.0.1", port), 100)
                        }
                        break
                    } catch (_: Exception) {
                        delay(20)
                    }
                }

                delay(80)

                val client = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                    .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()

                fun runOnce(url: String): Long {
                    val req = Request.Builder().url(url).get().build()
                    val t0 = System.nanoTime()
                    client.newCall(req).execute().use { resp ->
                        if (resp.code >= 400) {
                            throw java.io.IOException("HTTP proxy test failed with code=${resp.code}")
                        }
                        resp.body?.close()
                    }
                    return (System.nanoTime() - t0) / 1_000_000
                }

                try {
                    try {
                        runOnce(targetUrl)
                    } catch (e: Exception) {
                        if ((e.message ?: "").contains("Connection reset", ignoreCase = true)) {
                            delay(50)
                            runOnce(targetUrl)
                        } else {
                            throw e
                        }
                    }
                } catch (e: Exception) {
                    val fb = fallbackUrl
                    if (!fb.isNullOrBlank() && fb != targetUrl) {
                        try {
                            runOnce(fb)
                        } catch (e2: Exception) {
                            Log.w(TAG, "HTTP proxy native test error: primary=${e.message}, fallback=${e2.message}")
                            -1L
                        }
                    } else {
                        Log.w(TAG, "HTTP proxy native test error: ${e.message}")
                        -1L
                    }
                }
            } finally {
                try { service?.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local HTTP proxy setup failed", e)
            -1L
        }
    }

    private suspend fun testWithLibboxStaticUrlTest(
        outbound: Outbound,
        targetUrl: String,
        timeoutMs: Int,
        method: LatencyTestMethod
    ): Long = withContext(Dispatchers.IO) {
        // 由于当前的 libbox.aar (官方版本) 不包含 NekoBox 特有的 urlTest 接口，
        // 我们跳过直接调用，直接返回 -1，让上层逻辑回退到本地 HTTP 代理测速。
        // 本地 HTTP 代理测速也是一种“原生”方式（流量经过内核），结果准确。
        return@withContext -1L
    }

    private suspend fun testWithTemporaryServiceUrlTestOnRunning(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        method: LatencyTestMethod
    ): Long = withContext(Dispatchers.IO) {
        // 尝试调用 native 方法 (如果 VPN 正在运行)
        if (VpnStateStore.getActive(context) && libboxAvailable) {
            val rtt = testWithLibboxStaticUrlTest(outbound, targetUrl, timeoutMs, method)
            if (rtt >= 0) return@withContext rtt
        }
        
        // 内核不支持或未运行，直接走 HTTP 代理测速
        testWithLocalHttpProxyInternal(outbound, targetUrl, fallbackUrl, timeoutMs)
    }

    private suspend fun testOutboundsLatencyOfflineWithTemporaryService(
        outbounds: List<Outbound>,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        method: LatencyTestMethod,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 提高离线测试并发数以加快批量测速
        val semaphore = Semaphore(permits = 6)
        coroutineScope {
            val jobs = outbounds.map { outbound ->
                async {
                    semaphore.withPermit {
                        val latency = testWithLocalHttpProxy(outbound, targetUrl, fallbackUrl, timeoutMs)
                        onResult(outbound.tag, latency)
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    /**
     * 测试单个节点的延迟
     * @param outbound 节点出站配置
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
    suspend fun testOutboundLatency(outbound: Outbound): Long = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
        val timeoutMs = settings.latencyTestTimeout

        // When VPN is running, prefer running-instance URLTest.
        // When VPN is stopped, try Libbox static URLTest first, then local HTTP proxy fallback.
        if (VpnStateStore.getActive(context)) {
            return@withContext testOutboundLatencyWithLibbox(outbound, settings)
        }

        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)

        val fallbackUrl = try {
            if (settings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                adjustUrlForMode("http://www.gstatic.com/generate_204", settings.latencyTestMethod)
            } else {
                adjustUrlForMode("https://www.gstatic.com/generate_204", settings.latencyTestMethod)
            }
        } catch (_: Exception) { url }

        val rtt = testWithTemporaryServiceUrlTestOnRunning(outbound, url, fallbackUrl, timeoutMs, settings.latencyTestMethod)
        if (rtt >= 0) {
            Log.i(TAG, "Offline URLTest RTT: ${outbound.tag} -> ${rtt} ms")
            return@withContext rtt
        }

        val fallback = testWithLocalHttpProxy(outbound, url, fallbackUrl, timeoutMs)
        Log.i(TAG, "Offline HTTP fallback: ${outbound.tag} -> ${fallback} ms")
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

        // Native-only batch test: libbox URLTest first.
        if (libboxAvailable && VpnStateStore.getActive(context)) {
            // 先做一次轻量预热，避免批量首个请求落在 link 验证/路由冷启动窗口
            try {
                val warmupOutbound = outbounds.firstOrNull()
                if (warmupOutbound != null) {
                    val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
                    maybeWarmupNative(url)
                }
            } catch (_: Exception) { }
            // 提高并发数以加快批量测速
            val semaphore = Semaphore(permits = 10)
            coroutineScope {
                val jobs = outbounds.map { outbound ->
                    async {
                        semaphore.withPermit {
                            val latency = testOutboundLatencyWithLibbox(outbound, settings)
                            onResult(outbound.tag, latency)
                        }
                    }
                }
                jobs.awaitAll()
            }
            return@withContext
        }

        // VPN is not running: create one temporary registered core and test outbounds on it.
        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
        val timeoutMs = settings.latencyTestTimeout
        val fallbackUrl = try {
            if (settings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                adjustUrlForMode("http://www.gstatic.com/generate_204", settings.latencyTestMethod)
            } else {
                adjustUrlForMode("https://www.gstatic.com/generate_204", settings.latencyTestMethod)
            }
        } catch (_: Exception) { url }
        testOutboundsLatencyOfflineWithTemporaryService(outbounds, url, fallbackUrl, timeoutMs, settings.latencyTestMethod, onResult)
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
    
    fun formatConfig(config: SingBoxConfig): String = gson.toJson(config)

    // --- Inner Classes for Platform Interface ---

    private class TestPlatformInterface(private val context: Context) : PlatformInterface {
        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private var networkCallback: ConnectivityManager.NetworkCallback? = null
        private var currentInterfaceListener: InterfaceUpdateListener? = null
        private var defaultInterfaceName: String = ""

        override fun autoDetectInterfaceControl(fd: Int) {
            // 重要：如果 VPN 正在运行，必须 protect 测速 socket，否则流量会被 VPN 拦截
            try {
                com.kunk.singbox.service.SingBoxService.instance?.protect(fd)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to protect socket fd=$fd", e)
            }
        }

        override fun openTun(options: TunOptions?): Int {
            // Should not be called as we don't provide tun inbound
            Log.w(TAG, "TestPlatformInterface: openTun called unexpected!")
            return -1
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            currentInterfaceListener = listener
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateDefaultInterface(network)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    updateDefaultInterface(network)
                }
                override fun onLost(network: Network) {
                    currentInterfaceListener?.updateDefaultInterface("", 0, false, false)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                connectivityManager.registerNetworkCallback(request, networkCallback!!)
                connectivityManager.activeNetwork?.let { updateDefaultInterface(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start interface monitor", e)
            }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            networkCallback?.let { 
                try {
                    connectivityManager.unregisterNetworkCallback(it) 
                } catch (e: Exception) {}
            }
            networkCallback = null
            currentInterfaceListener = null
        }

        private fun updateDefaultInterface(network: Network) {
            try {
                val linkProperties = connectivityManager.getLinkProperties(network)
                val interfaceName = linkProperties?.interfaceName ?: ""
                if (interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName) {
                    defaultInterfaceName = interfaceName
                    val index = try {
                        NetworkInterface.getByName(interfaceName)?.index ?: 0
                    } catch (e: Exception) { 0 }
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                    val isConstrained = false // Simple assumption
                    currentInterfaceListener?.updateDefaultInterface(interfaceName, index, isExpensive, isConstrained)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update default interface", e)
            }
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

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
        override fun useProcFS(): Boolean = false
        override fun findConnectionOwner(p0: Int, p1: String?, p2: Int, p3: String?, p4: Int): Int = 0
        override fun packageNameByUid(p0: Int): String = ""
        override fun uidByPackageName(p0: String?): Int = 0
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() {}
        override fun sendNotification(p0: io.nekohasekai.libbox.Notification?) {}
        override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
            return com.kunk.singbox.core.LocalResolverImpl
        }
        override fun systemCertificates(): StringIterator? = null
        override fun writeLog(message: String?) {
            Log.v("SingBoxCoreTest", "libbox: $message")
            message?.let {
                com.kunk.singbox.repository.LogRepository.getInstance().addLog("[Test] $it")
            }
        }
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }
    
    fun cleanup() {
    }
}
