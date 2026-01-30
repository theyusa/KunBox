package com.kunk.singbox.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kunk.singbox.database.dao.ActiveStateDao
import com.kunk.singbox.database.dao.NodeDao
import com.kunk.singbox.database.dao.NodeLatencyDao
import com.kunk.singbox.database.dao.ProfileDao
import com.kunk.singbox.database.dao.SettingsDao
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.database.entity.SettingsEntity

/**
 * 应用数据库
 *
 * 使用 Room 存储 Profile、Node 和 Settings 数据
 *
 * 优势：
 * - 支持高效的查询和过滤
 * - 支持 Flow 实时观察数据变化
 * - 支持索引加速查询
 * - 内置事务支持
 */
@Database(
    entities = [
        ProfileEntity::class,
        NodeEntity::class,
        ActiveStateEntity::class,
        NodeLatencyEntity::class,
        SettingsEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun nodeDao(): NodeDao
    abstract fun activeStateDao(): ActiveStateDao
    abstract fun nodeLatencyDao(): NodeLatencyDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        private const val DATABASE_NAME = "singbox.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .allowMainThreadQueries() // 设置加载需要同步读取
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }

        /**
         * 数据库迁移: v1 -> v2 (添加 settings 表)
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        version INTEGER NOT NULL,
                        data TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * 数据库迁移: v2 -> v3 (移除 node_latencies 外键约束)
         * 由于 SQLite 不支持直接删除外键，需要重建表
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS node_latencies_new (
                        nodeId TEXT NOT NULL PRIMARY KEY,
                        latencyMs INTEGER NOT NULL,
                        testedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT OR IGNORE INTO node_latencies_new (nodeId, latencyMs, testedAt)
                    SELECT nodeId, latencyMs, testedAt FROM node_latencies
                """.trimIndent())
                database.execSQL("DROP TABLE IF EXISTS node_latencies")
                database.execSQL("ALTER TABLE node_latencies_new RENAME TO node_latencies")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_node_latencies_nodeId ON node_latencies(nodeId)")
            }
        }

        /**
         * 数据库迁移: v3 -> v4 (添加 DNS 预解析字段)
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE profiles ADD COLUMN dnsPreResolve INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE profiles ADD COLUMN dnsServer TEXT DEFAULT NULL")
            }
        }

        /**
         * 仅用于测试
         */
        fun getInMemoryDatabase(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            ).build()
        }
    }
}
