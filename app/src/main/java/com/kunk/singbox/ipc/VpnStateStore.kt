package com.kunk.singbox.ipc

import com.tencent.mmkv.MMKV

/**
 * VPN 状态存储 - 使用 MMKV 实现跨进程安全访问
 *
 * MMKV 优势:
 * - 跨进程安全 (MULTI_PROCESS_MODE)
 * - 原子写入，无竞态条件
 * - 性能比 SharedPreferences 快 100x
 */
object VpnStateStore {
    private const val MMKV_ID = "vpn_state"

    private const val KEY_VPN_ACTIVE = "vpn_active"
    private const val KEY_VPN_PENDING = "vpn_pending"
    private const val KEY_VPN_ACTIVE_LABEL = "vpn_active_label"
    private const val KEY_VPN_LAST_ERROR = "vpn_last_error"
    private const val KEY_VPN_MANUALLY_STOPPED = "vpn_manually_stopped"
    private const val KEY_CORE_MODE = "core_mode"

    enum class CoreMode {
        NONE,
        VPN,
        PROXY
    }

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)
    }

    fun getActive(): Boolean = mmkv.decodeBool(KEY_VPN_ACTIVE, false)

    fun setActive(active: Boolean) {
        mmkv.encode(KEY_VPN_ACTIVE, active)
    }

    fun getPending(): String = mmkv.decodeString(KEY_VPN_PENDING, "") ?: ""

    fun setPending(pending: String?) {
        mmkv.encode(KEY_VPN_PENDING, pending ?: "")
    }

    fun getActiveLabel(): String = mmkv.decodeString(KEY_VPN_ACTIVE_LABEL, "") ?: ""

    fun setActiveLabel(label: String?) {
        mmkv.encode(KEY_VPN_ACTIVE_LABEL, label ?: "")
    }

    fun getLastError(): String = mmkv.decodeString(KEY_VPN_LAST_ERROR, "") ?: ""

    fun setLastError(message: String?) {
        mmkv.encode(KEY_VPN_LAST_ERROR, message ?: "")
    }

    fun isManuallyStopped(): Boolean = mmkv.decodeBool(KEY_VPN_MANUALLY_STOPPED, false)

    fun setManuallyStopped(value: Boolean) {
        mmkv.encode(KEY_VPN_MANUALLY_STOPPED, value)
    }

    fun getMode(): CoreMode {
        val raw = mmkv.decodeString(KEY_CORE_MODE, CoreMode.NONE.name) ?: CoreMode.NONE.name
        return runCatching { CoreMode.valueOf(raw) }.getOrDefault(CoreMode.NONE)
    }

    fun setMode(mode: CoreMode) {
        mmkv.encode(KEY_CORE_MODE, mode.name)
    }

    /**
     * 清除所有状态 (用于重置)
     */
    fun clear() {
        mmkv.clearAll()
    }
}
