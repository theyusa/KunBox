package com.kunk.singbox.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.kunk.singbox.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 应用版本更新检查器
 *
 * 检查 GitHub Release 获取最新版本，如果有新版本则发送通知
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"

    private const val GITHUB_API_URL = "https://api.github.com/repos/roseforljh/KunBox/releases/latest"
    private const val CHANNEL_ID = "app_update"
    private const val NOTIFICATION_ID = 1001

    // 用于记录已通知过的版本，避免重复通知
    private const val PREFS_NAME = "app_update_prefs"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("name") val name: String,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("published_at") val publishedAt: String?,
        @SerializedName("assets") val assets: List<ReleaseAsset>?
    )

    data class ReleaseAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String,
        @SerializedName("size") val size: Long
    )

    /**
     * 检查更新并在有新版本时发送通知
     *
     * @param context Context
     * @param forceNotify 是否强制通知（即使之前已通知过该版本）
     * @return 检查结果，包含是否有新版本及版本信息
     */
    suspend fun checkAndNotify(
        context: Context,
        forceNotify: Boolean = false
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val currentVersion = getCurrentVersion(context)
            Log.d(TAG, "Current version: $currentVersion")

            val release = fetchLatestRelease()
            if (release == null) {
                Log.w(TAG, "Failed to fetch latest release")
                return@withContext UpdateCheckResult.Error("Failed to fetch release info")
            }

            val latestVersion = release.tagName.removePrefix("v")
            Log.d(TAG, "Latest version: $latestVersion")

            if (isNewerVersion(latestVersion, currentVersion)) {
                Log.i(TAG, "New version available: $latestVersion")

                // 检查是否已经通知过这个版本
                val lastNotifiedVersion = getLastNotifiedVersion(context)
                if (forceNotify || lastNotifiedVersion != latestVersion) {
                    showUpdateNotification(context, release)
                    setLastNotifiedVersion(context, latestVersion)
                } else {
                    Log.d(TAG, "Already notified for version $latestVersion, skipping")
                }

                return@withContext UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseUrl = release.htmlUrl,
                    releaseNotes = release.body
                )
            } else {
                Log.d(TAG, "Already on latest version")
                return@withContext UpdateCheckResult.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            return@withContext UpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 获取当前应用版本
     */
    private fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current version", e)
            "0.0.0"
        }
    }

    /**
     * 从 GitHub API 获取最新 Release
     */
    private fun fetchLatestRelease(): GitHubRelease? {
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "KunBox-Android")
            .build()

        return try {
            val response = NetworkClient.client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { json ->
                    Gson().fromJson(json, GitHubRelease::class.java)
                }
            } else {
                Log.w(TAG, "GitHub API returned ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch release", e)
            null
        }
    }

    /**
     * 比较版本号，判断 newVersion 是否比 currentVersion 新
     *
     * 支持格式: x.y.z, x.y.z-beta, x.y.z-rc1 等
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        try {
            val newParts = parseVersion(newVersion)
            val currentParts = parseVersion(currentVersion)

            // 比较主版本号
            for (i in 0 until maxOf(newParts.size, currentParts.size)) {
                val newPart = newParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }

                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }

            return false // 版本相同
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compare versions: $newVersion vs $currentVersion", e)
            return false
        }
    }

    /**
     * 解析版本号字符串为数字列表
     */
    private fun parseVersion(version: String): List<Int> {
        // 移除 v 前缀和后缀（如 -beta, -rc1）
        val cleanVersion = version
            .removePrefix("v")
            .split("-")[0]

        return cleanVersion.split(".").mapNotNull { it.toIntOrNull() }
    }

    /**
     * 显示更新通知
     */
    private fun showUpdateNotification(context: Context, release: GitHubRelease) {
        createNotificationChannel(context)

        val version = release.tagName.removePrefix("v")

        // 点击通知打开 Release 页面
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.update_notification_title)
        val content = context.getString(R.string.update_notification_content, version)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append(content)
                    release.body?.let { notes ->
                        append("\n\n")
                        // 只显示前200个字符的更新日志
                        val truncatedNotes = if (notes.length > 200) {
                            notes.take(200) + "..."
                        } else {
                            notes
                        }
                        append(truncatedNotes)
                    }
                }
            ))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        Log.i(TAG, "Update notification shown for version $version")
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.update_channel_name)
            val description = context.getString(R.string.update_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 获取上次通知的版本
     */
    private fun getLastNotifiedVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)
    }

    /**
     * 设置上次通知的版本
     */
    private fun setLastNotifiedVersion(context: Context, version: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, version).apply()
    }

    /**
     * 清除上次通知的版本记录（用于测试或用户手动检查更新）
     */
    fun clearLastNotifiedVersion(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_NOTIFIED_VERSION).apply()
    }
}

/**
 * 更新检查结果
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        val releaseNotes: String?
    ) : UpdateCheckResult()

    data class UpToDate(val currentVersion: String) : UpdateCheckResult()

    data class Error(val message: String) : UpdateCheckResult()
}
