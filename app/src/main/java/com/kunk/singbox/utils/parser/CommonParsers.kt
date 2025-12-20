package com.kunk.singbox.utils.parser

import android.util.Base64
import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig

/**
 * Sing-box JSON 格式解析器
 */
class SingBoxParser(private val gson: Gson) : SubscriptionParser {
    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) || 
               (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    override fun parse(content: String): SingBoxConfig? {
        return try {
            val config = gson.fromJson(content, SingBoxConfig::class.java)
            if (config.outbounds != null && config.outbounds.isNotEmpty()) {
                config
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Base64 订阅格式解析器 (V2Ray/Shadowrocket)
 */
class Base64Parser(private val nodeParser: (String) -> Outbound?) : SubscriptionParser {
    override fun canParse(content: String): Boolean {
        // 简单判断是否可能是 Base64 或包含节点链接
        val trimmed = content.trim()
        return !trimmed.startsWith("{") && !trimmed.startsWith("proxies:") && !trimmed.startsWith("proxy-groups:")
    }

    override fun parse(content: String): SingBoxConfig? {
        // 1. 尝试 Base64 解码
        val decoded = tryDecodeBase64(content.trim()) ?: content
        
        // 2. 按行分割
        val lines = decoded.lines().filter { it.isNotBlank() }
        val outbounds = mutableListOf<Outbound>()
        
        for (line in lines) {
            val trimmedLine = line.trim()
            // 3. 处理 Shadowrocket 的 remarks 和 plugin 参数 (如果存在)
            // 格式通常是: ss://...#remarks 或 ss://...?plugin=...
            // NodeLinkParser 已经处理了 #remarks (作为 tag)
            // 这里主要关注是否需要预处理一些非标准格式，目前 NodeLinkParser 应该足够健壮
            
            val outbound = nodeParser(trimmedLine)
            if (outbound != null) {
                outbounds.add(outbound)
            }
        }
        
        if (outbounds.isEmpty()) return null
        
        // 4. 添加默认出站
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

    private fun tryDecodeBase64(content: String): String? {
        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = Base64.decode(content, flags)
                val text = String(decoded)
                // 验证解码结果是否看起来像文本 (包含常见协议头或换行)
                if (text.isNotBlank() && (
                    text.contains("://") || 
                    text.contains("\n") || 
                    text.contains("\r") ||
                    text.all { it.isLetterOrDigit() || it.isWhitespace() || "=/-_:.".contains(it) }
                )) {
                    return text
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
