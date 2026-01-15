package com.kunk.singbox.repository.config

import com.kunk.singbox.model.Outbound

/**
 * Outbound 运行时修复器
 * 处理各种协议的配置修复和规范化
 */
object OutboundFixer {

    // 正则表达式常量
    private val REGEX_INTERVAL_DIGITS = Regex("^\\d+$")
    private val REGEX_INTERVAL_DECIMAL = Regex("^\\d+\\.\\d+$")
    private val REGEX_INTERVAL_UNIT = Regex("^\\d+(\\.\\d+)?[smhSMH]$")
    private val REGEX_IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val REGEX_IPV6 = Regex("^[0-9a-fA-F:]+$")
    private val REGEX_ED_PARAM_START = Regex("\\?ed=\\d+")
    private val REGEX_ED_PARAM_MID = Regex("&ed=\\d+")

    /**
     * 运行时修复 Outbound 配置
     * 包括：修复 interval 单位、清理 flow、补充 ALPN、补充 User-Agent、补充缺省值
     */
    fun fix(outbound: Outbound): Outbound {
        var result = outbound

        // Fix interval
        val interval = result.interval
        if (interval != null) {
            val fixedInterval = when {
                REGEX_INTERVAL_DIGITS.matches(interval) -> "${interval}s"
                REGEX_INTERVAL_DECIMAL.matches(interval) -> "${interval}s"
                REGEX_INTERVAL_UNIT.matches(interval) -> interval.lowercase()
                else -> interval
            }
            if (fixedInterval != interval) {
                result = result.copy(interval = fixedInterval)
            }
        }

        // Fix flow
        val cleanedFlow = result.flow?.takeIf { it.isNotBlank() }
        val normalizedFlow = cleanedFlow?.let { flowValue ->
            if (flowValue.contains("xtls-rprx-vision")) {
                "xtls-rprx-vision"
            } else {
                flowValue
            }
        }
        if (normalizedFlow != result.flow) {
            result = result.copy(flow = normalizedFlow)
        }

        // Fix URLTest - Convert to selector to avoid sing-box core panic during InterfaceUpdated
        if (result.type == "urltest" || result.type == "url-test") {
            var newOutbounds = result.outbounds
            if (newOutbounds.isNullOrEmpty()) {
                newOutbounds = listOf("direct")
            }

            result = result.copy(
                type = "selector",
                outbounds = newOutbounds,
                default = newOutbounds.firstOrNull(),
                interruptExistConnections = false,
                url = null,
                interval = null,
                tolerance = null
            )
        }

        // Fix Selector empty outbounds
        if (result.type == "selector" && result.outbounds.isNullOrEmpty()) {
            result = result.copy(outbounds = listOf("direct"))
        }

        // Fix TLS SNI for WebSocket
        val tls = result.tls
        val transport = result.transport
        if (transport?.type == "ws" && tls?.enabled == true) {
            val wsHost = transport.headers?.get("Host")
                ?: transport.headers?.get("host")
                ?: transport.host?.firstOrNull()
            val sni = tls.serverName?.trim().orEmpty()
            val server = result.server?.trim().orEmpty()
            if (!wsHost.isNullOrBlank() && !isIpLiteral(wsHost)) {
                val needFix = sni.isBlank() || isIpLiteral(sni) || (server.isNotBlank() && sni.equals(server, ignoreCase = true))
                if (needFix && !wsHost.equals(sni, ignoreCase = true)) {
                    result = result.copy(tls = tls.copy(serverName = wsHost))
                }
            }
        }

        // Fix ALPN for WebSocket + TLS
        val tlsAfterSni = result.tls
        if (result.transport?.type == "ws" && tlsAfterSni?.enabled == true && (tlsAfterSni.alpn == null || tlsAfterSni.alpn.isEmpty())) {
            result = result.copy(tls = tlsAfterSni.copy(alpn = listOf("http/1.1")))
        }

        // Fix User-Agent and path for WS
        if (transport != null && transport.type == "ws") {
            val headers = transport.headers?.toMutableMap() ?: mutableMapOf()
            var needUpdate = false

            if (!headers.containsKey("Host")) {
                val host = transport.host?.firstOrNull()
                    ?: result.tls?.serverName
                    ?: result.server
                if (!host.isNullOrBlank()) {
                    headers["Host"] = host
                    needUpdate = true
                }
            }

            if (!headers.containsKey("User-Agent")) {
                val fingerprint = result.tls?.utls?.fingerprint
                val userAgent = if (fingerprint?.contains("chrome") == true) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                }
                headers["User-Agent"] = userAgent
                needUpdate = true
            }

            val rawPath = transport.path ?: "/"
            val cleanPath = rawPath
                .replace(REGEX_ED_PARAM_START, "")
                .replace(REGEX_ED_PARAM_MID, "")
                .trimEnd('?', '&')
                .ifEmpty { "/" }

            val pathChanged = cleanPath != rawPath

            if (needUpdate || pathChanged) {
                result = result.copy(transport = transport.copy(
                    headers = headers,
                    path = cleanPath
                ))
            }
        }

