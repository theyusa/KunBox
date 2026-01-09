package com.kunk.singbox

import android.app.ActivityManager
import android.app.Application
import android.net.ConnectivityManager
import android.os.Process
import androidx.work.Configuration
import androidx.work.WorkManager
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.RuleSetAutoUpdateWorker
import com.kunk.singbox.service.SubscriptionAutoUpdateWorker
import com.kunk.singbox.service.VpnKeepaliveWorker
import com.kunk.singbox.utils.DefaultNetworkListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SingBoxApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // 手动初始化 WorkManager 以支持多进程
        if (!isWorkManagerInitialized()) {
            WorkManager.initialize(this, workManagerConfiguration)
        }

        LogRepository.init(this)

        // 清理遗留的临时数据库文件 (应对应用崩溃或强制停止的情况)
        cleanupOrphanedTempFiles()

        // 只在主进程中调度自动更新任务
        if (isMainProcess()) {
            applicationScope.launch {
                // 预缓存物理网络 - 参考 NekoBox 优化
                // VPN 启动时可直接使用已缓存的网络，避免应用二次加载
                val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    DefaultNetworkListener.start(cm, this@SingBoxApplication) { network ->
                        android.util.Log.d("SingBoxApp", "Underlying network updated: $network")
                    }
                }

                // 订阅自动更新
                SubscriptionAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)
                // 规则集自动更新
                RuleSetAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)
                // VPN 进程保活机制
                // 优化: 定期检查后台进程状态,防止系统杀死导致 VPN 意外断开
                VpnKeepaliveWorker.schedule(this@SingBoxApplication)
            }
        }
    }

    private fun isWorkManagerInitialized(): Boolean {
        return try {
            WorkManager.getInstance(this)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val processName = activityManager.runningAppProcesses?.find { it.pid == pid }?.processName
        return processName == packageName
    }

    /**
     * 清理遗留的临时数据库文件
     * 在应用启动时执行,清理因崩溃或强制停止而残留的测试数据库文件
     */
    private fun cleanupOrphanedTempFiles() {
        try {
            val tempDir = java.io.File(cacheDir, "singbox_temp")
            if (!tempDir.exists() || !tempDir.isDirectory) return

            val cleaned = mutableListOf<String>()
            tempDir.listFiles()?.forEach { file ->
                // 清理所有测试数据库文件及其 WAL/SHM 辅助文件
                if (file.name.startsWith("test_") || file.name.startsWith("batch_test_")) {
                    if (file.delete()) {
                        cleaned.add(file.name)
                    }
                }
            }

            if (cleaned.isNotEmpty()) {
                android.util.Log.i("SingBoxApp", "Cleaned ${cleaned.size} orphaned temp files: ${cleaned.take(5).joinToString()}")
            }
        } catch (e: Exception) {
            android.util.Log.w("SingBoxApp", "Failed to cleanup orphaned temp files", e)
        }
    }
}
