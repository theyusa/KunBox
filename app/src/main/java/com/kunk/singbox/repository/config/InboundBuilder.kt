package com.kunk.singbox.repository.config

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.Inbound
import com.kunk.singbox.model.TunStack

/**
 * 入站配置构建器
 */
object InboundBuilder {

    /**
     * 构建运行时入站配置
     */
    fun build(settings: AppSettings, effectiveTunStack: TunStack): List<Inbound> {
        val inbounds = mutableListOf<Inbound>()

        // 1. 添加混合入站 (Mixed Port)
        if (settings.proxyPort > 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
                    listenPort = settings.proxyPort,
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        }

        if (settings.tunEnabled) {
            inbounds.add(
                Inbound(
                    type = "tun",
                    tag = "tun-in",
                    interfaceName = settings.tunInterfaceName,
                    inet4AddressRaw = listOf("172.19.0.1/30"),
                    mtu = settings.tunMtu,
                    autoRoute = false, // Handled by Android VpnService
                    strictRoute = false, // Can cause issues on some Android versions
                    stack = effectiveTunStack.name.lowercase(),
                    endpointIndependentNat = settings.endpointIndependentNat,
                    gso = true, // GSO 优化，需要 libbox 1.11+
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        } else if (settings.proxyPort <= 0) {
            // 如果禁用 TUN 且未设置自定义端口，则添加默认混合入站
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = "127.0.0.1",
                    listenPort = 2080,
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        }

        return inbounds
    }
}
