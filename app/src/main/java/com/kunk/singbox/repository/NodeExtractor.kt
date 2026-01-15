package com.kunk.singbox.repository

import android.content.Context
import com.kunk.singbox.R
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 节点提取器 - 从配置中提取代理节点
 *
 * 设计原则: 只提取实际的代理节点 (ss/vmess/vless/trojan 等)
 * 忽略 selector/urltest 等节点组
 */
object NodeExtractor {

    private const val PARALLEL_CONCURRENCY = 8

    // 支持的代理类型
    private val PROXY_TYPES = setOf(
        "shadowsocks", "vmess", "vless", "trojan",
        "hysteria", "hysteria2", "tuic", "wireguard",
        "shadowtls", "ssh", "anytls", "http", "socks"
    )

    // 预编译的地区检测规则
    private data class RegionRule(
        val flag: String,
        val chineseKeywords: List<String>,
        val englishKeywords: List<String>,
        val wordBoundaryKeywords: List<String>
    )

    private val REGION_RULES = listOf(
        RegionRule("🇭🇰", listOf("香港"), listOf("hong kong"), listOf("hk")),
        RegionRule("🇹🇼", listOf("台湾"), listOf("taiwan"), listOf("tw")),
        RegionRule("🇯🇵", listOf("日本"), listOf("japan", "tokyo"), listOf("jp")),
        RegionRule("🇸🇬", listOf("新加坡"), listOf("singapore"), listOf("sg")),
        RegionRule("🇺🇸", listOf("美国"), listOf("united states", "america"), listOf("us", "usa")),
        RegionRule("🇰🇷", listOf("韩国"), listOf("korea"), listOf("kr")),
        RegionRule("🇬🇧", listOf("英国"), listOf("britain", "england"), listOf("uk", "gb")),
        RegionRule("🇩🇪", listOf("德国"), listOf("germany"), listOf("de")),
        RegionRule("🇫🇷", listOf("法国"), listOf("france"), listOf("fr")),
        RegionRule("🇨🇦", listOf("加拿大"), listOf("canada"), listOf("ca")),
        RegionRule("🇦🇺", listOf("澳大利亚"), listOf("australia"), listOf("au")),
        RegionRule("🇷🇺", listOf("俄罗斯"), listOf("russia"), listOf("ru")),
        RegionRule("🇮🇳", listOf("印度"), listOf("india"), listOf("in")),
        RegionRule("🇧🇷", listOf("巴西"), listOf("brazil"), listOf("br")),
        RegionRule("🇳🇱", listOf("荷兰"), listOf("netherlands"), listOf("nl")),
        RegionRule("🇹🇷", listOf("土耳其"), listOf("turkey"), listOf("tr")),
        RegionRule("🇦🇷", listOf("阿根廷"), listOf("argentina"), listOf("ar")),
        RegionRule("🇲🇾", listOf("马来西亚"), listOf("malaysia"), listOf("my")),
        RegionRule("🇹🇭", listOf("泰国"), listOf("thailand"), listOf("th")),
        RegionRule("🇻🇳", listOf("越南"), listOf("vietnam"), listOf("vn")),
        RegionRule("🇵🇭", listOf("菲律宾"), listOf("philippines"), listOf("ph")),
        RegionRule("🇮🇩", listOf("印尼"), listOf("indonesia"), listOf("id"))
    )

    // 预编译词边界 Regex Map
    private val WORD_BOUNDARY_REGEX_MAP: Map<String, Regex> = REGION_RULES
        .flatMap { it.wordBoundaryKeywords }
        .associateWith { word -> Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)") }

    // 地区检测缓存
    private val regionFlagCache = ConcurrentHashMap<String, String>()

    // stableNodeId 缓存
    private val nodeIdCache = ConcurrentHashMap<String, String>()

    // 国旗 Emoji 正则
    private val REGEX_FLAG_EMOJI = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")

    /**
     * 从配置中提取节点 - 使用协程并行处理提升性能
     *
     * @param config sing-box 配置
     * @param profileId 配置 ID
     * @param trafficRepo 流量仓库（用于获取流量统计）
     * @param context 上下文（用于字符串资源）
     * @param onProgress 进度回调
     */
    suspend fun extract(
        config: SingBoxConfig,
        profileId: String,
        trafficRepo: TrafficRepository,
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ): List<NodeUi> = withContext(Dispatchers.Default) {
        val outbounds = config.outbounds ?: return@withContext emptyList()

        // 收集所有 selector 和 urltest 的 outbounds 作为分组
        val groupOutbounds = outbounds.filter {
            it.type == "selector" || it.type == "urltest"
        }

        // 创建节点到分组的映射
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }

        // 过滤出代理节点
        val validOutbounds = outbounds.filter { it.type in PROXY_TYPES }
        if (validOutbounds.isEmpty()) return@withContext emptyList()

