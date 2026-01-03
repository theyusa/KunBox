package com.kunk.singbox.utils

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttpClient 单例
 * 优化网络连接复用，减少握手开销
 */
object NetworkClient {

    // 适当放宽超时，适应 VPN 环境下的 DNS 解析延迟
    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 20L
    private const val WRITE_TIMEOUT = 20L
    
    // 连接池配置：保持 5 个空闲连接，存活 5 分钟
    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    // 重试拦截器：应对 VPN 启动时的短暂网络抖动
    private val retryInterceptor = Interceptor { chain ->
        var request = chain.request()
        var response: Response? = null
        var exception: IOException? = null
        var tryCount = 0
        val maxRetries = 2

        while (tryCount <= maxRetries) {
            try {
                response = chain.proceed(request)
                // 如果成功，直接返回
                if (response.isSuccessful) {
                    return@Interceptor response
                }
                // 如果是 404 等业务错误，不需要重试，直接返回
                // 但如果是 502/503/504 等网关错误，可能需要重试？
                // 这里为了简单，只针对 IOException 进行重试，HTTP 错误码由业务层处理
                return@Interceptor response
            } catch (e: IOException) {
                exception = e
                tryCount++
                if (tryCount <= maxRetries) {
                    // 简单的退避策略：等待 1 秒
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        
        // 重试耗尽，抛出最后一次异常
        throw exception ?: IOException("Unknown network error")
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .addInterceptor(retryInterceptor) // 添加重试拦截器
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 获取一个新的 Builder，共享连接池但可以自定义超时等配置
     */
    fun newBuilder(): OkHttpClient.Builder {
        return client.newBuilder()
    }

    /**
     * 清理连接池
     * 当 VPN 状态变化或网络切换时调用，避免复用失效的 Socket
     */
    fun clearConnectionPool() {
        connectionPool.evictAll()
    }
}