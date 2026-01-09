package com.kunk.singbox.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference

/**
 * 网络监听器 - 在 Application 启动时开始监听物理网络变化
 *
 * 设计参考 NekoBox 的 DefaultNetworkListener
 * 核心优化: 预缓存物理网络, VPN 启动时直接使用已缓存的网络
 * 避免 VPN establish 后应用需要重新探测网络导致的二次加载
 */
object DefaultNetworkListener {
    private const val TAG = "DefaultNetworkListener"

    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network?>()
        }
        class Stop(val key: Any) : NetworkMessage()
        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
    }

    @Suppress("OPT_IN_USAGE")
    private val networkActor = GlobalScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        val pendingRequests = arrayListOf<NetworkMessage.Get>()

        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                if (listeners.isEmpty()) register()
                listeners[message.key] = message.listener
                if (network != null) message.listener(network)
            }
            is NetworkMessage.Get -> {
                if (network == null) {
                    pendingRequests += message
                } else {
                    message.response.complete(network)
                }
            }
            is NetworkMessage.Stop -> {
                if (listeners.isNotEmpty() && listeners.remove(message.key) != null && listeners.isEmpty()) {
                    network = null
                    unregister()
                }
            }
            is NetworkMessage.Put -> {
                network = message.network
                pendingRequests.forEach { it.response.complete(message.network) }
                pendingRequests.clear()
                listeners.values.forEach { it(network) }
            }
            is NetworkMessage.Update -> {
                if (network == message.network) {
                    listeners.values.forEach { it(network) }
                }
            }
            is NetworkMessage.Lost -> {
                if (network == message.network) {
                    network = null
                    listeners.values.forEach { it(null) }
                }
            }
        }
    }

    // 缓存的物理网络 - VPN 服务可直接使用
    @Volatile
    var underlyingNetwork: Network? = null
        private set

    private var connectivityManagerRef: WeakReference<ConnectivityManager>? = null
    private var fallback = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {
            // API 23 OEM bugs workaround
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()

    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: $network")
            underlyingNetwork = network
            runBlocking { networkActor.send(NetworkMessage.Put(network)) }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: $network")
            if (underlyingNetwork == network) {
                underlyingNetwork = null
            }
            runBlocking { networkActor.send(NetworkMessage.Lost(network)) }
        }
    }

    /**
     * 启动网络监听
     * 应在 Application.onCreate() 中调用
     */
    suspend fun start(connectivityManager: ConnectivityManager, key: Any, listener: (Network?) -> Unit) {
        connectivityManagerRef = WeakReference(connectivityManager)
        networkActor.send(NetworkMessage.Start(key, listener))
    }

    /**
     * 获取当前缓存的网络
     */
    suspend fun get(): Network? {
        return if (fallback) {
            if (Build.VERSION.SDK_INT >= 23) {
                connectivityManagerRef?.get()?.activeNetwork
            } else {
                null
            }
        } else {
            NetworkMessage.Get().run {
                networkActor.send(this)
                response.await()
            }
        }
    }

    /**
     * 停止网络监听
     */
    suspend fun stop(key: Any) {
        networkActor.send(NetworkMessage.Stop(key))
    }

    private fun register() {
        val cm = connectivityManagerRef?.get() ?: return
        try {
            fallback = false
            when {
                Build.VERSION.SDK_INT >= 31 -> {
                    cm.registerBestMatchingNetworkCallback(request, Callback, mainHandler)
                }
                Build.VERSION.SDK_INT >= 28 -> {
                    cm.requestNetwork(request, Callback, mainHandler)
                }
                Build.VERSION.SDK_INT >= 26 -> {
                    cm.registerDefaultNetworkCallback(Callback, mainHandler)
                }
                Build.VERSION.SDK_INT >= 24 -> {
                    cm.registerDefaultNetworkCallback(Callback)
                }
                else -> {
                    cm.requestNetwork(request, Callback)
                }
            }
            Log.i(TAG, "Network listener registered (SDK ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network listener, using fallback", e)
            fallback = true
        }
    }

    private fun unregister() {
        try {
            connectivityManagerRef?.get()?.unregisterNetworkCallback(Callback)
            Log.i(TAG, "Network listener unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network listener", e)
        }
    }
}