        val total = validOutbounds.size
        val completed = AtomicInteger(0)
        val semaphore = Semaphore(PARALLEL_CONCURRENCY)

        val deferredNodes = validOutbounds.map { outbound ->
            async {
                semaphore.withPermit {
                    val node = createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
                    val done = completed.incrementAndGet()
                    if (done % 100 == 0 || done == total) {
                        onProgress?.invoke(context.getString(R.string.profiles_extracting_nodes, done, total))
                    }
                    node
                }
            }
        }

        deferredNodes.awaitAll().filterNotNull()
    }

    /**
     * 创建单个节点 UI 对象
     */
    private fun createNodeUi(
        outbound: Outbound,
        profileId: String,
        nodeToGroup: Map<String, String>,
        trafficRepo: TrafficRepository
    ): NodeUi? {
        if (outbound.tag.isBlank()) return null

        var group = nodeToGroup[outbound.tag] ?: "Default"

        // 校验分组名是否为有效名称 (避免链接被当作分组名)
        if (group.contains("://") || group.length > 50) {
            group = "未分组"
        }

        var regionFlag = detectRegionFlag(outbound.tag)

        // 如果从名称无法识别地区，尝试更深层次的信息挖掘
        if (regionFlag == "🌐" || regionFlag.isBlank()) {
            // 1. 尝试 SNI
            val sni = outbound.tls?.serverName
            if (!sni.isNullOrBlank()) {
                val sniRegion = detectRegionFlag(sni)
                if (sniRegion != "🌐" && sniRegion.isNotBlank()) regionFlag = sniRegion
            }

            // 2. 尝试 Host
            if (regionFlag == "🌐" || regionFlag.isBlank()) {
                val host = outbound.transport?.headers?.get("Host")
                    ?: outbound.transport?.host?.firstOrNull()
                if (!host.isNullOrBlank()) {
                    val hostRegion = detectRegionFlag(host)
                    if (hostRegion != "🌐" && hostRegion.isNotBlank()) regionFlag = hostRegion
                }
            }

            // 3. 尝试服务器地址
            if ((regionFlag == "🌐" || regionFlag.isBlank()) && !outbound.server.isNullOrBlank()) {
                val serverRegion = detectRegionFlag(outbound.server)
                if (serverRegion != "🌐" && serverRegion.isNotBlank()) regionFlag = serverRegion
            }
        }

        val id = stableNodeId(profileId, outbound.tag)

        return NodeUi(
            id = id,
            name = outbound.tag,
            protocol = outbound.type,
            group = group,
            regionFlag = regionFlag,
            latencyMs = null,
            isFavorite = false,
            sourceProfileId = profileId,
            trafficUsed = trafficRepo.getMonthlyTotal(id),
            tags = buildList {
                outbound.tls?.let {
                    if (it.enabled == true) add("TLS")
                    it.reality?.let { r -> if (r.enabled == true) add("Reality") }
                }
                outbound.transport?.type?.let { add(it.uppercase()) }
            }
        )
    }

    /**
     * 生成稳定的节点 ID
     */
    fun stableNodeId(profileId: String, outboundTag: String): String {
        val key = "$profileId|$outboundTag"
        return nodeIdCache.getOrPut(key) {
            val sb = StringBuilder()
            sb.append(profileId).append('|').append(outboundTag)
            java.util.UUID.nameUUIDFromBytes(sb.toString().toByteArray()).toString()
        }
    }

    /**
     * 根据节点名称检测地区标志
     */
    fun detectRegionFlag(name: String): String {
        // 先查缓存
        regionFlagCache[name]?.let { return it }

        val lowerName = name.lowercase()

        for (rule in REGION_RULES) {
            // 1. 检查中文关键词
            if (rule.chineseKeywords.any { lowerName.contains(it) }) {
                regionFlagCache[name] = rule.flag
                return rule.flag
            }

            // 2. 检查英文关键词
            if (rule.englishKeywords.any { lowerName.contains(it) }) {
                regionFlagCache[name] = rule.flag
                return rule.flag
            }

            // 3. 检查需要词边界的短代码
            for (word in rule.wordBoundaryKeywords) {
                val regex = WORD_BOUNDARY_REGEX_MAP[word] ?: continue
                if (regex.containsMatchIn(lowerName)) {
                    regionFlagCache[name] = rule.flag
                    return rule.flag
                }
            }
        }

        // 检查是否已包含国旗 Emoji
        if (containsFlagEmoji(name)) {
            val match = REGEX_FLAG_EMOJI.find(name)
            if (match != null) {
                regionFlagCache[name] = match.value
                return match.value
            }
        }

        regionFlagCache[name] = "🌐"
        return "🌐"
    }

    /**
     * 检测字符串是否包含国旗 Emoji
     */
    private fun containsFlagEmoji(str: String): Boolean {
        return REGEX_FLAG_EMOJI.containsMatchIn(str)
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        regionFlagCache.clear()
        nodeIdCache.clear()
    }
}
