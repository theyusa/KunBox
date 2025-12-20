package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import com.kunk.singbox.model.RealityConfig
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.WireGuardPeer
import android.util.Base64
import android.util.Log
import com.google.gson.Gson

/**
 * 各种节点链接解析器集合
 */
class NodeLinkParser(private val gson: Gson) {
    
    fun parse(link: String): Outbound? {
        return when {
            link.startsWith("ss://") -> parseShadowsocksLink(link)
            link.startsWith("vmess://") -> parseVMessLink(link)
            link.startsWith("vless://") -> parseVLessLink(link)
            link.startsWith("trojan://") -> parseTrojanLink(link)
            link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2Link(link)
            link.startsWith("hysteria://") -> parseHysteriaLink(link)
            link.startsWith("anytls://") -> parseAnyTLSLink(link)
            link.startsWith("tuic://") -> parseTuicLink(link)
            link.startsWith("wireguard://") -> parseWireGuardLink(link)
            link.startsWith("ssh://") -> parseSSHLink(link)
            else -> null
        }
    }

    private fun parseShadowsocksLink(link: String): Outbound? {
        try {
            val uri = link.removePrefix("ss://")
            val nameIndex = uri.lastIndexOf('#')
            val name = if (nameIndex > 0) java.net.URLDecoder.decode(uri.substring(nameIndex + 1), "UTF-8") else "SS Node"
            val mainPart = if (nameIndex > 0) uri.substring(0, nameIndex) else uri
            
            val atIndex = mainPart.lastIndexOf('@')
            if (atIndex > 0) {
                val userInfo = String(Base64.decode(mainPart.substring(0, atIndex), Base64.URL_SAFE or Base64.NO_PADDING))
                val serverPart = mainPart.substring(atIndex + 1)
                val colonIndex = serverPart.lastIndexOf(':')
                val server = serverPart.substring(0, colonIndex)
                val port = serverPart.substring(colonIndex + 1).toInt()
                val methodPassword = userInfo.split(":", limit = 2)
                
                return Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = methodPassword[0],
                    password = methodPassword.getOrElse(1) { "" }
                )
            } else {
                val decoded = String(Base64.decode(mainPart, Base64.URL_SAFE or Base64.NO_PADDING))
                val regex = Regex("(.+):(.+)@(.+):(\\d+)")
                val match = regex.find(decoded)
                if (match != null) {
                    val (method, password, server, port) = match.destructured
                    return Outbound(
                        type = "shadowsocks",
                        tag = name,
                        server = server,
                        serverPort = port.toInt(),
                        method = method,
                        password = password
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse SS link", e)
        }
        return null
    }

    private fun parseVMessLink(link: String): Outbound? {
        try {
            val base64Part = link.removePrefix("vmess://")
            val decoded = String(Base64.decode(base64Part, Base64.DEFAULT))
            // 这里需要一个简单的内部类来映射 VMess 链接的 JSON
            val json = gson.fromJson(decoded, Map::class.java)
            
            val add = json["add"] as? String ?: ""
            val port = (json["port"] as? String)?.toIntOrNull() ?: (json["port"] as? Double)?.toInt() ?: 443
            val id = json["id"] as? String ?: ""
            val aid = (json["aid"] as? String)?.toIntOrNull() ?: (json["aid"] as? Double)?.toInt() ?: 0
            val net = json["net"] as? String ?: "tcp"
            val type = json["type"] as? String ?: "none"
            val host = json["host"] as? String ?: ""
            val path = json["path"] as? String ?: ""
            val tls = json["tls"] as? String ?: ""
            val sni = json["sni"] as? String ?: ""
            val ps = json["ps"] as? String ?: "VMess Node"
            val fp = json["fp"] as? String ?: ""

            val tlsConfig = if (tls == "tls") {
                TlsConfig(
                    enabled = true,
                    serverName = if (sni.isNotBlank()) sni else if (host.isNotBlank()) host else add,
                    utls = if (fp.isNotBlank()) UtlsConfig(enabled = true, fingerprint = fp) else null
                )
            } else null
            
            val transport = when (net) {
                "ws" -> TransportConfig(
                    type = "ws",
                    path = if (path.isBlank()) "/" else path,
                    headers = if (host.isNotBlank()) mapOf("Host" to host) else null
                )
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = path
                )
                "h2" -> TransportConfig(
                    type = "http",
                    host = if (host.isNotBlank()) listOf(host) else null,
                    path = path
                )
                else -> null
            }
            
            return Outbound(
                type = "vmess",
                tag = ps,
                server = add,
                serverPort = port,
                uuid = id,
                alterId = aid,
                security = "auto",
                tls = tlsConfig,
                transport = transport
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse VMess link", e)
        }
        return null
    }

    private fun parseVLessLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "VLESS Node", "UTF-8")
            val uuid = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val security = params["security"] ?: "none"
            val sni = params["sni"] ?: params["host"] ?: server
            val transportType = params["type"] ?: "tcp"
            
            val tlsConfig = when (security) {
                "tls" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    utls = params["fp"]?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
                "reality" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    reality = RealityConfig(
                        enabled = true,
                        publicKey = params["pbk"],
                        shortId = params["sid"]
                    ),
                    utls = params["fp"]?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
                else -> null
            }
            
            val transport = when (transportType) {
                "ws" -> TransportConfig(
                    type = "ws",
                    path = params["path"] ?: "/",
                    headers = params["host"]?.let { mapOf("Host" to it) }
                )
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = params["serviceName"] ?: params["sn"] ?: ""
                )
                else -> null
            }
            
