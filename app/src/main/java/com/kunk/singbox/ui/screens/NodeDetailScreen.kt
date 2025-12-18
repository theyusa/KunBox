package com.kunk.singbox.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.EditableSelectionItem
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(navController: NavController, nodeId: String) {
    val context = LocalContext.current
    val configRepository = remember { ConfigRepository.getInstance(context) }
    
    // Watch for node changes
    val nodes by configRepository.nodes.collectAsState(initial = emptyList())
    val node = nodes.find { it.id == nodeId }
    
    // Initial load
    var editingOutbound by remember { mutableStateOf<Outbound?>(null) }
    
    LaunchedEffect(nodeId) {
        if (editingOutbound == null) {
            val original = configRepository.getOutboundByNodeId(nodeId)
            if (original != null) {
                editingOutbound = original
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("服务器配置", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (editingOutbound != null) {
                            configRepository.updateNode(nodeId, editingOutbound!!)
                            navController.popBackStack()
                        }
                    }) {
                        Icon(Icons.Rounded.Save, contentDescription = "保存", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (editingOutbound == null) {
                StandardCard {
                    SettingItem(title = "加载中...", value = "")
                }
            } else {
                val outbound = editingOutbound!!
                
                // --- Config Name ---
                StandardCard {
                    EditableTextItem(
                        title = "配置名称",
                        value = outbound.tag,
                        icon = Icons.Rounded.Title,
                        onValueChange = { editingOutbound = outbound.copy(tag = it) }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("服务器设置")
                
                // --- Basic Info ---
                StandardCard {
                    EditableTextItem(
                        title = "服务器",
                        value = outbound.server ?: "",
                        icon = Icons.Rounded.Router,
                        onValueChange = { editingOutbound = outbound.copy(server = it) }
                    )
                    
                    EditableTextItem(
                        title = "服务器端口",
                        value = outbound.serverPort?.toString() ?: "",
                        icon = Icons.Rounded.Numbers,
                        onValueChange = { editingOutbound = outbound.copy(serverPort = it.toIntOrNull() ?: 0) }
                    )
                    
                    // UUID
                    if (outbound.type in listOf("vmess", "vless", "tuic")) {
                        EditableTextItem(
                            title = "用户ID",
                            value = outbound.uuid ?: "",
                            icon = Icons.Rounded.Person,
                            onValueChange = { editingOutbound = outbound.copy(uuid = it) }
                        )
                    }
                    
                    // Password
                    if (outbound.type in listOf("trojan", "anytls", "hysteria2", "tuic")) {
                        EditableTextItem(
                            title = "密码",
                            value = outbound.password ?: "",
                            icon = Icons.Rounded.Password,
                            onValueChange = { editingOutbound = outbound.copy(password = it) }
                        )
                    }
                    
                    // AnyTLS Specific Fields
                    if (outbound.type == "anytls") {
                        EditableTextItem(
                            title = "空闲会话检查间隔",
                            value = outbound.idleSessionCheckInterval ?: "30s",
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = { editingOutbound = outbound.copy(idleSessionCheckInterval = it) }
                        )
                        EditableTextItem(
                            title = "空闲会话超时",
                            value = outbound.idleSessionTimeout ?: "30s",
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = { editingOutbound = outbound.copy(idleSessionTimeout = it) }
                        )
                        EditableTextItem(
                            title = "最小空闲会话数",
                            value = outbound.minIdleSession?.toString() ?: "0",
                            icon = Icons.Rounded.Numbers,
                            onValueChange = { editingOutbound = outbound.copy(minIdleSession = it.toIntOrNull()) }
                        )
                    }
                    
                    // TUIC Specific Fields
                    if (outbound.type == "tuic") {
                        EditableSelectionItem(
                            title = "拥塞控制",
                            value = outbound.congestionControl ?: "bbr",
                            options = listOf("bbr", "cubic", "new_reno"),
                            icon = Icons.Rounded.Speed,
                            onValueChange = { editingOutbound = outbound.copy(congestionControl = it) }
                        )
                        EditableSelectionItem(
                            title = "UDP 中继模式",
                            value = outbound.udpRelayMode ?: "native",
                            options = listOf("native", "quic"),
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = { editingOutbound = outbound.copy(udpRelayMode = it) }
                        )
                        SettingSwitchItem(
                            title = "启用 0-RTT",
                            subtitle = "减少握手延迟，但可能影响安全性",
                            checked = outbound.zeroRttHandshake == true,
                            icon = Icons.Rounded.Bolt,
                            onCheckedChange = { editingOutbound = outbound.copy(zeroRttHandshake = it) }
                        )
                    }
                    
                    // Flow (VLESS)
                    if (outbound.type == "vless") {
                         EditableSelectionItem(
                            title = "Flow (VLESS 子协议)",
                            value = outbound.flow ?: "",
                            options = listOf("", "xtls-rprx-vision"),
                            icon = Icons.Rounded.Waves,
                            onValueChange = { editingOutbound = outbound.copy(flow = it) }
                        )
                    }
                    
                    // Packet Encoding
                    if (outbound.type == "vmess" || outbound.type == "vless") {
                        EditableSelectionItem(
                            title = "包编码",
                            value = outbound.packetEncoding ?: "",
                            options = listOf("", "xudp", "packet"),
                            icon = Icons.Rounded.Layers,
                            onValueChange = { editingOutbound = outbound.copy(packetEncoding = if(it.isEmpty()) null else it) }
                        )
                    }
                    
                    // Security (VMess)
                    if (outbound.type == "vmess") {
                        EditableSelectionItem(
                            title = "安全性",
                            value = outbound.security ?: "auto",
                            options = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"),
                            icon = Icons.Rounded.Security,
                            onValueChange = { editingOutbound = outbound.copy(security = it) }
                        )
                        EditableTextItem(
                            title = "AlterID",
                            value = outbound.alterId?.toString() ?: "0",
                            icon = Icons.Rounded.Tag,
                            onValueChange = { editingOutbound = outbound.copy(alterId = it.toIntOrNull() ?: 0) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // --- Transport ---
                // Only show transport settings for protocols that support pluggable transport (VMess, VLESS, Trojan)
                // Don't show for: hysteria2, hysteria, tuic, anytls (they use fixed transport)
                if (outbound.type !in listOf("hysteria2", "hysteria", "tuic", "anytls")) {
                    StandardCard {
                        val transport = outbound.transport ?: TransportConfig(type = "tcp")
                        val currentType = transport.type ?: "tcp"
                        
                        EditableSelectionItem(
                            title = "传输协议",
                            value = currentType,
                            options = if (outbound.type == "anytls") listOf("tcp") else listOf("tcp", "http", "ws", "grpc", "quic"),
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = { newType ->
                                editingOutbound = outbound.copy(
                                    transport = transport.copy(type = newType)
                                )
                            }
                        )
                        
                        if (currentType == "ws") {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader("WebSocket 设置")
                        EditableTextItem(
                            title = "WebSocket 主机",
                            value = transport.headers?.get("Host") ?: "",
                            icon = Icons.Rounded.Language,
                            onValueChange = { 
                                val newHeaders = (transport.headers ?: emptyMap()).toMutableMap()
                                if (it.isBlank()) newHeaders.remove("Host") else newHeaders["Host"] = it
                                editingOutbound = outbound.copy(transport = transport.copy(headers = newHeaders))
                            }
                        )
                        EditableTextItem(
                            title = "WebSocket 路径",
                            value = transport.path ?: "/",
                            icon = Icons.Rounded.Route,
                            onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(path = it)) }
                        )
                        EditableTextItem(
                            title = "Max early data",
                            value = transport.maxEarlyData?.toString() ?: "",
                            icon = Icons.Rounded.CompareArrows,
                            onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(maxEarlyData = it.toIntOrNull())) }
                        )
                        EditableTextItem(
                            title = "前置数据标题",
                            value = transport.earlyDataHeaderName ?: "",
                            icon = Icons.Rounded.Title,
                            onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(earlyDataHeaderName = if(it.isEmpty()) null else it)) }
                        )
                    }
                    
                    if (currentType == "grpc") {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader("gRPC 设置")
                        EditableTextItem(
                            title = "ServiceName",
                            value = transport.serviceName ?: "",
                            icon = Icons.Rounded.Tag,
                            onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(serviceName = it)) }
                        )
                    }
                    
                        if (currentType == "http" || currentType == "h2") {
                             Spacer(modifier = Modifier.height(8.dp))
                             SectionHeader("HTTP 设置")
                             EditableTextItem(
                                title = "路径",
                                value = transport.path ?: "/",
                                icon = Icons.Rounded.Route,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(path = it)) }
                            )
                            EditableTextItem(
                                title = "Host",
                                value = transport.host?.joinToString(", ") ?: "",
                                icon = Icons.Rounded.Language,
                                onValueChange = {
                                    val hosts = it.split(",").map { h -> h.trim() }.filter { h -> h.isNotEmpty() }
                                    editingOutbound = outbound.copy(transport = transport.copy(host = hosts))
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("TLS 安全设置")
                
                // --- TLS ---
                StandardCard {
                    val tls = outbound.tls ?: TlsConfig(enabled = false)
                    
                    // Determine if TLS is mandatory/intrinsic for this protocol
                    val isTlsIntrinsic = outbound.type in listOf("hysteria2", "hysteria", "tuic", "quic", "anytls")
                    
                    // 传输层加密 (Nekobox style: none, tls, reality)
                    val securityType = if (isTlsIntrinsic || tls.enabled == true) {
                        if (tls.reality?.enabled == true) "reality" else "tls"
                    } else "none"
                    
                    // Only show selector if TLS is not intrinsic
                    if (!isTlsIntrinsic) {
                        EditableSelectionItem(
                            title = "传输层加密",
                            value = securityType,
                            options = listOf("none", "tls", "reality"),
                            icon = Icons.Rounded.Security,
                            onValueChange = { type ->
                                val newTls = when (type) {
                                    "none" -> tls.copy(enabled = false)
                                    "tls" -> tls.copy(enabled = true, reality = null) // Reset reality
                                    "reality" -> tls.copy(enabled = true, reality = com.kunk.singbox.model.RealityConfig(enabled = true))
                                    else -> tls
                                }
                                editingOutbound = outbound.copy(tls = newTls)
                            }
                        )
                    }
                    
                    if (securityType != "none") {
                        EditableTextItem(
                            title = "服务名称指示 (SNI)",
                            value = tls.serverName ?: "",
                            icon = Icons.Rounded.Dns, // Using Dns as replacement for copyright/sni icon
                            onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(serverName = it)) }
                        )
                        
                        EditableTextItem(
                            title = "应用层协议协商 (ALPN)",
                            value = tls.alpn?.joinToString(", ") ?: "",
                            icon = Icons.Rounded.Merge, // Using Merge as icon for protocol negotiation
                            onValueChange = { 
                                val alpnList = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                editingOutbound = outbound.copy(tls = tls.copy(alpn = alpnList))
                            }
                        )
                        
                        SettingSwitchItem(
                            title = "允许不安全的连接",
                            subtitle = "禁用证书检查。启用后该配置安全性相当于明文",
                            checked = tls.insecure == true,
                            icon = Icons.Rounded.Lock,
                            onCheckedChange = { insecure ->
                                editingOutbound = outbound.copy(tls = tls.copy(insecure = insecure))
                            }
                        )
                        
                        // uTLS
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader("TLS 伪装设置")
                        
                        val fingerPrints = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized")
                        EditableSelectionItem(
                            title = "uTLS 指纹",
                            value = tls.utls?.fingerprint ?: "",
                            options = listOf("") + fingerPrints,
                            icon = Icons.Rounded.Fingerprint,
                            onValueChange = { fp ->
                                val newUtls = if (fp.isEmpty()) null else com.kunk.singbox.model.UtlsConfig(enabled = true, fingerprint = fp)
                                editingOutbound = outbound.copy(tls = tls.copy(utls = newUtls))
                            }
                        )
                        
                        // Reality
                        if (securityType == "reality") {
                            val reality = tls.reality ?: com.kunk.singbox.model.RealityConfig(enabled = true)
                            EditableTextItem(
                                title = "Reality Public Key",
                                value = reality.publicKey ?: "",
                                icon = Icons.Rounded.Key,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(reality = reality.copy(publicKey = it))) }
                            )
                            EditableTextItem(
                                title = "Reality ShortId",
                                value = reality.shortId ?: "",
                                icon = Icons.Rounded.Tag,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(reality = reality.copy(shortId = it))) }
                            )
                        }
                        
                        // ECH
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader("ECH")
                        val ech = tls.ech ?: EchConfig(enabled = false)
                        SettingSwitchItem(
                            title = "启用 ECH",
                            checked = ech.enabled == true,
                            icon = Icons.Rounded.Security,
                            onCheckedChange = { enabled ->
                                editingOutbound = outbound.copy(tls = tls.copy(ech = ech.copy(enabled = enabled)))
                            }
                        )
                        if (ech.enabled == true) {
                             EditableTextItem(
                                title = "ECH 配置",
                                value = ech.config?.joinToString("\n") ?: "",
                                icon = Icons.Rounded.Tune,
                                onValueChange = {
                                    val configs = it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                    editingOutbound = outbound.copy(tls = tls.copy(ech = ech.copy(config = configs)))
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Multiplex")
                
                // --- Multiplex ---
                StandardCard {
                    val mux = outbound.multiplex ?: MultiplexConfig(enabled = false)
                    SettingSwitchItem(
                        title = "启用多路复用",
                        subtitle = "多路复用是为了减少 TCP 的握手延迟而设计...",
                        checked = mux.enabled == true,
                        icon = Icons.Rounded.CallSplit,
                        onCheckedChange = { enabled ->
                            editingOutbound = outbound.copy(multiplex = mux.copy(enabled = enabled))
                        }
                    )
                    
                    if (mux.enabled == true) {
                        EditableSelectionItem(
                            title = "Mux 协议",
                            value = mux.protocol ?: "h2mux",
                            options = listOf("h2mux", "smux", "yamux"),
                            icon = Icons.Rounded.Merge,
                            onValueChange = { editingOutbound = outbound.copy(multiplex = mux.copy(protocol = it)) }
                        )
                        EditableTextItem(
                            title = "复用最大并发",
                            value = mux.maxConnections?.toString() ?: "5",
                            icon = Icons.Rounded.Numbers,
                            onValueChange = { editingOutbound = outbound.copy(multiplex = mux.copy(maxConnections = it.toIntOrNull())) }
                        )
                        SettingSwitchItem(
                            title = "Padding",
                            checked = mux.padding == true,
                            icon = Icons.Rounded.Layers,
                            onCheckedChange = { padding ->
                                editingOutbound = outbound.copy(multiplex = mux.copy(padding = padding))
                            }
                        )
                    }
                }
                
                // Hysteria specific
                if (outbound.type in listOf("hysteria2", "hysteria")) {
                    Spacer(modifier = Modifier.height(16.dp))
                    StandardCard {
                        EditableTextItem(
                            title = "最大上行 (Mbps)",
                            value = outbound.upMbps?.toString() ?: "",
                            icon = Icons.Rounded.Bolt,
                            onValueChange = { editingOutbound = outbound.copy(upMbps = it.toIntOrNull()) }
                        )
                        EditableTextItem(
                            title = "最大下行 (Mbps)",
                            value = outbound.downMbps?.toString() ?: "",
                            icon = Icons.Rounded.Bolt,
                            onValueChange = { editingOutbound = outbound.copy(downMbps = it.toIntOrNull()) }
                        )
                        
                        if (outbound.type == "hysteria2") {
                            val obfs = outbound.obfs
                             EditableSelectionItem(
                                title = "混淆类型",
                                value = obfs?.type ?: "",
                                options = listOf("", "salamander"),
                                icon = Icons.Rounded.Lock,
                                onValueChange = { 
                                    val newObfs = if (it.isEmpty()) null else (obfs?.copy(type = it) ?: com.kunk.singbox.model.ObfsConfig(type = it))
                                    editingOutbound = outbound.copy(obfs = newObfs)
                                }
                            )
                            if (obfs?.type == "salamander") {
                                EditableTextItem(
                                    title = "混淆密码",
                                    value = obfs.password ?: "",
                                    icon = Icons.Rounded.Key,
                                    onValueChange = { editingOutbound = outbound.copy(obfs = obfs.copy(password = it)) }
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
    )
}