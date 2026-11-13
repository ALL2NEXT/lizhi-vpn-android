package io.github.HanJason233.chimera.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeGroupEntity
import io.github.HanJason233.chimera.data.model.NodeGroupMemberEntity
import io.github.HanJason233.chimera.data.model.NodeTypeConverter
import io.github.HanJason233.chimera.util.DatabasePassphraseManager
import java.io.File

@Database(
    entities = [
        Node::class,
        NodeGroupEntity::class,
        NodeGroupMemberEntity::class,
        HomeNodeTestResultsEntity::class
    ],
    version = 17,
    exportSchema = false
)
@TypeConverters(NodeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    
    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "vpn.db"
        private const val LEGACY_DB_NAME = "firefly_vpn.db"
        private val DB_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `blocked_subscription_nodes` (
                        `sourceId` TEXT NOT NULL,
                        `nodeId` TEXT NOT NULL,
                        PRIMARY KEY(`sourceId`, `nodeId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `blocked_subscription_nodes`")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `home_node_test_results` ADD COLUMN `lastTestStage` TEXT DEFAULT NULL"
                )
            }
        }

        @Volatile
        private var sqlCipherLibLoaded = false

        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                ensureSqlCipherLoaded()
                migrateLegacyDatabaseName(appContext)
                val instance = buildEncryptedDatabase(appContext)
                try {
                    // Force open once to validate passphrase/file format, but do not close it here.
                    instance.openHelper.writableDatabase
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to open encrypted database; local data was preserved.", error)
                    runCatching { instance.close() }
                    throw IllegalStateException(
                        "Unable to open encrypted database; local data was preserved.",
                        error
                    )
                }
                INSTANCE = instance
                instance
            }
        }

        /** 注销账户时关闭并删除本地加密数据库。 */
        fun clearAndClose(context: Context) {
            synchronized(this) {
                runCatching { INSTANCE?.close() }
                INSTANCE = null
                deleteDatabaseFiles(context.applicationContext)
            }
        }

        private fun buildEncryptedDatabase(context: Context): AppDatabase {
            val passphrase = DatabasePassphraseManager.getOrCreatePassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                // 发布前开发版允许在表结构变化时重建本地数据库。
                .fallbackToDestructiveMigration()
                .build()
        }

        private fun ensureSqlCipherLoaded() {
            if (sqlCipherLibLoaded) return
            synchronized(this) {
                if (sqlCipherLibLoaded) return
                System.loadLibrary("sqlcipher")
                sqlCipherLibLoaded = true
            }
        }

        private fun deleteIfExists(file: File) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete database sidecar: ${file.absolutePath}")
            }
        }

        private fun deleteDatabaseFiles(context: Context) {
            deleteDatabaseFiles(context, DB_NAME)
            deleteDatabaseFiles(context, LEGACY_DB_NAME)
        }

        private fun deleteDatabaseFiles(context: Context, databaseName: String) {
            val dbFile = context.getDatabasePath(databaseName)
            context.deleteDatabase(databaseName)
            val parent = dbFile.parentFile ?: return
            DB_SIDECAR_SUFFIXES.forEach { suffix ->
                deleteIfExists(File(parent, "$databaseName$suffix"))
            }
        }

        private fun migrateLegacyDatabaseName(context: Context) {
            val legacyFile = context.getDatabasePath(LEGACY_DB_NAME)
            val databaseFile = context.getDatabasePath(DB_NAME)
            if (databaseFile.exists() || !legacyFile.exists()) return

            val movedSidecars = mutableListOf<Pair<File, File>>()
            DB_SIDECAR_SUFFIXES.forEach { suffix ->
                val legacySidecar = File("${legacyFile.path}$suffix")
                val databaseSidecar = File("${databaseFile.path}$suffix")
                if (databaseSidecar.exists()) {
                    if (legacySidecar.exists()) {
                        rollbackMovedSidecars(movedSidecars)
                        throw IllegalStateException(
                            "Both legacy and new database sidecars exist for $suffix."
                        )
                    }
                    // A previous launch may have stopped after moving this sidecar.
                    return@forEach
                }
                if (!legacySidecar.exists()) return@forEach
                if (!legacySidecar.renameTo(databaseSidecar)) {
                    rollbackMovedSidecars(movedSidecars)
                    throw IllegalStateException(
                        "Unable to migrate legacy database sidecar: ${legacySidecar.name}"
                    )
                }
                movedSidecars.add(legacySidecar to databaseSidecar)
            }

            if (!legacyFile.renameTo(databaseFile)) {
                rollbackMovedSidecars(movedSidecars)
                throw IllegalStateException(
                    "Unable to rename legacy database file to $DB_NAME."
                )
            }
            Log.i(TAG, "Renamed legacy database file to $DB_NAME")
        }

        private fun rollbackMovedSidecars(movedSidecars: List<Pair<File, File>>) {
            movedSidecars.asReversed().forEach { (legacySidecar, databaseSidecar) ->
                if (databaseSidecar.exists() && !legacySidecar.exists() &&
                    !databaseSidecar.renameTo(legacySidecar)
                ) {
                    Log.w(TAG, "Failed to restore database sidecar: ${legacySidecar.name}")
                }
            }
        }
    }
}
