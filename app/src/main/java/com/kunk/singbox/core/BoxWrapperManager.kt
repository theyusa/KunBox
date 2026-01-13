package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.BoxWrapper
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BoxWrapper 管理器 - 统一管理 libbox BoxWrapper 的生命周期
 *
 * 功能:
 * - 节点切换: selectOutbound()
 * - 电源管理: pause() / resume()
 * - 流量统计: getUploadTotal() / getDownloadTotal()
 * - 全局访问: 通过 Libbox.getGlobalWrapper() 跨组件共享
 */
object BoxWrapperManager {
    private const val TAG = "BoxWrapperManager"

    @Volatile
    private var wrapper: BoxWrapper? = null

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _hasSelector = MutableStateFlow(false)
    val hasSelector: StateFlow<Boolean> = _hasSelector.asStateFlow()

    /**
     * 初始化 BoxWrapper
     * 在 SingBoxService.startVpn() 成功后调用
     */
    fun init(boxService: BoxService): Boolean {
        return try {
            wrapper = Libbox.wrapBoxService(boxService)
            _isPaused.value = false
            _hasSelector.value = wrapper?.hasSelector() == true
            Log.i(TAG, "BoxWrapper initialized, hasSelector=${_hasSelector.value}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init BoxWrapper", e)
            wrapper = null
            false
        }
    }

    /**
     * 释放 BoxWrapper
     * 在 SingBoxService.stopVpn() 时调用
     */
    fun release() {
        try {
            Libbox.clearGlobalWrapper()
        } catch (e: Exception) {
            Log.w(TAG, "clearGlobalWrapper failed: ${e.message}")
        }
        wrapper = null
        _isPaused.value = false
        _hasSelector.value = false
        Log.i(TAG, "BoxWrapper released")
    }

    /**
     * 检查 wrapper 是否可用
     */
    fun isAvailable(): Boolean = wrapper != null

    // ==================== 节点切换 ====================

    /**
     * 切换出站节点
     * @param nodeTag 节点标签
     * @return true 如果切换成功
     */
    fun selectOutbound(nodeTag: String): Boolean {
        val w = wrapper ?: return false
        return try {
            w.selectOutbound(nodeTag)
            Log.i(TAG, "selectOutbound($nodeTag) success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "selectOutbound($nodeTag) failed: ${e.message}")
            false
        }
    }

    /**
     * 获取当前选中的出站节点
     */
    fun getSelectedOutbound(): String? {
        val w = wrapper ?: return null
        return try {
            w.selectedOutbound.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "getSelectedOutbound failed: ${e.message}")
            null
        }
    }

    /**
     * 获取所有出站节点列表
     * @return 节点标签列表
     */
    fun listOutbounds(): List<String> {
        val w = wrapper ?: return emptyList()
        return try {
            w.listOutboundsString()
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "listOutbounds failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 检查是否有 selector 类型的出站
     */
    fun hasSelector(): Boolean {
        val w = wrapper ?: return false
        return try {
            w.hasSelector()
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 电源管理 ====================

    /**
     * 暂停 - 设备休眠时调用
     * 通知 sing-box 内核进入省电模式
     */
    fun pause(): Boolean {
        val w = wrapper ?: return false
        return try {
            w.pause()
            _isPaused.value = true
            Log.i(TAG, "pause() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "pause() failed: ${e.message}")
            false
        }
    }

    /**
     * 恢复 - 设备唤醒时调用
     * 通知 sing-box 内核恢复正常模式
     */
    fun resume(): Boolean {
        val w = wrapper ?: return false
        return try {
            w.resume()
            _isPaused.value = false
            Log.i(TAG, "resume() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resume() failed: ${e.message}")
            false
        }
    }

    /**
     * 检查是否处于暂停状态
     */
    fun isPausedNow(): Boolean {
        val w = wrapper ?: return false
        return try {
            w.isPaused
        } catch (e: Exception) {
            _isPaused.value
        }
    }

    // ==================== 流量统计 ====================

    /**
     * 获取累计上传字节数
     */
    fun getUploadTotal(): Long {
        val w = wrapper ?: return -1L
        return try {
            w.uploadTotal
        } catch (e: Exception) {
            Log.w(TAG, "getUploadTotal failed: ${e.message}")
            -1L
        }
    }

    /**
     * 获取累计下载字节数
     */
    fun getDownloadTotal(): Long {
        val w = wrapper ?: return -1L
        return try {
            w.downloadTotal
        } catch (e: Exception) {
            Log.w(TAG, "getDownloadTotal failed: ${e.message}")
            -1L
        }
    }

    /**
     * 重置流量统计
     */
    fun resetTraffic(): Boolean {
        val w = wrapper ?: return false
        return try {
            w.resetTraffic()
            Log.i(TAG, "resetTraffic() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resetTraffic() failed: ${e.message}")
            false
        }
    }

    // ==================== 工具函数 ====================

    /**
     * 重置所有连接
     * @param system true=重置系统级连接表
     */
    fun resetAllConnections(system: Boolean = true): Boolean {
        return try {
            Libbox.resetAllConnections(system)
            Log.i(TAG, "resetAllConnections($system) success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resetAllConnections failed: ${e.message}")
            // 回退到 LibboxCompat
            LibboxCompat.resetAllConnections(system)
        }
    }

    /**
     * 获取扩展版本
     */
    fun getExtensionVersion(): String {
        return try {
            Libbox.getExtensionVersion()
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 获取全局 wrapper (用于跨组件访问)
     */
    fun getGlobalWrapper(): BoxWrapper? {
        return try {
            Libbox.getGlobalWrapper()
        } catch (e: Exception) {
            wrapper
        }
    }
}
