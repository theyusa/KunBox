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
        for (p in proxiesRaw) {
            val m = p as? Map<*, *> ?: continue
            val ob = parseProxy(m)
            if (ob != null) outbounds.add(ob)
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
        val name = asString(proxyMap["name"]) ?: return null
        val type = asString(proxyMap["type"])?.lowercase() ?: return null

        val server = asString(proxyMap["server"])
        val port = asInt(proxyMap["port"])

        return when (type) {
            "vless" -> parseVLess(proxyMap, name, server, port)
            "vmess" -> parseVMess(proxyMap, name, server, port)
            "ss", "shadowsocks" -> parseShadowsocks(proxyMap, name, server, port)
            "trojan" -> parseTrojan(proxyMap, name, server, port)
            "hysteria2", "hy2" -> parseHysteria2(proxyMap, name, server, port)
            "tuic" -> parseTuic(proxyMap, name, server, port)
            "ssh" -> parseSSH(proxyMap, name, server, port)
            "wireguard" -> parseWireGuard(proxyMap, name, server, port)
            else -> null
        }
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
        val alterId = asInt(map["alterId"]) ?: 0
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
            alterId = alterId,
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
        val password = asString(map["password"]) ?: return null
        val sni = asString(map["sni"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val fingerprint = asString(map["client-fingerprint"])
        val congestion = asString(map["congestion-controller"]) ?: "bbr"
        val udpRelayMode = asString(map["udp-relay-mode"]) ?: "native"
        val zeroRtt = asBool(map["reduce-rtt"]) == true

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