            return Outbound(
                type = "vless",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                tls = tlsConfig,
                transport = transport
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse VLESS link", e)
        }
        return null
    }

    private fun parseTrojanLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Trojan Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            return Outbound(
                type = "trojan",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: params["host"] ?: server
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Trojan link", e)
        }
        return null
    }

    private fun parseHysteria2Link(link: String): Outbound? {
        try {
            val uri = java.net.URI(link.replace("hy2://", "hysteria2://"))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria2 Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port == -1) 443 else uri.port
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            return Outbound(
                type = "hysteria2",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: server
                ),
                obfs = params["obfs"]?.let { ObfsConfig(type = it, password = params["obfs-password"]) }
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Hy2 link", e)
        }
        return null
    }

    private fun parseHysteriaLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port == -1) 443 else uri.port
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            return Outbound(
                type = "hysteria",
                tag = name,
                server = server,
                serverPort = port,
                authStr = params["auth"],
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: server
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Hysteria link", e)
        }
        return null
    }

    private fun parseAnyTLSLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "AnyTLS Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            return Outbound(
                type = "anytls",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(enabled = true, serverName = server)
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse AnyTLS link", e)
        }
        return null
    }

    private fun parseTuicLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "TUIC Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            val userInfo = uri.userInfo ?: ""
            val parts = userInfo.split(":")
            
            return Outbound(
                type = "tuic",
                tag = name,
                server = server,
                serverPort = port,
                uuid = parts.getOrNull(0),
                password = parts.getOrNull(1),
                tls = TlsConfig(enabled = true, serverName = server)
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse TUIC link", e)
        }
        return null
    }

    private fun parseWireGuardLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "WireGuard Node", "UTF-8")
            val privateKey = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 51820
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val peer = WireGuardPeer(
                server = server,
                serverPort = port,
                publicKey = params["public_key"] ?: ""
            )
            
            return Outbound(
                type = "wireguard",
                tag = name,
                privateKey = privateKey,
                peers = listOf(peer)
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse WG link", e)
        }
        return null
    }

    private fun parseSSHLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "SSH Node", "UTF-8")
            val userInfo = uri.userInfo ?: ""
            val parts = userInfo.split(":")
            
            return Outbound(
                type = "ssh",
                tag = name,
                server = uri.host,
                serverPort = if (uri.port > 0) uri.port else 22,
                user = parts.getOrNull(0),
                password = parts.getOrNull(1)
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse SSH link", e)
        }
        return null
    }
}
