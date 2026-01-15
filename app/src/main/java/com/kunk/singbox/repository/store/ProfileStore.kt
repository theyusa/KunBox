package com.kunk.singbox.repository.store

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.model.SavedProfilesData
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.utils.KryoSerializer
import java.io.File

/**
 * 配置数据存储 - 负责 Kryo/JSON 序列化和配置文件管理
 *
 * 职责:
 * - 配置文件的读写 (Kryo 优先，JSON 回退)
 * - 配置缓存管理
 * - 配置目录管理
 */
class ProfileStore(context: Context, private val gson: Gson) {

    companion object {
        private const val TAG = "ProfileStore"
        private const val PROFILES_FILE_KRYO = "profiles.kryo"
        private const val PROFILES_FILE_JSON = "profiles.json"
        private const val CONFIG_DIR = "configs"
    }

    private val filesDir = context.filesDir
    val profilesFileKryo = File(filesDir, PROFILES_FILE_KRYO)
    val profilesFileJson = File(filesDir, PROFILES_FILE_JSON)
    val configDir = File(filesDir, CONFIG_DIR).also { it.mkdirs() }

    // 配置缓存 - LRU 风格，最多缓存 10 个配置
    private val maxConfigCacheSize = 10
    private val configCache = object : LinkedHashMap<String, SingBoxConfig>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SingBoxConfig>?): Boolean {
            return size > maxConfigCacheSize
        }
    }

    /**
     * 加载保存的配置数据
     * @return SavedProfilesData 或 null
     */
    fun loadSavedData(): SavedProfilesData? {
        return try {
            when {
                profilesFileKryo.exists() -> {
                    KryoSerializer.deserializeFromFile<SavedProfilesData>(profilesFileKryo)
                }
                profilesFileJson.exists() -> {
                    val json = profilesFileJson.readText()
                    val savedDataType = object : TypeToken<SavedProfilesData>() {}.type
                    gson.fromJson<SavedProfilesData>(json, savedDataType)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved data", e)
            null
        }
    }

    /**
     * 保存配置数据
     * @return true 如果保存成功
     */
    fun saveData(data: SavedProfilesData): Boolean {
        return try {
            val success = KryoSerializer.serializeToFile(data, profilesFileKryo)

            if (success) {
                // 成功保存 Kryo 格式后，删除旧的 JSON 文件
                if (profilesFileJson.exists()) {
                    profilesFileJson.delete()
                }
                true
            } else {
                // Kryo 失败时回退到 JSON
                Log.w(TAG, "Kryo serialization failed, falling back to JSON")
                saveDataAsJson(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save data", e)
            false
        }
    }

    private fun saveDataAsJson(data: SavedProfilesData): Boolean {
        return try {
            val json = gson.toJson(data)
            val tmpFile = File(profilesFileJson.parent, "${profilesFileJson.name}.tmp")
            tmpFile.writeText(json)
            if (tmpFile.exists() && tmpFile.length() > 0) {
                if (profilesFileJson.exists()) {
                    profilesFileJson.delete()
                }
                if (!tmpFile.renameTo(profilesFileJson)) {
                    tmpFile.copyTo(profilesFileJson, overwrite = true)
                    tmpFile.delete()
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON fallback also failed", e)
            false
        }
    }

    /**
     * 加载单个配置文件
     */
    fun loadConfig(profileId: String): SingBoxConfig? {
        synchronized(configCache) {
            configCache[profileId]?.let { return it }
        }

        val configFile = File(configDir, "$profileId.json")
        if (!configFile.exists()) return null

        return try {
            val configJson = configFile.readText()
            val config = gson.fromJson(configJson, SingBoxConfig::class.java)
            cacheConfig(profileId, config)
            config
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config for profile: $profileId", e)
            null
        }
    }

    /**
     * 保存单个配置文件
     */
    fun saveConfig(profileId: String, config: SingBoxConfig): Boolean {
        return try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(config))
            cacheConfig(profileId, config)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config for profile: $profileId", e)
            false
        }
    }

    /**
     * 删除配置文件
     */
    fun deleteConfig(profileId: String): Boolean {
        removeCachedConfig(profileId)
        val configFile = File(configDir, "$profileId.json")
        return if (configFile.exists()) {
            configFile.delete()
        } else {
            true
        }
    }

    /**
     * 缓存配置
     */
    fun cacheConfig(profileId: String, config: SingBoxConfig) {
        synchronized(configCache) {
            configCache[profileId] = config
        }
    }

    /**
     * 移除缓存的配置
     */
    fun removeCachedConfig(profileId: String) {
        synchronized(configCache) {
            configCache.remove(profileId)
        }
    }

    /**
     * 获取缓存的配置
     */
    fun getCachedConfig(profileId: String): SingBoxConfig? {
        synchronized(configCache) {
            return configCache[profileId]
        }
    }

    /**
     * 清除所有缓存
     */
    fun clearCache() {
        synchronized(configCache) {
            configCache.clear()
        }
    }

    /**
     * 检查是否需要从 JSON 迁移到 Kryo
     */
    fun needsMigration(): Boolean {
        return profilesFileJson.exists() && !profilesFileKryo.exists()
    }
}
