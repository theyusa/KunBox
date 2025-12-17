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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(navController: NavController, nodeId: String) {
    val context = LocalContext.current
    val configRepository = remember { ConfigRepository.getInstance(context) }
    
    val node = remember(nodeId) { configRepository.getNodeById(nodeId) }
    val outbound = remember(nodeId) { configRepository.getOutboundByNodeId(nodeId) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("节点详情", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Rounded.Bolt, contentDescription = "测速", tint = PureWhite)
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制", tint = PureWhite)
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Rounded.Share, contentDescription = "分享", tint = PureWhite)
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
            if (outbound == null || node == null) {
                StandardCard {
                    SettingItem(title = "错误", value = "无法加载节点信息")
                }
            } else {
                // 基本信息
                StandardCard {
                    SettingItem(title = "名称", value = outbound.tag)
                    SettingItem(title = "协议", value = outbound.type.uppercase())
                    SettingItem(title = "地址", value = outbound.server ?: "-")
                    SettingItem(title = "端口", value = outbound.serverPort?.toString() ?: "-")
                    
                    // UUID (VMess/VLESS)
                    outbound.uuid?.let { uuid ->
                        val maskedUuid = if (uuid.length > 8) {
                            "${uuid.take(4)}****-****-****-****-****${uuid.takeLast(4)}"
                        } else uuid
                        SettingItem(title = "UUID", value = maskedUuid)
                    }
                    
                    // Password (Trojan/Hysteria2/Shadowsocks)
                    outbound.password?.let { password ->
                        val maskedPassword = if (password.length > 8) {
                            "${password.take(4)}****${password.takeLast(4)}"
                        } else "****"
                        SettingItem(title = "密码", value = maskedPassword)
                    }
                    
                    // Flow (VLESS)
                    outbound.flow?.let { flow ->
                        if (flow.isNotBlank()) {
                            SettingItem(title = "流控", value = flow)
                        }
                    }
                    
                    // Method (Shadowsocks)
                    outbound.method?.let { method ->
                        SettingItem(title = "加密方式", value = method)
                    }
                    
                    // Security (VMess)
                    if (outbound.type == "vmess") {
                        SettingItem(title = "安全性", value = outbound.security ?: "auto")
                        outbound.alterId?.let { aid ->
                            SettingItem(title = "AlterID", value = aid.toString())
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 传输配置
                StandardCard {
                    val transportType = outbound.transport?.type
                        ?: if (outbound.type in listOf("hysteria2", "hysteria", "tuic", "wireguard")) "UDP" else "TCP"
                    SettingItem(title = "传输方式", value = transportType.uppercase())
                    
                    // WebSocket
                    if (transportType == "ws") {
                        outbound.transport?.path?.let { path ->
                            SettingItem(title = "路径", value = path)
                        }
                        // WebSocket 的 Host 在 headers 中
                        outbound.transport?.headers?.get("Host")?.let { host ->
                            SettingItem(title = "Host", value = host)
                        }
                    }
                    
                    // gRPC
                    if (transportType == "grpc") {
                        outbound.transport?.serviceName?.let { sn ->
                            SettingItem(title = "ServiceName", value = sn)
                        }
                    }
                    
                    // HTTP/H2
                    if (transportType == "http") {
                        outbound.transport?.path?.let { path ->
                            SettingItem(title = "路径", value = path)
                        }
                        // HTTP 的 host 是 List<String>
                        outbound.transport?.host?.let { hostList ->
                            if (hostList.isNotEmpty()) {
                                SettingItem(title = "Host", value = hostList.joinToString(", "))
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // TLS 配置
                StandardCard {
                    val tlsEnabled = outbound.tls?.enabled == true
                    SettingItem(title = "TLS", value = if (tlsEnabled) "已启用" else "未启用")
                    
                    if (tlsEnabled) {
                        outbound.tls?.serverName?.let { sni ->
                            SettingItem(title = "SNI", value = sni)
                        }
                        
                        outbound.tls?.insecure?.let { insecure ->
                            if (insecure) {
                                SettingItem(title = "跳过证书验证", value = "是")
                            }
                        }
                        
                        outbound.tls?.alpn?.let { alpn ->
                            if (alpn.isNotEmpty()) {
                                SettingItem(title = "ALPN", value = alpn.joinToString(", "))
                            }
                        }
                        
                        outbound.tls?.utls?.let { utls ->
                            if (utls.enabled == true) {
                                SettingItem(title = "指纹", value = utls.fingerprint ?: "-")
                            }
                        }
                        
                        // Reality
                        outbound.tls?.reality?.let { reality ->
                            if (reality.enabled == true) {
                                SettingItem(title = "Reality", value = "已启用")
                                reality.publicKey?.let { pk ->
                                    val maskedPk = if (pk.length > 16) "${pk.take(8)}...${pk.takeLast(8)}" else pk
                                    SettingItem(title = "公钥", value = maskedPk)
                                }
                                reality.shortId?.let { sid ->
                                    SettingItem(title = "ShortID", value = sid)
                                }
                            }
                        }
                    }
                }
                
                // Hysteria2 特有配置
                if (outbound.type == "hysteria2" || outbound.type == "hysteria") {
                    Spacer(modifier = Modifier.height(16.dp))
                    StandardCard {
                        outbound.upMbps?.let { up ->
                            SettingItem(title = "上行带宽", value = "${up} Mbps")
                        }
                        outbound.downMbps?.let { down ->
                            SettingItem(title = "下行带宽", value = "${down} Mbps")
                        }
                        outbound.obfs?.let { obfs ->
                            if (obfs.type != null) {
                                SettingItem(title = "混淆类型", value = obfs.type)
                            }
                        }
                    }
                }
            }
        }
    }
}