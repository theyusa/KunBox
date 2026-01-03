package com.kunk.singbox.utils.parser

import android.util.Log
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig

/**
 * 订阅转换引擎接口
 */
interface SubscriptionParser {
    /**
     * 判断是否能解析该内容
     */
    fun canParse(content: String): Boolean

    /**
     * 解析内容并返回 SingBoxConfig
     */
    fun parse(content: String): SingBoxConfig?
}

/**
 * 订阅解析管理器
 */
class SubscriptionManager(private val parsers: List<SubscriptionParser>) {
    
    companion object {
        private const val TAG = "SubscriptionManager"
        
    }

    /**
     * 解析订阅内容
     */
    fun parse(content: String): SingBoxConfig? {
        for (parser in parsers) {
            if (parser.canParse(content)) {
                try {
                    val config = parser.parse(content)
                    if (config != null && !config.outbounds.isNullOrEmpty()) {
                        return config
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parser ${parser.javaClass.simpleName} failed", e)
                }
            }
        }
        return null
    }
}
