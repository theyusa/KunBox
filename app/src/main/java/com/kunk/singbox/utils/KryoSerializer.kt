package com.kunk.singbox.utils

import android.util.Log
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.CollectionSerializer
import com.esotericsoftware.kryo.serializers.MapSerializer
import com.kunk.singbox.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Kryo 二进制序列化工具
 * 相比 JSON 序列化速度快 5-10x，体积小 50-70%
 *
 * 文件格式 (v2+):
 * [MAGIC: 4 bytes "KBOX"] [VERSION: 2 bytes] [DATA: Kryo serialized]
 */
object KryoSerializer {
    private const val TAG = "KryoSerializer"

    // 魔数: "KBOX" 用于识别文件格式
    private val MAGIC = byteArrayOf('K'.code.toByte(), 'B'.code.toByte(), 'O'.code.toByte(), 'X'.code.toByte())

    // 当前数据格式版本 - 修改数据结构时递增此值
    const val CURRENT_VERSION = 1

    // 版本迁移器注册表
    private val migrators = mutableMapOf<Int, DataMigrator>()

    /**
     * 数据迁移器接口
     */
    interface DataMigrator {
        /**
         * 将数据从当前版本迁移到下一版本
         * @param data 原始数据对象
         * @return 迁移后的数据对象
         */
        fun migrate(data: Any?): Any?
    }

    /**
     * 注册版本迁移器
     * @param fromVersion 源版本号
     * @param migrator 迁移器实现
     */
    fun registerMigrator(fromVersion: Int, migrator: DataMigrator) {
        migrators[fromVersion] = migrator
        Log.d(TAG, "Registered migrator for version $fromVersion -> ${fromVersion + 1}")
    }

    /**
     * 注册版本迁移器 (Lambda 简化版)
     */
    fun registerMigrator(fromVersion: Int, migrate: (Any?) -> Any?) {
        registerMigrator(fromVersion, object : DataMigrator {
            override fun migrate(data: Any?): Any? = migrate(data)
        })
    }

    // 使用 ThreadLocal 保证线程安全，Kryo 实例不是线程安全的
    private val kryoThreadLocal = object : ThreadLocal<Kryo>() {
        override fun initialValue(): Kryo {
            return createKryo()
        }
    }

    private fun createKryo(): Kryo {
        return Kryo().apply {
            // 允许未注册的类（更灵活，但稍慢）
            isRegistrationRequired = false

            // 注册常用类以获得更好的性能
            register(SavedProfilesData::class.java)
            register(ProfileUi::class.java)
            register(ProfileType::class.java)
            register(UpdateStatus::class.java)
            register(NodeUi::class.java)
            register(SingBoxConfig::class.java)
            register(Outbound::class.java)
            register(TlsConfig::class.java)
            register(TransportConfig::class.java)
            register(UtlsConfig::class.java)
            register(RealityConfig::class.java)
            register(EchConfig::class.java)
            register(ObfsConfig::class.java)
            register(MultiplexConfig::class.java)
            register(WireGuardPeer::class.java)

            // 注册集合类型
            register(ArrayList::class.java)
            register(HashMap::class.java)
            register(LinkedHashMap::class.java)
            register(emptyList<Any>().javaClass)
            register(emptyMap<Any, Any>().javaClass)

            // 设置引用跟踪（避免循环引用问题）
            references = true
        }
    }

    private val kryo: Kryo
        get() = kryoThreadLocal.get()!!

    /**
     * 序列化对象到字节数组
     */
    fun <T> serialize(obj: T): ByteArray {
        val outputStream = ByteArrayOutputStream(4096)
        Output(outputStream).use { output ->
            kryo.writeClassAndObject(output, obj)
        }
        return outputStream.toByteArray()
    }

    /**
     * 从字节数组反序列化对象
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> deserialize(bytes: ByteArray): T? {
        return try {
            Input(ByteArrayInputStream(bytes)).use { input ->
                kryo.readClassAndObject(input) as? T
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deserialization failed", e)
            null
        }
    }

    /**
     * 带版本号序列化对象到文件
     * 文件格式: [MAGIC: 4 bytes] [VERSION: 2 bytes] [DATA: Kryo serialized]
     */
    fun <T> serializeToFile(obj: T, file: File): Boolean {
        return serializeToFileVersioned(obj, file, CURRENT_VERSION)
    }

