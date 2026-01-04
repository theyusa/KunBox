package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * Clash / Clash Meta (Mihomo) YAML 订阅格式解析器
 */
class ClashYamlParser : SubscriptionParser {
    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()
        
        // 排除明显是节点链接的情况
        val nodeLinkPrefixes = listOf(
            "vmess://", "vless://", "ss://", "trojan://",
            "hysteria2://", "hy2://", "hysteria://",
            "tuic://", "anytls://", "wireguard://", "ssh://"
        )
        if (nodeLinkPrefixes.any { trimmed.startsWith(it) }) {
            return false
        }
        
        // 简单特征判断：包含 proxies: 或 proxy-groups: 关键字
        return trimmed.contains("proxies:") || trimmed.contains("proxy-groups:")
    }

    override fun parse(content: String): SingBoxConfig? {
        val root = try {
            Yaml().load<Any>(content)
        } catch (_: YAMLException) {
            return null
        } catch (_: Exception) {
            return null
        }

        val rootMap = (root as? Map<*, *>) ?: return null
        val proxiesRaw = rootMap["proxies"] as? List<*> ?: return null

        val outbounds = mutableListOf<Outbound>()
        val typeCounts = mutableMapOf<String, Int>()
        
        for (p in proxiesRaw) {
            val m = p as? Map<*, *> ?: continue
            
            // 调试日志：打印所有节点的名称和类型
            val name = asString(m["name"]) ?: "unknown"
            val type = asString(m["type"])?.lowercase() ?: "unknown"
            typeCounts[type] = (typeCounts[type] ?: 0) + 1
            
            val ob = parseProxy(m)
            if (ob != null) {
                outbounds.add(ob)
            } else {
                android.util.Log.w("ClashYamlParser", "Skipped proxy: '$name' (type=$type)")
            }
        }
        
        android.util.Log.i("ClashYamlParser", "Parsed proxy types distribution: $typeCounts")
        
        // 解析 proxy-groups
        val proxyGroupsRaw = rootMap["proxy-groups"] as? List<*>
        if (proxyGroupsRaw != null) {
            for (g in proxyGroupsRaw) {
                val gm = g as? Map<*, *> ?: continue
                val name = asString(gm["name"]) ?: continue
                val type = asString(gm["type"])?.lowercase() ?: continue
                val proxies = (gm["proxies"] as? List<*>)?.mapNotNull { asString(it) }?.filter { it.isNotBlank() } ?: emptyList()
                if (proxies.isEmpty()) continue

                when (type) {
                    "select", "selector" -> {
                        outbounds.add(
                            Outbound(
                                type = "selector",
                                tag = name,
                                outbounds = proxies,
                                default = proxies.firstOrNull(),
                                interruptExistConnections = false
                            )
                        )
                    }
                    "url-test", "urltest" -> {
                        val url = asString(gm["url"]) ?: "http://www.gstatic.com/generate_204"
                        val interval = asString(gm["interval"]) ?: asInt(gm["interval"])?.toString() ?: "300s"
                        val tolerance = asInt(gm["tolerance"]) ?: 50
                        outbounds.add(
                            Outbound(
                                type = "urltest",
                                tag = name,
                                outbounds = proxies,
                                url = url,
                                interval = interval,
                                tolerance = tolerance,
                                interruptExistConnections = false
                            )
                        )
                    }
                }
            }
        }

        // 如果没有解析出任何代理节点，返回 null
        if (outbounds.isEmpty()) return null

        // 添加默认出站
        if (outbounds.none { it.tag == "direct" }) {
            outbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        if (outbounds.none { it.tag == "block" }) {
            outbounds.add(Outbound(type = "block", tag = "block"))
        }
        if (outbounds.none { it.tag == "dns-out" }) {
            outbounds.add(Outbound(type = "dns", tag = "dns-out"))
        }

        return SingBoxConfig(outbounds = outbounds)
    }

    private fun parseProxy(proxyMap: Map<*, *>): Outbound? {
        val name = asString(proxyMap["name"]) ?: run {
            android.util.Log.w("ClashYamlParser", "Proxy missing name field: ${proxyMap.keys}")
            return null
        }
        val type = asString(proxyMap["type"])?.lowercase() ?: run {
            android.util.Log.w("ClashYamlParser", "Proxy '$name' missing type field")
            return null
        }

        val server = asString(proxyMap["server"])
        val port = asInt(proxyMap["port"])

        val outbound = when (type) {
            "vless" -> parseVLess(proxyMap, name, server, port)
            "vmess" -> parseVMess(proxyMap, name, server, port)
            "ss", "shadowsocks" -> parseShadowsocks(proxyMap, name, server, port)
            "trojan" -> parseTrojan(proxyMap, name, server, port)
            "hysteria2", "hy2" -> parseHysteria2(proxyMap, name, server, port)
            "hysteria" -> parseHysteria(proxyMap, name, server, port)
            "tuic", "tuic-v5" -> parseTuic(proxyMap, name, server, port)
            "anytls" -> parseAnyTLS(proxyMap, name, server, port)
            "ssh" -> parseSSH(proxyMap, name, server, port)
            "wireguard" -> parseWireGuard(proxyMap, name, server, port)
            "http" -> parseHttp(proxyMap, name, server, port)
            "socks5" -> parseSocks(proxyMap, name, server, port)
            "shadowtls" -> parseShadowTLS(proxyMap, name, server, port)
            else -> {
                android.util.Log.d("ClashYamlParser", "Unknown/Unsupported proxy type: '$type' for node '$name'")
                null
            }
        }
        
        if (outbound == null && (type.contains("tuic") || type.contains("anytls"))) {
            android.util.Log.w("ClashYamlParser", "Failed to parse $type node '$name'. Server: $server, Port: $port, Map: $proxyMap")
        }
        
        return outbound
    }

    private fun parseVLess(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null
        val network = asString(map["network"])?.lowercase()
        val tlsEnabled = asBool(map["tls"]) == true
        val serverName = asString(map["servername"]) ?: asString(map["sni"]) ?: server
        val fingerprint = asString(map["client-fingerprint"])
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val flow = asString(map["flow"])
        val packetEncoding = asString(map["packet-encoding"]) ?: "xudp"
        
        // Reality support
        val realityOpts = map["reality-opts"] as? Map<*, *>
        val realityPublicKey = asString(realityOpts?.get("public-key"))
        val realityShortId = asString(realityOpts?.get("short-id"))
        
        // 自动补充 ALPN
        val finalAlpn = if (tlsEnabled && network == "ws" && (alpn == null || alpn.isEmpty())) listOf("http/1.1") else alpn

        val tlsConfig = if (tlsEnabled) {
            TlsConfig(
                enabled = true,
                serverName = serverName,
                insecure = insecure,
                alpn = finalAlpn,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) },
                reality = if (realityPublicKey != null) {
                    com.kunk.singbox.model.RealityConfig(
                        enabled = true,
                        publicKey = realityPublicKey,
                        shortId = realityShortId
                    )
                } else null
            )
        } else null

        val transport = when (network) {
            "ws" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val path = asString(wsOpts?.get("path")) ?: "/"
                val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                val headers = mutableMapOf<String, String>()
                headersRaw?.forEach { (k, v) ->
                    val ks = asString(k) ?: return@forEach
                    val vs = asString(v) ?: return@forEach
                    headers[ks] = vs
                }
                
                // 自动补充 Host 和 User-Agent
                val host = headers["Host"] ?: headers["host"] ?: serverName
                if (!host.isNullOrBlank()) headers["Host"] = host
                
                if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] = getUserAgent(fingerprint)
                }

                // 处理 max-early-data
                val maxEarlyData = asInt(wsOpts?.get("max-early-data")) ?: 2048
                val earlyDataHeaderName = asString(wsOpts?.get("early-data-header-name")) ?: "Sec-WebSocket-Protocol"

                TransportConfig(
                    type = "ws",
                    path = path,
                    headers = headers,
                    maxEarlyData = maxEarlyData,
                    earlyDataHeaderName = earlyDataHeaderName
                )
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name"))
                    ?: asString(grpcOpts?.get("service-name"))
                    ?: asString(map["grpc-service-name"])
                    ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
            "h2", "http" -> {
                val h2Opts = map["h2-opts"] as? Map<*, *>
                val path = asString(map["path"]) ?: asString(h2Opts?.get("path"))
                val host = asString(map["host"])?.let { listOf(it) } ?: asStringList(h2Opts?.get("host"))
                TransportConfig(type = "http", path = path, host = host)
            }
            else -> null
        }

        return Outbound(
            type = "vless",
            tag = name,
            server = server,
            serverPort = port,
            uuid = uuid,
            flow = flow,
            tls = tlsConfig,
            transport = transport,
            packetEncoding = packetEncoding
        )
    }

    private fun parseVMess(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null
        // 注意：sing-box 不支持 alter_id，只支持 AEAD 加密的 VMess (alterId=0)
        val alterId = asInt(map["alterId"]) ?: 0
        if (alterId != 0) {
            android.util.Log.w("ClashYamlParser", "VMess node '$name' has alterId=$alterId, sing-box only supports alterId=0 (AEAD)")
        }
        val cipher = asString(map["cipher"]) ?: "auto"
        val network = asString(map["network"])?.lowercase()
        val tlsEnabled = asBool(map["tls"]) == true
        val serverName = asString(map["servername"]) ?: asString(map["sni"]) ?: server
        val fingerprint = asString(map["client-fingerprint"])
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val packetEncoding = asString(map["packet-encoding"]) ?: "xudp"
        
        val finalAlpn = if (tlsEnabled && network == "ws" && (alpn == null || alpn.isEmpty())) listOf("http/1.1") else alpn

        val tlsConfig = if (tlsEnabled) {
            TlsConfig(
                enabled = true,
                serverName = serverName,
                insecure = insecure,
                alpn = finalAlpn,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        } else null
        
        val transport = when (network) {
            "ws" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val path = asString(wsOpts?.get("path")) ?: "/"
                val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                val headers = mutableMapOf<String, String>()
                headersRaw?.forEach { (k, v) ->
                    val ks = asString(k) ?: return@forEach
                    val vs = asString(v) ?: return@forEach
                    headers[ks] = vs
                }
                val host = headers["Host"] ?: headers["host"] ?: serverName
                if (!host.isNullOrBlank()) headers["Host"] = host
                 if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] = getUserAgent(fingerprint)
                }
                
                TransportConfig(
                    type = "ws",
                    path = path,
                    headers = headers,
                    maxEarlyData = 2048,
                    earlyDataHeaderName = "Sec-WebSocket-Protocol"
                )
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name")) ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
             "h2", "http" -> {
                val h2Opts = map["h2-opts"] as? Map<*, *>
                val path = asString(h2Opts?.get("path"))
                val host = asStringList(h2Opts?.get("host"))
                TransportConfig(type = "http", path = path, host = host)
            }
            else -> null
        }

        return Outbound(
            type = "vmess",
            tag = name,
            server = server,
            serverPort = port,
            uuid = uuid,
            // alterId 字段已从 Outbound 模型中移除，sing-box 不支持
            security = cipher,
            tls = tlsConfig,
            transport = transport,
            packetEncoding = packetEncoding
        )
    }

    private fun parseShadowsocks(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val cipher = asString(map["cipher"]) ?: return null
        val password = asString(map["password"]) ?: return null
        val plugin = asString(map["plugin"])
        val pluginOpts = map["plugin-opts"] as? Map<*, *>

        // 处理 obfs-local / v2ray-plugin
        // Sing-box 原生不支持这些 plugin，但如果是 simple-obfs http/tls，勉强可以用 transport 模拟？
        // 这里暂时只支持基础 SS，复杂的 plugin 可能需要转换逻辑
        
        return Outbound(
            type = "shadowsocks",
            tag = name,
            server = server,
            serverPort = port,
            method = cipher,
            password = password
        )
    }

    private fun parseTrojan(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val network = asString(map["network"])?.lowercase()
        val sni = asString(map["sni"]) ?: server
        val fingerprint = asString(map["client-fingerprint"])
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])

        val tlsConfig = TlsConfig(
            enabled = true,
            serverName = sni,
            insecure = insecure,
            alpn = alpn,
            utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
        )
        
        val transport = when (network) {
            "ws" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val path = asString(wsOpts?.get("path")) ?: "/"
                val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                val headers = mutableMapOf<String, String>()
                headersRaw?.forEach { (k, v) -> headers[asString(k) ?: ""] = asString(v) ?: "" }
                if (!headers.containsKey("Host")) headers["Host"] = sni
                 if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] = getUserAgent(fingerprint)
                }
                
                TransportConfig(
                    type = "ws",
                    path = path,
                    headers = headers
                )
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name")) ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
            else -> null
        }

        return Outbound(
            type = "trojan",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            tls = tlsConfig,
            transport = transport
        )
    }

    private fun parseHysteria2(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val sni = asString(map["sni"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val fingerprint = asString(map["client-fingerprint"])
        val obfs = asString(map["obfs"])
        val obfsPassword = asString(map["obfs-password"])

        return Outbound(
            type = "hysteria2",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            ),
            obfs = if (obfs != null) com.kunk.singbox.model.ObfsConfig(type = obfs, password = obfsPassword) else null
        )
    }
    
    private fun parseTuic(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null
        
        // 密码可能是 password 或 token，如果都为空则使用 uuid
        val password = asString(map["password"]) ?: asString(map["token"]) ?: uuid
        
        val sni = asString(map["sni"]) ?: asString(map["servername"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true || asBool(map["allow-insecure"]) == true || asBool(map["insecure"]) == true
        val alpn = asStringList(map["alpn"])
        val fingerprint = asString(map["client-fingerprint"]) ?: asString(map["fingerprint"])
        val congestion = asString(map["congestion-controller"]) ?: asString(map["congestion"]) ?: "bbr"
        val udpRelayMode = asString(map["udp-relay-mode"]) ?: "native"
        val zeroRtt = asBool(map["reduce-rtt"]) == true || asBool(map["zero-rtt-handshake"]) == true

        return Outbound(
            type = "tuic",
            tag = name,
            server = server,
            serverPort = port,
            uuid = uuid,
            password = password,
            congestionControl = congestion,
            udpRelayMode = udpRelayMode,
            zeroRttHandshake = zeroRtt,
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        )
    }
    
    private fun parseSSH(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val user = asString(map["username"]) ?: "root"
        val password = asString(map["password"])
        val privateKey = asString(map["private-key"])
        
        return Outbound(
            type = "ssh",
            tag = name,
            server = server,
            serverPort = port,
            user = user,
            password = password,
            privateKey = privateKey
        )
    }

    private fun parseWireGuard(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val privateKey = asString(map["private-key"]) ?: return null
        val publicKey = asString(map["public-key"]) ?: return null // Peer public key
        val preSharedKey = asString(map["pre-shared-key"])
        val address = asStringList(map["ip"]) // Local Address
        val mtu = asInt(map["mtu"]) ?: 1420
        
        val peer = com.kunk.singbox.model.WireGuardPeer(
            server = server,
            serverPort = port,
            publicKey = publicKey,
            preSharedKey = preSharedKey
        )

        return Outbound(
            type = "wireguard",
            tag = name,
            localAddress = address,
            privateKey = privateKey,
            peers = listOf(peer),
            mtu = mtu
        )
    }

    private fun parseAnyTLS(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        
        // 尝试从 password, uuid, token 读取密码
        val password = asString(map["password"])
            ?: asString(map["uuid"])
            ?: asString(map["token"])
            ?: return null
        
        val sni = asString(map["sni"]) ?: asString(map["servername"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true || asBool(map["allow-insecure"]) == true || asBool(map["insecure"]) == true
        val alpn = asStringList(map["alpn"])
        val fingerprint = asString(map["client-fingerprint"]) ?: asString(map["fingerprint"])
        
        val idleSessionCheckInterval = asString(map["idle-session-check-interval"])
        val idleSessionTimeout = asString(map["idle-session-timeout"])
        val minIdleSession = asInt(map["min-idle-session"])

        return Outbound(
            type = "anytls",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            idleSessionCheckInterval = idleSessionCheckInterval,
            idleSessionTimeout = idleSessionTimeout,
            minIdleSession = minIdleSession,
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        )
    }

    private fun parseHysteria(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val authStr = asString(map["auth-str"]) ?: asString(map["auth_str"]) ?: asString(map["auth"])
        val upMbps = asInt(map["up"]) ?: asInt(map["up-mbps"])
        val downMbps = asInt(map["down"]) ?: asInt(map["down-mbps"])
        val sni = asString(map["sni"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val fingerprint = asString(map["client-fingerprint"])
        val obfs = asString(map["obfs"])

        return Outbound(
            type = "hysteria",
            tag = name,
            server = server,
            serverPort = port,
            authStr = authStr,
            upMbps = upMbps,
            downMbps = downMbps,
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            ),
            obfs = if (obfs != null) com.kunk.singbox.model.ObfsConfig(type = obfs) else null
        )
    }

    private fun parseHttp(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val username = asString(map["username"])
        val password = asString(map["password"])
        val tlsEnabled = asBool(map["tls"]) == true
        
        return Outbound(
            type = "http",
            tag = name,
            server = server,
            serverPort = port,
            username = username,
            password = password,
            tls = if (tlsEnabled) TlsConfig(enabled = true) else null
        )
    }

    private fun parseSocks(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val username = asString(map["username"])
        val password = asString(map["password"])
        
        return Outbound(
            type = "socks",
            tag = name,
            server = server,
            serverPort = port,
            username = username,
            password = password
        )
    }

    private fun parseShadowTLS(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val version = asInt(map["version"]) ?: 3
        
        return Outbound(
            type = "shadowtls",
            tag = name,
            server = server,
            serverPort = port,
            version = version,
            password = password,
            tls = TlsConfig(
                enabled = true,
                serverName = asString(map["sni"]) ?: server
            )
        )
    }

    // --- Helpers ---

    private fun getUserAgent(fingerprint: String?): String {
        return if (fingerprint?.contains("chrome", ignoreCase = true) == true) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        }
    }

    private fun asString(v: Any?): String? = when (v) {
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> null
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> when (v.lowercase()) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> null
        }
        else -> null
    }

    private fun asStringList(v: Any?): List<String>? {
        return when (v) {
            is List<*> -> v.mapNotNull { asString(it) }.takeIf { it.isNotEmpty() }
            is String -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}
