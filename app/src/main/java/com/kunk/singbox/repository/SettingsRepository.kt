package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.model.GhProxyMirror
import com.kunk.singbox.model.AppThemeMode
import com.kunk.singbox.model.AppLanguage
import com.kunk.singbox.viewmodel.NodeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    private val gson = Gson()

    private fun parseVpnAppMode(raw: String?): VpnAppMode {
        if (raw.isNullOrBlank()) return VpnAppMode.ALL
        return VpnAppMode.entries.firstOrNull { it.name == raw } ?: VpnAppMode.ALL
    }

    private object PreferencesKeys {
        // 通用设置
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val EXCLUDE_FROM_RECENT = booleanPreferencesKey("exclude_from_recent")
        val APP_THEME = stringPreferencesKey("app_theme")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val SHOW_NOTIFICATION_SPEED = booleanPreferencesKey("show_notification_speed")
        
        // TUN/VPN 设置
        val TUN_ENABLED = booleanPreferencesKey("tun_enabled")
        val TUN_STACK = stringPreferencesKey("tun_stack")
        val TUN_MTU = intPreferencesKey("tun_mtu")
        val TUN_INTERFACE_NAME = stringPreferencesKey("tun_interface_name")
        val AUTO_ROUTE = booleanPreferencesKey("auto_route")
        val STRICT_ROUTE = booleanPreferencesKey("strict_route")
        val ENDPOINT_INDEPENDENT_NAT = booleanPreferencesKey("endpoint_independent_nat")
        val VPN_ROUTE_MODE = stringPreferencesKey("vpn_route_mode")
        val VPN_ROUTE_INCLUDE_CIDRS = stringPreferencesKey("vpn_route_include_cidrs")
        val VPN_APP_MODE = stringPreferencesKey("vpn_app_mode")
        val VPN_ALLOWLIST = stringPreferencesKey("vpn_allowlist")
        val VPN_BLOCKLIST = stringPreferencesKey("vpn_blocklist")
        
        // DNS 设置
        val LOCAL_DNS = stringPreferencesKey("local_dns")
        val REMOTE_DNS = stringPreferencesKey("remote_dns")
        val FAKE_DNS_ENABLED = booleanPreferencesKey("fake_dns_enabled")
        val FAKE_IP_RANGE = stringPreferencesKey("fake_ip_range")
        val DNS_STRATEGY = stringPreferencesKey("dns_strategy")
        val REMOTE_DNS_STRATEGY = stringPreferencesKey("remote_dns_strategy")
        val DIRECT_DNS_STRATEGY = stringPreferencesKey("direct_dns_strategy")
        val SERVER_ADDRESS_STRATEGY = stringPreferencesKey("server_address_strategy")
        val DNS_CACHE_ENABLED = booleanPreferencesKey("dns_cache_enabled")
        
        // 路由设置
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val DEFAULT_RULE = stringPreferencesKey("default_rule")
        val BLOCK_ADS = booleanPreferencesKey("block_ads")
        val BLOCK_QUIC = booleanPreferencesKey("block_quic")
        val LATENCY_TEST_METHOD = stringPreferencesKey("latency_test_method")
        val LATENCY_TEST_URL = stringPreferencesKey("latency_test_url")
        val LATENCY_TEST_TIMEOUT = intPreferencesKey("latency_test_timeout")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val GH_PROXY_MIRROR = stringPreferencesKey("gh_proxy_mirror")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        
        // 代理设置
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val ALLOW_LAN = booleanPreferencesKey("allow_lan")
        val APPEND_HTTP_PROXY = booleanPreferencesKey("append_http_proxy")
        
        // 高级路由 (JSON)
        val CUSTOM_RULES = stringPreferencesKey("custom_rules")
        val RULE_SETS = stringPreferencesKey("rule_sets")
        val APP_RULES = stringPreferencesKey("app_rules")
        val APP_GROUPS = stringPreferencesKey("app_groups")
        val DNS_MIGRATED = booleanPreferencesKey("dns_migrated")
        val NODE_FILTER = stringPreferencesKey("node_filter")
        
        // 规则集自动更新
        val RULE_SET_AUTO_UPDATE_ENABLED = booleanPreferencesKey("rule_set_auto_update_enabled")
        val RULE_SET_AUTO_UPDATE_INTERVAL = intPreferencesKey("rule_set_auto_update_interval")
        
        // 订阅更新超时
        val SUBSCRIPTION_UPDATE_TIMEOUT = intPreferencesKey("subscription_update_timeout")
    }
    
    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val rawMirror = preferences[PreferencesKeys.GH_PROXY_MIRROR]
        val selectedMirror = GhProxyMirror.entries.find { it.name == rawMirror }
            ?: GhProxyMirror.entries.find { it.name == "SAGERNET_ORIGIN" }!!
        val currentMirrorUrl = selectedMirror.url

        val customRulesJson = preferences[PreferencesKeys.CUSTOM_RULES]
        val customRules = if (customRulesJson != null) {
            try {
                val list = gson.fromJson<List<CustomRule>>(customRulesJson, object : TypeToken<List<CustomRule>>() {}.type) ?: emptyList()
                list.map { rule ->
                    if (rule.outboundMode != null) {
                        rule
                    } else {
                        val migratedMode = when (rule.outbound) {
                            com.kunk.singbox.model.OutboundTag.DIRECT -> com.kunk.singbox.model.RuleSetOutboundMode.DIRECT
                            com.kunk.singbox.model.OutboundTag.BLOCK -> com.kunk.singbox.model.RuleSetOutboundMode.BLOCK
                            com.kunk.singbox.model.OutboundTag.PROXY -> com.kunk.singbox.model.RuleSetOutboundMode.PROXY
                        }
                        rule.copy(outboundMode = migratedMode)
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }

        } else {
            emptyList()
        }

        val ruleSetsJson = preferences[PreferencesKeys.RULE_SETS]
        val ruleSets = if (ruleSetsJson != null) {
            try {
                // Log only if it's a significant read or in verbose mode
                // Log.v(TAG, "Loading rule sets from JSON (length=${ruleSetsJson.length})")
                val list = gson.fromJson<List<RuleSet>>(ruleSetsJson, object : TypeToken<List<RuleSet>>() {}.type) ?: emptyList()
                
                // 自动修复并去重
                val migratedList = list.map { ruleSet ->
                    var updatedUrl = ruleSet.url
                    var updatedTag = ruleSet.tag
                    
                    // 1. 强制重命名旧的广告规则集标识
                    if (updatedTag.equals("geosite-ads", ignoreCase = true)) {
                        updatedTag = "geosite-category-ads-all"
                    }
                    
                    // 2. 修复错误的广告规则集 URL
                    if (updatedUrl.contains("geosite-ads.srs")) {
                        updatedUrl = updatedUrl.replace("geosite-ads.srs", "geosite-category-ads-all.srs")
                    }
                    
                    // 3. 统一使用镜像加速
                    val rawPrefix = "https://raw.githubusercontent.com/"
                    val cdnPrefix = "https://cdn.jsdelivr.net/gh/"
                    
                    // 先还原到原始 URL (raw.githubusercontent.com)
                    var rawUrl = updatedUrl
                    
                    // 移除旧镜像
                    val oldMirrors = listOf(
                        "https://ghp.ci/",
                        "https://mirror.ghproxy.com/",
                        "https://ghproxy.com/",
                        "https://ghproxy.net/",
                        "https://ghfast.top/",
                        "https://gh-proxy.com/",
                        cdnPrefix
                    )
                    
                    for (mirror in oldMirrors) {
                        if (rawUrl.startsWith(mirror)) {
                            // 对于 jsDelivr，URL 结构不同，需要特殊处理
                            if (mirror == cdnPrefix) {
                                // https://cdn.jsdelivr.net/gh/user/repo@version/path -> https://raw.githubusercontent.com/user/repo/version/path
                                // 这里简化处理，假设是 @rule-set 这种形式
                                rawUrl = rawUrl.replace(cdnPrefix, rawPrefix).replace("@", "/")
                            } else {
                                rawUrl = rawUrl.replace(mirror, rawPrefix)
                            }
                        }
                    }

                    // 应用当前选择的镜像
                    if (currentMirrorUrl.contains("cdn.jsdelivr.net")) {
                        // 转换为 jsDelivr 格式
                        // https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs
                        // -> https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs
                        if (rawUrl.startsWith(rawPrefix)) {
                            val path = rawUrl.removePrefix(rawPrefix)
                            val parts = path.split("/", limit = 4)
                            if (parts.size >= 4) {
                                val user = parts[0]
                                val repo = parts[1]
                                val branch = parts[2]
                                val filePath = parts[3]
                                updatedUrl = "$cdnPrefix$user/$repo@$branch/$filePath"
                            }
                        }
                    } else if (currentMirrorUrl != rawPrefix) {
                         // 其他镜像通常直接拼接
                         if (rawUrl.startsWith(rawPrefix)) {
                             updatedUrl = rawUrl.replace(rawPrefix, currentMirrorUrl)
                         }
                    } else {
                        // 使用官方源 (raw.githubusercontent.com)
                        updatedUrl = rawUrl
                    }

                    if (updatedUrl != ruleSet.url || updatedTag != ruleSet.tag) {
                        ruleSet.copy(tag = updatedTag, url = updatedUrl)
                    } else {
                        ruleSet
                    }
                }
                
                // 去重：如果存在相同 tag 的规则集，保留最后一个（通常是较新的或已迁移的）
                migratedList.distinctBy { it.tag }
            } catch (e: Exception) {
                Log.e("SettingsRepository", "Failed to parse rule sets JSON", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        val appRulesJson = preferences[PreferencesKeys.APP_RULES]
        val appRules = if (appRulesJson != null) {
            try {
                gson.fromJson<List<AppRule>>(appRulesJson, object : TypeToken<List<AppRule>>() {}.type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val appGroupsJson = preferences[PreferencesKeys.APP_GROUPS]
        val appGroups = if (appGroupsJson != null) {
            try {
                gson.fromJson<List<AppGroup>>(appGroupsJson, object : TypeToken<List<AppGroup>>() {}.type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        AppSettings(
            // 通用设置
            autoConnect = preferences[PreferencesKeys.AUTO_CONNECT] ?: false,
            autoReconnect = preferences[PreferencesKeys.AUTO_RECONNECT] ?: true,
            excludeFromRecent = preferences[PreferencesKeys.EXCLUDE_FROM_RECENT] ?: false,
            appTheme = runCatching { AppThemeMode.valueOf(preferences[PreferencesKeys.APP_THEME] ?: "") }.getOrDefault(AppThemeMode.SYSTEM),
            appLanguage = runCatching { AppLanguage.valueOf(preferences[PreferencesKeys.APP_LANGUAGE] ?: "") }.getOrDefault(AppLanguage.SYSTEM),
            showNotificationSpeed = preferences[PreferencesKeys.SHOW_NOTIFICATION_SPEED] ?: true,
            
            // TUN/VPN 设置
            tunEnabled = preferences[PreferencesKeys.TUN_ENABLED] ?: true,
            tunStack = runCatching { TunStack.valueOf(preferences[PreferencesKeys.TUN_STACK] ?: "") }.getOrDefault(TunStack.SYSTEM),
            tunMtu = preferences[PreferencesKeys.TUN_MTU] ?: 1280,
            tunInterfaceName = preferences[PreferencesKeys.TUN_INTERFACE_NAME] ?: "tun0",
            autoRoute = preferences[PreferencesKeys.AUTO_ROUTE] ?: false,
            strictRoute = preferences[PreferencesKeys.STRICT_ROUTE] ?: true,
            endpointIndependentNat = preferences[PreferencesKeys.ENDPOINT_INDEPENDENT_NAT] ?: false,
            vpnRouteMode = runCatching { VpnRouteMode.valueOf(preferences[PreferencesKeys.VPN_ROUTE_MODE] ?: "") }.getOrDefault(VpnRouteMode.GLOBAL),
            vpnRouteIncludeCidrs = preferences[PreferencesKeys.VPN_ROUTE_INCLUDE_CIDRS] ?: "",
            vpnAppMode = parseVpnAppMode(preferences[PreferencesKeys.VPN_APP_MODE]),
            vpnAllowlist = preferences[PreferencesKeys.VPN_ALLOWLIST] ?: "",
            vpnBlocklist = preferences[PreferencesKeys.VPN_BLOCKLIST] ?: "",
            
            // DNS 设置
            localDns = preferences[PreferencesKeys.LOCAL_DNS] ?: "https://dns.alidns.com/dns-query",
            remoteDns = preferences[PreferencesKeys.REMOTE_DNS] ?: "https://dns.google/dns-query",
            fakeDnsEnabled = preferences[PreferencesKeys.FAKE_DNS_ENABLED] ?: true,
            fakeIpRange = preferences[PreferencesKeys.FAKE_IP_RANGE] ?: "198.18.0.0/15",
            dnsStrategy = runCatching { DnsStrategy.valueOf(preferences[PreferencesKeys.DNS_STRATEGY] ?: "") }.getOrDefault(DnsStrategy.PREFER_IPV4),
            remoteDnsStrategy = runCatching { DnsStrategy.valueOf(preferences[PreferencesKeys.REMOTE_DNS_STRATEGY] ?: "") }.getOrDefault(DnsStrategy.AUTO),
            directDnsStrategy = runCatching { DnsStrategy.valueOf(preferences[PreferencesKeys.DIRECT_DNS_STRATEGY] ?: "") }.getOrDefault(DnsStrategy.AUTO),
            serverAddressStrategy = runCatching { DnsStrategy.valueOf(preferences[PreferencesKeys.SERVER_ADDRESS_STRATEGY] ?: "") }.getOrDefault(DnsStrategy.AUTO),
            dnsCacheEnabled = preferences[PreferencesKeys.DNS_CACHE_ENABLED] ?: true,
            
            // 路由设置
            routingMode = runCatching { RoutingMode.valueOf(preferences[PreferencesKeys.ROUTING_MODE] ?: "") }.getOrDefault(RoutingMode.RULE),
            defaultRule = runCatching { DefaultRule.valueOf(preferences[PreferencesKeys.DEFAULT_RULE] ?: "") }.getOrDefault(DefaultRule.PROXY),
            blockAds = preferences[PreferencesKeys.BLOCK_ADS] ?: true,
            blockQuic = preferences[PreferencesKeys.BLOCK_QUIC] ?: true,
            debugLoggingEnabled = preferences[PreferencesKeys.DEBUG_LOGGING_ENABLED] ?: false,
            latencyTestMethod = LatencyTestMethod.valueOf(preferences[PreferencesKeys.LATENCY_TEST_METHOD] ?: LatencyTestMethod.REAL_RTT.name),
            latencyTestUrl = preferences[PreferencesKeys.LATENCY_TEST_URL] ?: "https://cp.cloudflare.com/generate_204",
            latencyTestTimeout = preferences[PreferencesKeys.LATENCY_TEST_TIMEOUT] ?: 3000,
            bypassLan = preferences[PreferencesKeys.BYPASS_LAN] ?: true,
            
            // 镜像设置
            ghProxyMirror = selectedMirror,
            
            // 代理设置
            proxyPort = preferences[PreferencesKeys.PROXY_PORT] ?: 20808,
            allowLan = preferences[PreferencesKeys.ALLOW_LAN] ?: false,
            appendHttpProxy = preferences[PreferencesKeys.APPEND_HTTP_PROXY] ?: false,
            
            // 高级路由
            customRules = customRules,
            ruleSets = ruleSets,
            appRules = appRules,
            appGroups = appGroups,
            
            // 规则集自动更新
            ruleSetAutoUpdateEnabled = preferences[PreferencesKeys.RULE_SET_AUTO_UPDATE_ENABLED] ?: false,
            ruleSetAutoUpdateInterval = preferences[PreferencesKeys.RULE_SET_AUTO_UPDATE_INTERVAL] ?: 60,
            
            // 订阅更新超时
            subscriptionUpdateTimeout = preferences[PreferencesKeys.SUBSCRIPTION_UPDATE_TIMEOUT] ?: 30
        )
    }.flowOn(Dispatchers.Default)
    
    // 通用设置
    suspend fun setAutoConnect(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_CONNECT] = value }
    }
    
    suspend fun setAutoReconnect(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_RECONNECT] = value }
    }
    
    suspend fun setExcludeFromRecent(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.EXCLUDE_FROM_RECENT] = value }
    }

    suspend fun setAppTheme(value: com.kunk.singbox.model.AppThemeMode) {
        context.dataStore.edit { it[PreferencesKeys.APP_THEME] = value.name }
    }
    
    suspend fun setAppLanguage(value: AppLanguage) {
        context.dataStore.edit { it[PreferencesKeys.APP_LANGUAGE] = value.name }
    }
    
    suspend fun setShowNotificationSpeed(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SHOW_NOTIFICATION_SPEED] = value }
    }
    
    // TUN/VPN 设置
    suspend fun setTunEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.TUN_ENABLED] = value }
        notifyRestartRequired()
    }
    
    suspend fun setTunStack(value: TunStack) {
        context.dataStore.edit { it[PreferencesKeys.TUN_STACK] = value.name }
        notifyRestartRequired()
    }
    
    suspend fun setTunMtu(value: Int) {
        context.dataStore.edit { it[PreferencesKeys.TUN_MTU] = value }
        notifyRestartRequired()
    }
    
    suspend fun setTunInterfaceName(value: String) {
        context.dataStore.edit { it[PreferencesKeys.TUN_INTERFACE_NAME] = value }
        notifyRestartRequired()
    }
    
    suspend fun setAutoRoute(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_ROUTE] = value }
        notifyRestartRequired()
    }
    
    suspend fun setStrictRoute(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.STRICT_ROUTE] = value }
        notifyRestartRequired()
    }

    suspend fun setEndpointIndependentNat(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ENDPOINT_INDEPENDENT_NAT] = value }
        notifyRestartRequired()
    }

    suspend fun setVpnRouteMode(value: VpnRouteMode) {
        context.dataStore.edit { it[PreferencesKeys.VPN_ROUTE_MODE] = value.name }
        notifyRestartRequired()
    }

    suspend fun setVpnRouteIncludeCidrs(value: String) {
        context.dataStore.edit { it[PreferencesKeys.VPN_ROUTE_INCLUDE_CIDRS] = value }
        notifyRestartRequired()
    }

    suspend fun setVpnAppMode(value: VpnAppMode) {
        context.dataStore.edit { it[PreferencesKeys.VPN_APP_MODE] = value.name }
        notifyRestartRequired()
    }

    suspend fun setVpnAllowlist(value: String) {
        context.dataStore.edit { it[PreferencesKeys.VPN_ALLOWLIST] = value }
        notifyRestartRequired()
    }

    suspend fun setVpnBlocklist(value: String) {
        context.dataStore.edit { it[PreferencesKeys.VPN_BLOCKLIST] = value }
        notifyRestartRequired()
    }
    
    // DNS 设置
    suspend fun setLocalDns(value: String) {
        context.dataStore.edit { it[PreferencesKeys.LOCAL_DNS] = value }
        notifyRestartRequired()
    }
    
    suspend fun setRemoteDns(value: String) {
        context.dataStore.edit { it[PreferencesKeys.REMOTE_DNS] = value }
        notifyRestartRequired()
    }
    
    suspend fun setFakeDnsEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.FAKE_DNS_ENABLED] = value }
        notifyRestartRequired()
    }
    
    suspend fun setFakeIpRange(value: String) {
        context.dataStore.edit { it[PreferencesKeys.FAKE_IP_RANGE] = value }
        notifyRestartRequired()
    }
    
    suspend fun setDnsStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.DNS_STRATEGY] = value.name }
        notifyRestartRequired()
    }

    suspend fun setRemoteDnsStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.REMOTE_DNS_STRATEGY] = value.name }
        notifyRestartRequired()
    }

    suspend fun setDirectDnsStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.DIRECT_DNS_STRATEGY] = value.name }
        notifyRestartRequired()
    }

    suspend fun setServerAddressStrategy(value: DnsStrategy) {
        context.dataStore.edit { it[PreferencesKeys.SERVER_ADDRESS_STRATEGY] = value.name }
        notifyRestartRequired()
    }
    
    suspend fun setDnsCacheEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DNS_CACHE_ENABLED] = value }
        notifyRestartRequired()
    }
    
    // 路由设置
    suspend fun setRoutingMode(value: RoutingMode, notifyRestartRequired: Boolean = true) {
        context.dataStore.edit { it[PreferencesKeys.ROUTING_MODE] = value.name }
        if (notifyRestartRequired) {
            notifyRestartRequired()
        }
    }
    
    suspend fun setDefaultRule(value: DefaultRule) {
        context.dataStore.edit { it[PreferencesKeys.DEFAULT_RULE] = value.name }
        notifyRestartRequired()
    }
    
    suspend fun setBlockAds(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BLOCK_ADS] = value }
        notifyRestartRequired()
    }
    
    suspend fun setBlockQuic(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BLOCK_QUIC] = value }
        notifyRestartRequired()
    }

    suspend fun setDebugLoggingEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DEBUG_LOGGING_ENABLED] = value }
        notifyRestartRequired()
    }
    
    suspend fun setLatencyTestMethod(value: LatencyTestMethod) {
        context.dataStore.edit { it[PreferencesKeys.LATENCY_TEST_METHOD] = value.name }
    }
    
    suspend fun setLatencyTestUrl(value: String) {
        context.dataStore.edit { it[PreferencesKeys.LATENCY_TEST_URL] = value }
    }
    
    suspend fun setLatencyTestTimeout(value: Int) {
        context.dataStore.edit { it[PreferencesKeys.LATENCY_TEST_TIMEOUT] = value }
    }
    
    suspend fun setBypassLan(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BYPASS_LAN] = value }
        notifyRestartRequired()
    }
    
    suspend fun setGhProxyMirror(value: GhProxyMirror) {
        context.dataStore.edit { it[PreferencesKeys.GH_PROXY_MIRROR] = value.name }
        notifyRestartRequired()
    }
    
    suspend fun setProxyPort(value: Int) {
        context.dataStore.edit { it[PreferencesKeys.PROXY_PORT] = value }
        notifyRestartRequired()
    }

    suspend fun setAllowLan(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ALLOW_LAN] = value }
        notifyRestartRequired()
    }

    suspend fun setAppendHttpProxy(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.APPEND_HTTP_PROXY] = value }
        notifyRestartRequired()
    }
    
    suspend fun setCustomRules(value: List<CustomRule>) {
        context.dataStore.edit { it[PreferencesKeys.CUSTOM_RULES] = gson.toJson(value) }
        notifyRestartRequired()
    }

    suspend fun setRuleSets(value: List<RuleSet>, notify: Boolean = true) {
        context.dataStore.edit { it[PreferencesKeys.RULE_SETS] = gson.toJson(value) }
        if (notify) {
            notifyRestartRequired()
        }
    }

    suspend fun getRuleSets(): List<RuleSet> {
        return settings.first().ruleSets
    }

    suspend fun setAppRules(value: List<AppRule>) {
        context.dataStore.edit { it[PreferencesKeys.APP_RULES] = gson.toJson(value) }
        notifyRestartRequired()
    }

    suspend fun setAppGroups(value: List<AppGroup>) {
        context.dataStore.edit { it[PreferencesKeys.APP_GROUPS] = gson.toJson(value) }
        notifyRestartRequired()
    }
    
    suspend fun setRuleSetAutoUpdateEnabled(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.RULE_SET_AUTO_UPDATE_ENABLED] = value }
    }
    
    suspend fun setRuleSetAutoUpdateInterval(value: Int) {
        context.dataStore.edit { it[PreferencesKeys.RULE_SET_AUTO_UPDATE_INTERVAL] = value }
    }
    
    suspend fun setSubscriptionUpdateTimeout(value: Int) {
        context.dataStore.edit { it[PreferencesKeys.SUBSCRIPTION_UPDATE_TIMEOUT] = value }
    }
    
    suspend fun setNodeFilter(value: NodeFilter) {
        context.dataStore.edit { it[PreferencesKeys.NODE_FILTER] = gson.toJson(value) }
    }

    suspend fun getNodeFilter(): NodeFilter {
        return context.dataStore.data.map { preferences ->
            val json = preferences[PreferencesKeys.NODE_FILTER]
            if (json != null) {
                try {
                    gson.fromJson(json, NodeFilter::class.java) ?: NodeFilter()
                } catch (e: Exception) {
                    NodeFilter()
                }
            } else {
                NodeFilter()
            }
        }.first()
    }

    suspend fun checkAndMigrateRuleSets() {
        try {
            // Legacy migration logic removed as we now use valueOf with runCatching
            val preferences = context.dataStore.data.first()

            val currentSettings = settings.first()
            val isDnsMigrated = preferences[PreferencesKeys.DNS_MIGRATED] ?: false
            
            // 自动迁移: 优化 TUN MTU (1500 -> 1280)
            if (currentSettings.tunMtu == 1500) {
                Log.i("SettingsRepository", "Migrating TUN MTU from 1500 to 1280")
                setTunMtu(1280)
            }
            
            if (!isDnsMigrated) {
                // 自动迁移: 优化本地 DNS (8.8.8.8/223.5.5.5 -> AliDNS DoH)
                if (currentSettings.localDns == "8.8.8.8" || currentSettings.localDns == "223.5.5.5") {
                    Log.i("SettingsRepository", "Migrating Local DNS to AliDNS DoH")
                    setLocalDns("https://dns.alidns.com/dns-query")
                }
                
                // 自动迁移: 优化远程 DNS (1.1.1.1 -> Google DoH)
                if (currentSettings.remoteDns == "1.1.1.1") {
                    Log.i("SettingsRepository", "Migrating Remote DNS to Google DoH")
                    setRemoteDns("https://dns.google/dns-query")
                }
                
                // 标记已迁移，防止后续用户手动改回 IP 后再次被覆盖
                context.dataStore.edit { it[PreferencesKeys.DNS_MIGRATED] = true }
            }

            val originalRuleSets = currentSettings.ruleSets
            val currentMirrorUrl = currentSettings.ghProxyMirror.url
            val migratedRuleSets = originalRuleSets.map { ruleSet ->
                var updatedUrl = ruleSet.url
                var updatedTag = ruleSet.tag
                
                if (updatedTag.equals("geosite-ads", ignoreCase = true)) {
                    updatedTag = "geosite-category-ads-all"
                }
                
                if (updatedUrl.contains("geosite-ads.srs")) {
                    updatedUrl = updatedUrl.replace("geosite-ads.srs", "geosite-category-ads-all.srs")
                }
                
                val rawPrefix = "https://raw.githubusercontent.com/"
                val cdnPrefix = "https://cdn.jsdelivr.net/gh/"
                
                // 先还原到原始 URL
                var rawUrl = updatedUrl
                
                // 1. 如果是 jsDelivr 格式，还原为 raw 格式
                if (rawUrl.startsWith(cdnPrefix)) {
                     // https://cdn.jsdelivr.net/gh/user/repo@branch/path -> user/repo@branch/path
                     val path = rawUrl.removePrefix(cdnPrefix)
                     val parts = path.split("@", limit = 2)
                     if (parts.size == 2) {
                         val userRepo = parts[0]
                         val branchPath = parts[1]
                         rawUrl = "$rawPrefix$userRepo/$branchPath"
                     }
                } else {
                    // 2. 如果是其他前缀代理，移除前缀
                    val oldMirrors = listOf(
                        "https://ghp.ci/",
                        "https://mirror.ghproxy.com/",
                        "https://ghproxy.com/",
                        "https://ghproxy.net/",
                        "https://ghfast.top/",
                        "https://gh-proxy.com/"
                    )
                    
                    for (mirror in oldMirrors) {
                        if (rawUrl.startsWith(mirror)) {
                            rawUrl = rawUrl.replace(mirror, rawPrefix)
                        }
                    }
                    // 3. 处理已有的 raw 链接被代理的情况
                    if (rawUrl.contains("raw.githubusercontent.com") && !rawUrl.startsWith(rawPrefix)) {
                        val path = rawUrl.substringAfter("raw.githubusercontent.com/")
                        rawUrl = rawPrefix + path
                    }
                }

                // 应用当前选择的镜像
                if (currentMirrorUrl.contains("cdn.jsdelivr.net")) {
                    if (rawUrl.startsWith(rawPrefix)) {
                        val path = rawUrl.removePrefix(rawPrefix)
                        val parts = path.split("/", limit = 4)
                        if (parts.size >= 4) {
                            val user = parts[0]
                            val repo = parts[1]
                            val branch = parts[2]
                            val filePath = parts[3]
                            updatedUrl = "$cdnPrefix$user/$repo@$branch/$filePath"
                        }
                    }
                } else if (currentMirrorUrl != rawPrefix) {
                     if (rawUrl.startsWith(rawPrefix)) {
                         updatedUrl = rawUrl.replace(rawPrefix, currentMirrorUrl)
                     }
                } else {
                    updatedUrl = rawUrl
                }

                if (updatedUrl != ruleSet.url || updatedTag != ruleSet.tag) {
                    ruleSet.copy(tag = updatedTag, url = updatedUrl)
                } else {
                    ruleSet
                }
            }.distinctBy { it.tag }

            if (migratedRuleSets != originalRuleSets) {
                Log.i("SettingsRepository", "Force saving migrated rule sets to DataStore")
                setRuleSets(migratedRuleSets)
            }
        } catch (e: Exception) {
            Log.e("SettingsRepository", "Error during force migration", e)
        }
    }

    private fun notifyRestartRequired() {
        _restartRequiredEvents.tryEmit(Unit)
    }

    companion object {
        private val _restartRequiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val restartRequiredEvents: SharedFlow<Unit> = _restartRequiredEvents.asSharedFlow()

        @Volatile
        private var INSTANCE: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