    /**
     * 带指定版本号序列化对象到文件
     */
    fun <T> serializeToFileVersioned(obj: T, file: File, version: Int): Boolean {
        return try {
            val tmpFile = File(file.parent, "${file.name}.tmp")
            tmpFile.outputStream().use { fos ->
                // 写入魔数
                fos.write(MAGIC)
                // 写入版本号 (2 bytes, big-endian)
                fos.write((version shr 8) and 0xFF)
                fos.write(version and 0xFF)
                // 写入数据
                Output(fos, 8192).use { output ->
                    kryo.writeClassAndObject(output, obj)
                }
            }

            // 原子替换
            if (tmpFile.exists() && tmpFile.length() > 0) {
                if (file.exists()) {
                    file.delete()
                }
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
                true
            } else {
                Log.e(TAG, "Serialization produced empty file")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize to file: ${file.name}", e)
            false
        }
    }

    /**
     * 从文件反序列化对象，自动处理版本迁移
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> deserializeFromFile(file: File): T? {
        if (!file.exists()) return null

        return try {
            file.inputStream().use { fis ->
                val header = ByteArray(6)
                val headerRead = fis.read(header)

                // 检查是否为新格式 (带魔数)
                val (version, dataStream) = if (headerRead >= 6 && header.sliceArray(0..3).contentEquals(MAGIC)) {
                    // 新格式: 读取版本号
                    val ver = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                    Log.d(TAG, "Reading versioned file: version=$ver, current=$CURRENT_VERSION")
                    ver to fis
                } else {
                    // 旧格式 (无魔数): 视为版本 0，需要重新读取整个文件
                    Log.d(TAG, "Reading legacy file (no magic), treating as version 0")
                    fis.close()
                    0 to file.inputStream()
                }

                // 反序列化数据
                var data: Any? = Input(dataStream, 8192).use { input ->
                    kryo.readClassAndObject(input)
                }

                // 执行版本迁移
                if (version < CURRENT_VERSION) {
                    data = migrateData(data, version, CURRENT_VERSION)
                    // 迁移成功后，重新保存为新版本格式
                    if (data != null) {
                        Log.i(TAG, "Data migrated from v$version to v$CURRENT_VERSION, saving...")
                        serializeToFile(data as T, file)
                    }
                }

                data as? T
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize from file: ${file.name}", e)
            null
        }
    }

    /**
     * 执行数据迁移，从 fromVersion 逐步迁移到 toVersion
     */
    private fun migrateData(data: Any?, fromVersion: Int, toVersion: Int): Any? {
        var currentData = data
        var currentVersion = fromVersion

        while (currentVersion < toVersion) {
            val migrator = migrators[currentVersion]
            if (migrator != null) {
                Log.i(TAG, "Migrating data from v$currentVersion to v${currentVersion + 1}")
                currentData = migrator.migrate(currentData)
                if (currentData == null) {
                    Log.e(TAG, "Migration from v$currentVersion failed, data is null")
                    return null
                }
            } else {
                Log.w(TAG, "No migrator for v$currentVersion, skipping")
            }
            currentVersion++
        }

        return currentData
    }

    /**
     * 读取文件版本号，不反序列化数据
     * @return 版本号，-1 表示无法读取或非 Kryo 文件
     */
    fun getFileVersion(file: File): Int {
        if (!file.exists() || file.length() < 6) return -1

        return try {
            file.inputStream().use { fis ->
                val header = ByteArray(6)
                if (fis.read(header) < 6) return -1

                if (header.sliceArray(0..3).contentEquals(MAGIC)) {
                    ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                } else {
                    0 // 旧格式，视为版本 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file version: ${file.name}", e)
            -1
        }
    }

    /**
     * 检查文件是否为 Kryo 格式
     */
    fun isKryoFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1) return false

        return try {
            file.inputStream().use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)

                // 新格式: 检查魔数
                if (read >= 4 && header.contentEquals(MAGIC)) {
                    return true
                }

                // 旧格式: JSON 文件通常以 '{' 或 '[' 开头
                val firstByte = header[0].toInt()
                firstByte != '{'.code && firstByte != '['.code &&
                firstByte != ' '.code && firstByte != '\n'.code &&
                firstByte != '\r'.code && firstByte != '\t'.code
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查文件是否需要迁移
     */
    fun needsMigration(file: File): Boolean {
        val version = getFileVersion(file)
        return version in 0 until CURRENT_VERSION
    }
}