        // 强制清理 VLESS 协议中的 security 字段 (sing-box 不支持)
        if (result.type == "vless" && result.security != null) {
            result = result.copy(security = null)
        }

        // Hysteria/Hysteria2: 补充缺省带宽
        if (result.type == "hysteria" || result.type == "hysteria2") {
            val up = result.upMbps
            val down = result.downMbps
            val defaultMbps = 50
            if (up == null || down == null) {
                result = result.copy(
                    upMbps = up ?: defaultMbps,
                    downMbps = down ?: defaultMbps
                )
            }
        }

        // 补齐 VMess packetEncoding 缺省值
        if (result.type == "vmess" && result.packetEncoding.isNullOrBlank()) {
            result = result.copy(packetEncoding = "xudp")
        }

        return result
    }

    /**
     * 构建运行时 Outbound，只保留必要字段
     */
    fun buildForRuntime(outbound: Outbound): Outbound {
        val fixed = fix(outbound)
        return when (fixed.type) {
            "selector", "urltest", "url-test" -> Outbound(
                type = "selector",
                tag = fixed.tag,
                outbounds = fixed.outbounds,
                default = fixed.default,
                interruptExistConnections = fixed.interruptExistConnections
            )

            "direct", "block", "dns" -> Outbound(type = fixed.type, tag = fixed.tag)

            "vmess" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                security = fixed.security,
                packetEncoding = fixed.packetEncoding,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex
            )

            "vless" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                flow = fixed.flow,
                packetEncoding = fixed.packetEncoding,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex
            )

            "trojan" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex
            )

            "shadowsocks" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                method = fixed.method,
                password = fixed.password,
                plugin = fixed.plugin,
                pluginOpts = fixed.pluginOpts,
                udpOverTcp = fixed.udpOverTcp,
                multiplex = fixed.multiplex
            )

            "hysteria", "hysteria2" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                authStr = fixed.authStr,
                upMbps = fixed.upMbps,
                downMbps = fixed.downMbps,
                obfs = fixed.obfs,
                recvWindowConn = fixed.recvWindowConn,
                recvWindow = fixed.recvWindow,
                disableMtuDiscovery = fixed.disableMtuDiscovery,
                hopInterval = fixed.hopInterval,
                ports = fixed.ports,
                tls = fixed.tls,
                multiplex = fixed.multiplex
            )

            "tuic" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                password = fixed.password,
                congestionControl = fixed.congestionControl,
                udpRelayMode = fixed.udpRelayMode,
                zeroRttHandshake = fixed.zeroRttHandshake,
                heartbeat = fixed.heartbeat,
                disableSni = fixed.disableSni,
                mtu = fixed.mtu,
                tls = fixed.tls,
                multiplex = fixed.multiplex
            )

            "anytls" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                idleSessionCheckInterval = fixed.idleSessionCheckInterval,
                idleSessionTimeout = fixed.idleSessionTimeout,
                minIdleSession = fixed.minIdleSession,
                tls = fixed.tls,
                multiplex = fixed.multiplex
            )

            "wireguard" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                localAddress = fixed.localAddress,
                privateKey = fixed.privateKey,
                peerPublicKey = fixed.peerPublicKey,
                preSharedKey = fixed.preSharedKey,
                reserved = fixed.reserved,
                peers = fixed.peers
            )

            "ssh" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                user = fixed.user,
                password = fixed.password,
                privateKeyPath = fixed.privateKeyPath,
                privateKeyPassphrase = fixed.privateKeyPassphrase,
                hostKey = fixed.hostKey,
                hostKeyAlgorithms = fixed.hostKeyAlgorithms,
                clientVersion = fixed.clientVersion
            )

            "shadowtls" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                version = fixed.version,
                password = fixed.password,
                detour = fixed.detour
            )

            else -> fixed
        }
    }

    private fun isIpLiteral(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (REGEX_IPV4.matches(v)) {
            return v.split(".").all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
        return v.contains(":") && REGEX_IPV6.matches(v)
    }
}
