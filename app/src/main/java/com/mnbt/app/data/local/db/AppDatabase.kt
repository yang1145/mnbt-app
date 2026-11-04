package com.mnbt.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HostEntity::class, SshSessionEntity::class, CloudSiteEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun hostDao(): HostDao

    abstract fun sshSessionDao(): SshSessionDao

    abstract fun cloudSiteDao(): CloudSiteDao

    companion object {
        private const val DB_NAME = "mnbt.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE hosts ADD COLUMN needs_repair INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v3：新增云站点表（ZJMF-CBAP 对接）。全新建表 + base_url 唯一索引，无既有数据要搬。
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cloud_sites` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`base_url` TEXT NOT NULL, " +
                        "`token_ref` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`module_hint` TEXT, " +
                        "`paired_at` INTEGER NOT NULL)",
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_sites_base_url` ON `cloud_sites` (`base_url`)",
                )
            }
        }

        // v4：云开放协议 v2——cloud_sites 去掉 module_hint（模块探测已废除），
        // 新增 system_type（服务商系统标识，存量 ZJMF 站点回填 'zjmf'）与 capabilities_json
        //（能力协商缓存）。SQLite DROP COLUMN 需 3.35+（Android 12），低版本设备走标准建表迁移。
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cloud_sites_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`base_url` TEXT NOT NULL, " +
                        "`token_ref` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`system_type` TEXT NOT NULL, " +
                        "`capabilities_json` TEXT, " +
                        "`paired_at` INTEGER NOT NULL)",
                )
                database.execSQL(
                    "INSERT INTO `cloud_sites_new` " +
                        "(`id`, `name`, `base_url`, `token_ref`, `status`, `system_type`, `capabilities_json`, `paired_at`) " +
                        "SELECT `id`, `name`, `base_url`, `token_ref`, `status`, 'zjmf', NULL, `paired_at` FROM `cloud_sites`",
                )
                database.execSQL("DROP TABLE `cloud_sites`")
                database.execSQL("ALTER TABLE `cloud_sites_new` RENAME TO `cloud_sites`")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_sites_base_url` ON `cloud_sites` (`base_url`)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
