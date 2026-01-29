package com.kunk.singbox.service.tun

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.repository.LogRepository
import io.nekohasekai.libbox.TunOptions
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN TUN 接口管理器
 * 负责 TUN 接口的配置、创建和生命周期管理
 */
class VpnTunManager(
    private val context: Context,
    private val vpnService: VpnService
) {
    companion object {
        private const val TAG = "VpnTunManager"
    }

    @Volatile
    private var preallocatedBuilder: VpnService.Builder? = null

    val isConnecting = AtomicBoolean(false)

    // Avoid spamming logs if Builder is recreated multiple times.
    private val lastMtuLogAtMs = AtomicLong(0L)
    @Volatile private var lastLoggedMtu: Int = -1
    private val mtuLogDebounceMs: Long = 10_000L

    /**
     * 预分配 TUN Builder
     * 在收到 ACTION_START 时调用，减少 openTun 时的延迟
     */
    fun preallocateBuilder() {
        if (preallocatedBuilder != null) return
        try {
            preallocatedBuilder = vpnService.Builder()
                .setSession(context.packageName)
                .setMtu(9000)
            Log.d(TAG, "TUN builder preallocated")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to preallocate TUN builder", e)
            preallocatedBuilder = null
        }
    }

    /**
     * 获取预分配的 Builder（如果有）
     */
    fun consumePreallocatedBuilder(): VpnService.Builder? {
        return preallocatedBuilder?.also {
            preallocatedBuilder = null
            Log.d(TAG, "Using preallocated TUN builder")
        }
    }

    /**
     * 配置 TUN Builder
     * @param builder VpnService.Builder
     * @param options TunOptions from libbox
     * @param settings 应用设置
     */
    fun configureBuilder(
        builder: VpnService.Builder,
        options: TunOptions?,
        settings: AppSettings?
    ) {
        val effectiveMtu = resolveEffectiveMtu(options, settings)
        logEffectiveMtuIfNeeded(options, settings, effectiveMtu)

        builder.setSession("KunBox VPN")
            .setMtu(effectiveMtu)

        // 添加地址
        builder.addAddress("172.19.0.1", 30)
        builder.addAddress("fd00::1", 126)

        // 添加路由
        configureRoutes(builder, settings)

        // 添加 DNS
        configureDns(builder, settings)

        // 分应用配置
        configurePerAppVpn(builder, settings)

        // 保存分流设置用于热重载检测
        val appModeName = (settings?.vpnAppMode ?: VpnAppMode.ALL).name
        val allowlist = settings?.vpnAllowlist
        val blocklist = settings?.vpnBlocklist
        Log.d(
            TAG,
            "Saving per-app settings: mode=$appModeName, " +
                "allowHash=${allowlist?.hashCode() ?: 0}, blockHash=${blocklist?.hashCode() ?: 0}"
        )
        VpnStateStore.savePerAppVpnSettings(
            appMode = appModeName,
            allowlist = allowlist,
            blocklist = blocklist
        )

        VpnStateStore.saveTunSettings(
            tunStack = (settings?.tunStack ?: TunStack.MIXED).name,
            tunMtu = effectiveMtu,
            autoRoute = settings?.autoRoute ?: false,
            strictRoute = settings?.strictRoute ?: true,
            proxyPort = settings?.proxyPort ?: 2080
        )

        // 安全设置
        configureSecuritySettings(builder)

        // Android Q+ 设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            configureHttpProxy(builder, settings)
        }
    }

    private fun logEffectiveMtuIfNeeded(options: TunOptions?, settings: AppSettings?, effectiveMtu: Int) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastMtuLogAtMs.get()
        if (effectiveMtu == lastLoggedMtu && elapsed < mtuLogDebounceMs) return
        lastMtuLogAtMs.set(now)
        lastLoggedMtu = effectiveMtu

        val configuredMtu = if (options != null && options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500)
        val autoEnabled = settings?.tunMtuAuto == true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val physicalCaps = cm?.allNetworks
            ?.asSequence()
            ?.mapNotNull { cm.getNetworkCapabilities(it) }
            ?.firstOrNull {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        val caps = physicalCaps ?: cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val networkType = when {
            caps == null -> "unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }

        val msg = "INFO [VPN] Effective MTU=$effectiveMtu " +
            "(auto=$autoEnabled, configured=$configuredMtu) network=$networkType"
        Log.i(TAG, msg)
        runCatching { LogRepository.getInstance().addLog(msg) }
    }

    private fun resolveEffectiveMtu(options: TunOptions?, settings: AppSettings?): Int {
        val configuredMtu = if (options != null && options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500)
        if (settings?.tunMtuAuto != true) return configuredMtu

        val caps = getNetworkCapabilities() ?: return configuredMtu

        // Throughput-first for Wi-Fi/Ethernet; conservative for cellular.
        // QUIC-based proxies (Hysteria2/TUIC) + YouTube QUIC = double encapsulation,
        // requiring higher MTU to avoid fragmentation blackholes.
        val recommendedMtu = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1480
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1480
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1400
            else -> configuredMtu
        }

        // Auto MTU should never be more aggressive than user-configured MTU.
        return minOf(configuredMtu, recommendedMtu)
    }

    private fun getNetworkCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val physicalCaps = cm.allNetworks
            .asSequence()
            .mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        return physicalCaps ?: cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    }

    private fun configureRoutes(builder: VpnService.Builder, settings: AppSettings?) {
        val routeMode = settings?.vpnRouteMode ?: VpnRouteMode.GLOBAL
        val cidrText = settings?.vpnRouteIncludeCidrs.orEmpty()
        val cidrs = cidrText
            .split("\n", "\r", ",", ";", " ", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val usedCustomRoutes = if (routeMode == VpnRouteMode.CUSTOM) {
            var okCount = 0
            cidrs.forEach { cidr ->
                if (addCidrRoute(builder, cidr)) okCount++
            }
            okCount > 0
        } else {
            false
        }

        if (!usedCustomRoutes) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }
    }

    private fun addCidrRoute(builder: VpnService.Builder, cidr: String): Boolean {
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

    private fun configureDns(builder: VpnService.Builder, settings: AppSettings?) {
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

        dnsServers.distinct().forEach { dns ->
            try {
                builder.addDnsServer(dns)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DNS server: $dns", e)
            }
        }
    }

    private fun configurePerAppVpn(builder: VpnService.Builder, settings: AppSettings?) {
        val appMode = settings?.vpnAppMode ?: VpnAppMode.ALL
        val allowPkgs = parsePackageList(settings?.vpnAllowlist.orEmpty())
        val blockPkgs = parsePackageList(settings?.vpnBlocklist.orEmpty())
        val selfPackage = context.packageName

        try {
            when (appMode) {
                VpnAppMode.ALL -> {
                    builder.addDisallowedApplication(selfPackage)
                }
                VpnAppMode.ALLOWLIST -> {
                    if (allowPkgs.isEmpty()) {
                        Log.w(TAG, "Allowlist is empty, falling back to ALL mode")
                        builder.addDisallowedApplication(selfPackage)
                    } else {
                        var addedCount = 0
                        allowPkgs.forEach { pkg ->
                            if (pkg == selfPackage) return@forEach
                            try {
                                builder.addAllowedApplication(pkg)
                                addedCount++
                            } catch (e: PackageManager.NameNotFoundException) {
                                Log.w(TAG, "Allowed app not found: $pkg")
                            }
                        }
                        if (addedCount == 0) {
                            Log.w(TAG, "No valid apps in allowlist, falling back to ALL mode")
                            builder.addDisallowedApplication(selfPackage)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply per-app VPN settings", e)
        }
    }

    private fun configureSecuritySettings(builder: VpnService.Builder) {
        // Kill Switch: NOT calling allowBypass() means bypass disabled by default
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.i(TAG, "Kill switch enabled: NOT calling allowBypass()")
        }

        // Blocking mode: blocks network until VPN established
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                builder.setBlocking(true)
                Log.i(TAG, "Blocking mode enabled: setBlocking(true)")
            } catch (e: Exception) {
                Log.w(TAG, "setBlocking not supported on this device", e)
            }
        }
    }

    private fun configureHttpProxy(builder: VpnService.Builder, settings: AppSettings?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (settings?.appendHttpProxy == true && settings.proxyPort > 0) {
                try {
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", settings.proxyPort))
                    Log.i(TAG, "HTTP Proxy appended to VPN: 127.0.0.1:${settings.proxyPort}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set HTTP proxy for VPN", e)
                }
            }
        }
    }

    /**
     * 检查 Always-On VPN 状态
     * @return Pair<packageName, isLockdown>
     */
    fun checkAlwaysOnVpn(): Pair<String?, Boolean> {
        val alwaysOnPkg = runCatching {
            Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
        }.getOrNull() ?: runCatching {
            Settings.Global.getString(context.contentResolver, "always_on_vpn_app")
        }.getOrNull()

        val lockdownValueSecure = runCatching {
            Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown", 0)
        }.getOrDefault(0)
        val lockdownValueGlobal = runCatching {
            Settings.Global.getInt(context.contentResolver, "always_on_vpn_lockdown", 0)
        }.getOrDefault(0)
        val lockdown = lockdownValueSecure != 0 || lockdownValueGlobal != 0

        if (!alwaysOnPkg.isNullOrBlank() || lockdown) {
            Log.i(TAG, "Always-on VPN status: pkg=$alwaysOnPkg lockdown=$lockdown")
        }

        return Pair(alwaysOnPkg, lockdown)
    }

    /**
     * 检查是否有其他 VPN 活跃
     */
    fun isOtherVpnActive(connectivityManager: ConnectivityManager?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager != null) {
            return runCatching {
                @Suppress("DEPRECATION")
                connectivityManager.allNetworks.any { network ->
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }.getOrDefault(false)
        }
        return false
    }

    /**
     * 使用重试建立 TUN 接口
     * @return ParcelFileDescriptor 或 null
     */
    fun establishWithRetry(
        builder: VpnService.Builder,
        isStopping: () -> Boolean
    ): ParcelFileDescriptor? {
        val backoffMs = longArrayOf(0L, 250L, 250L, 500L, 500L, 1000L, 1000L, 2000L, 2000L, 2000L)

        for (sleepMs in backoffMs) {
            if (isStopping()) {
                return null
            }
            if (sleepMs > 0) {
                SystemClock.sleep(sleepMs)
            }

            val vpnInterface = builder.establish()
            val fd = vpnInterface?.fd ?: -1
            if (vpnInterface != null && fd >= 0) {
                return vpnInterface
            }

            try { vpnInterface?.close() } catch (_: Exception) {}
        }

        return null
    }

    /**
     * 清理预分配的 Builder
     */
    fun cleanup() {
        preallocatedBuilder = null
        isConnecting.set(false)
    }

    private fun parsePackageList(raw: String): List<String> {
        return raw
            .split("\n", "\r", ",", ";", " ", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun isNumericAddress(address: String): Boolean {
        if (address.isBlank()) return false

        // 跳过 URL 格式 (DoH/DoT)，避免 DNS 解析超时
        val hasUrlFormat = address.contains("://") || address.contains("/")
        val hasNonIpv6Colon = address.contains(":") && !isIpv6Literal(address)
        if (hasUrlFormat || hasNonIpv6Colon) {
            return false
        }

        return try {
            val addr = InetAddress.getByName(address)
            addr.hostAddress == address
        } catch (_: Exception) {
            false
        }
    }

    private fun isIpv6Literal(address: String): Boolean {
        // IPv6 地址格式: xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx
        // 或简写格式如 ::1, fe80::1 等
        // 不包含 http/https 协议前缀
        if (address.startsWith("[") || address.startsWith("::")) return true
        val colonCount = address.count { it == ':' }
        val dotCount = address.count { it == '.' }
        // IPv6 至少有 2 个冒号，且没有点（除非是 IPv4-mapped IPv6）
        return colonCount >= 2 && dotCount == 0
    }
}
