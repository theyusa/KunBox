package com.kunk.singbox.utils.parser

import android.util.Log
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig

/**
 * 订阅转换引擎接口
 */
interface SubscriptionParser {
    /**
     * 判断是否能解析该内容
     */
    fun canParse(content: String): Boolean

    /**
     * 解析内容并返回 SingBoxConfig
     */
    fun parse(content: String): SingBoxConfig?
}

/**
 * 订阅解析管理器
 */
class SubscriptionManager(private val parsers: List<SubscriptionParser>) {
    
    companion object {
        private const val TAG = "SubscriptionManager"
        
        // 协议缩写映射
        private val PROTOCOL_SHORT_NAMES = mapOf(
            "shadowsocks" to "SS",
            "vmess" to "VMess",
            "vless" to "VLESS",
            "trojan" to "Trojan",
            "hysteria2" to "Hy2",
            "hysteria" to "Hy",
            "tuic" to "TUIC",
            "wireguard" to "WG",
            "ssh" to "SSH",
            "anytls" to "AnyTLS"
        )
    }

    /**
     * 解析订阅内容
     */
    fun parse(content: String): SingBoxConfig? {
        for (parser in parsers) {
            if (parser.canParse(content)) {
                try {
                    val config = parser.parse(content)
                    if (config != null && !config.outbounds.isNullOrEmpty()) {
                        return applyNamingRules(config)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parser ${parser.javaClass.simpleName} failed", e)
                }
            }
        }
        return null
    }

    /**
     * 应用命名规范: 🇨🇳中国香港1M-VLESS
     */
    private fun applyNamingRules(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: return config
        val updatedOutbounds = outbounds.map { outbound ->
            if (isProxyOutbound(outbound)) {
                val shortProtocol = PROTOCOL_SHORT_NAMES[outbound.type] ?: outbound.type.uppercase()
                
                // 1. 提取当前名称
                val originalName = outbound.tag
                
                // 2. 检测国旗 (如果已有国旗，暂时保留，后续可能调整位置)
                val flag = detectRegionFlag(originalName)
                
                // 3. 清理名称：移除已有的协议后缀、移除可能的重复国旗
                var cleanName = originalName
                    .replace(Regex("-\\w+$"), "") // 移除结尾的 -协议 (如 -VLESS)
                    .replace(Regex("\\s-\\s\\w+$"), "") // 移除结尾的 " - 协议"
                
                // 如果检测到的国旗已经存在于名称中，尝试移除它，以便重新格式化
                if (flag != "🌐" && cleanName.contains(flag)) {
                     cleanName = cleanName.replace(flag, "").trim()
                }
                
                // 移除开头可能存在的其他国旗表情（可选，视需求而定，这里假设只保留我们检测到的或原有的一个）
                // cleanName = cleanName.replace(Regex("^[\\uD83C\\uDDE6-\\uD83C\\uDDFF]{2}"), "").trim()

                // 4. 组装新名称: Flag + CleanName + "-" + Protocol
                val newName = "$flag$cleanName-$shortProtocol"
                
                outbound.copy(tag = newName)
            } else {
                outbound
            }
        }
        return config.copy(outbounds = updatedOutbounds)
    }

    private fun isProxyOutbound(outbound: Outbound): Boolean {
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls"
        )
        return outbound.type in proxyTypes
    }
    
    /**
     * 根据节点名称检测地区标志
     * 使用词边界匹配，避免 "us" 匹配 "music" 等误报
     */
    private fun detectRegionFlag(name: String): String {
        val lowerName = name.lowercase()
        
        fun matchWord(vararg words: String): Boolean {
            return words.any { word ->
                val regex = Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)")
                regex.containsMatchIn(lowerName)
            }
        }
        
        return when {
            lowerName.contains("香港") || matchWord("hk") || lowerName.contains("hong kong") -> "🇭🇰"
            lowerName.contains("台湾") || matchWord("tw") || lowerName.contains("taiwan") -> "🇹🇼"
            lowerName.contains("日本") || matchWord("jp") || lowerName.contains("japan") || lowerName.contains("tokyo") -> "🇯🇵"
            lowerName.contains("新加坡") || matchWord("sg") || lowerName.contains("singapore") -> "🇸🇬"
            lowerName.contains("美国") || matchWord("us", "usa") || lowerName.contains("united states") || lowerName.contains("america") -> "🇺🇸"
            lowerName.contains("韩国") || matchWord("kr") || lowerName.contains("korea") -> "🇰🇷"
            lowerName.contains("英国") || matchWord("uk", "gb") || lowerName.contains("britain") || lowerName.contains("england") -> "🇬🇧"
            lowerName.contains("德国") || matchWord("de") || lowerName.contains("germany") -> "🇩🇪"
            lowerName.contains("法国") || matchWord("fr") || lowerName.contains("france") -> "🇫🇷"
            lowerName.contains("加拿大") || matchWord("ca") || lowerName.contains("canada") -> "🇨🇦"
            lowerName.contains("澳大利亚") || matchWord("au") || lowerName.contains("australia") -> "🇦🇺"
            lowerName.contains("俄罗斯") || matchWord("ru") || lowerName.contains("russia") -> "🇷🇺"
            lowerName.contains("印度") || matchWord("in") || lowerName.contains("india") -> "🇮🇳"
            lowerName.contains("巴西") || matchWord("br") || lowerName.contains("brazil") -> "🇧🇷"
            lowerName.contains("荷兰") || matchWord("nl") || lowerName.contains("netherlands") -> "🇳🇱"
            lowerName.contains("土耳其") || matchWord("tr") || lowerName.contains("turkey") -> "🇹🇷"
            lowerName.contains("阿根廷") || matchWord("ar") || lowerName.contains("argentina") -> "🇦🇷"
            lowerName.contains("马来西亚") || matchWord("my") || lowerName.contains("malaysia") -> "🇲🇾"
            lowerName.contains("泰国") || matchWord("th") || lowerName.contains("thailand") -> "🇹🇭"
            lowerName.contains("越南") || matchWord("vn") || lowerName.contains("vietnam") -> "🇻🇳"
            lowerName.contains("菲律宾") || matchWord("ph") || lowerName.contains("philippines") -> "🇵🇭"
            lowerName.contains("印尼") || matchWord("id") || lowerName.contains("indonesia") -> "🇮🇩"
            else -> "🌐"
        }
    }
}
