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
        // 注意：sing-box 1.11.0+ 弃用了 inbound 中的 sniff/sniff_override_destination 字段
        // 现在通过 route.rules 中的 action: "sniff" 规则来启用协议嗅探
        if (settings.proxyPort > 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
                    listenPort = settings.proxyPort
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
                    autoRoute = false,
                    strictRoute = false,
                    stack = effectiveTunStack.name.lowercase(),
                    endpointIndependentNat = settings.endpointIndependentNat,
                    gso = true
                )
            )
        } else if (settings.proxyPort <= 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = "127.0.0.1",
                    listenPort = 2080
                )
            )
        }

        return inbounds
    }
}
