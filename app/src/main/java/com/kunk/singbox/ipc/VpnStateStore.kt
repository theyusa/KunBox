package com.kunk.singbox.ipc

import android.content.Context

object VpnStateStore {
    private const val PREFS_NAME = "vpn_state"

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

    fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActive(context: Context): Boolean = prefs(context).getBoolean(KEY_VPN_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_VPN_ACTIVE, active).apply()
    }

    fun getPending(context: Context): String = prefs(context).getString(KEY_VPN_PENDING, "").orEmpty()

    fun setPending(context: Context, pending: String?) {
        prefs(context).edit().putString(KEY_VPN_PENDING, pending.orEmpty()).apply()
    }

    fun getActiveLabel(context: Context): String = prefs(context).getString(KEY_VPN_ACTIVE_LABEL, "").orEmpty()

    fun setActiveLabel(context: Context, label: String?) {
        prefs(context).edit().putString(KEY_VPN_ACTIVE_LABEL, label.orEmpty()).apply()
    }

    fun getLastError(context: Context): String = prefs(context).getString(KEY_VPN_LAST_ERROR, "").orEmpty()

    fun setLastError(context: Context, message: String?) {
        prefs(context).edit().putString(KEY_VPN_LAST_ERROR, message.orEmpty()).apply()
    }

    fun isManuallyStopped(context: Context): Boolean = prefs(context).getBoolean(KEY_VPN_MANUALLY_STOPPED, false)

    fun setManuallyStopped(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VPN_MANUALLY_STOPPED, value).apply()
    }

    fun getMode(context: Context): CoreMode {
        val raw = prefs(context).getString(KEY_CORE_MODE, CoreMode.NONE.name).orEmpty()
        return runCatching { CoreMode.valueOf(raw) }.getOrDefault(CoreMode.NONE)
    }

    fun setMode(context: Context, mode: CoreMode) {
        prefs(context).edit().putString(KEY_CORE_MODE, mode.name).apply()
    }
}
