package com.kunk.singbox.model

import com.google.gson.annotations.SerializedName

/**
 * 所有应用设置的数据模型
 */
data class AppSettings(
    // 通用设置
    @SerializedName("autoConnect") val autoConnect: Boolean = false,
    @SerializedName("autoReconnect") val autoReconnect: Boolean = true,
    @SerializedName("excludeFromRecent") val excludeFromRecent: Boolean = false,
    
    // TUN/VPN 设置
    @SerializedName("tunEnabled") val tunEnabled: Boolean = true,
    @SerializedName("tunStack") val tunStack: TunStack = TunStack.GVISOR,
    @SerializedName("tunMtu") val tunMtu: Int = 1500,
    @SerializedName("tunInterfaceName") val tunInterfaceName: String = "tun0",
    @SerializedName("autoRoute") val autoRoute: Boolean = true,
    @SerializedName("strictRoute") val strictRoute: Boolean = true,
    
    // DNS 设置
    @SerializedName("localDns") val localDns: String = "8.8.8.8",
    @SerializedName("remoteDns") val remoteDns: String = "1.1.1.1",
    @SerializedName("fakeDnsEnabled") val fakeDnsEnabled: Boolean = true,
    @SerializedName("fakeIpRange") val fakeIpRange: String = "198.18.0.0/15",
    @SerializedName("dnsStrategy") val dnsStrategy: DnsStrategy = DnsStrategy.PREFER_IPV4,
    @SerializedName("dnsCacheEnabled") val dnsCacheEnabled: Boolean = true,
    
    // 路由设置
    @SerializedName("routingMode") val routingMode: RoutingMode = RoutingMode.RULE,
    @SerializedName("defaultRule") val defaultRule: DefaultRule = DefaultRule.DIRECT,
    @SerializedName("blockAds") val blockAds: Boolean = true,
    @SerializedName("bypassLan") val bypassLan: Boolean = true,
    
    // 高级路由
    @SerializedName("customRules") val customRules: List<CustomRule> = emptyList(),
    @SerializedName("ruleSets") val ruleSets: List<RuleSet> = emptyList(),
    @SerializedName("appRules") val appRules: List<AppRule> = emptyList(),
    @SerializedName("appGroups") val appGroups: List<AppGroup> = emptyList()
)

enum class TunStack(val displayName: String) {
    @SerializedName("SYSTEM") SYSTEM("System"),
    @SerializedName("GVISOR") GVISOR("gVisor"),
    @SerializedName("MIXED") MIXED("Mixed");
    
    companion object {
        fun fromDisplayName(name: String): TunStack {
            return entries.find { it.displayName == name } ?: GVISOR
        }
    }
}

enum class DnsStrategy(val displayName: String) {
    @SerializedName("PREFER_IPV4") PREFER_IPV4("优先 IPv4"),
    @SerializedName("PREFER_IPV6") PREFER_IPV6("优先 IPv6"),
    @SerializedName("ONLY_IPV4") ONLY_IPV4("仅 IPv4"),
    @SerializedName("ONLY_IPV6") ONLY_IPV6("仅 IPv6");
    
    companion object {
        fun fromDisplayName(name: String): DnsStrategy {
            return entries.find { it.displayName == name } ?: PREFER_IPV4
        }
    }
}

enum class RoutingMode(val displayName: String) {
    @SerializedName("RULE") RULE("规则模式"),
    @SerializedName("GLOBAL_PROXY") GLOBAL_PROXY("全局代理"),
    @SerializedName("GLOBAL_DIRECT") GLOBAL_DIRECT("全局直连");
    
    companion object {
        fun fromDisplayName(name: String): RoutingMode {
            return entries.find { it.displayName == name } ?: RULE
        }
    }
}

enum class DefaultRule(val displayName: String) {
    @SerializedName("DIRECT") DIRECT("直连"),
    @SerializedName("PROXY") PROXY("代理"),
    @SerializedName("BLOCK") BLOCK("拦截");
    
    companion object {
        fun fromDisplayName(name: String): DefaultRule {
            return entries.find { it.displayName == name } ?: DIRECT
        }
    }
}
