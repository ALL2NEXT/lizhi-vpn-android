package xyz.a202132.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeGroupEntity
import xyz.a202132.app.data.model.NodeGroupMemberEntity
import xyz.a202132.app.data.model.NodeTypeConverter
import xyz.a202132.app.util.DatabasePassphraseManager
import java.io.File
import java.io.RandomAccessFile

@Database(
    entities = [Node::class, NodeGroupEntity::class, NodeGroupMemberEntity::class],
    version = 8,
    exportSchema = false
)
@TypeConverters(NodeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    
    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "firefly_vpn.db"
        private val SQLITE_PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `nodes_new` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `server` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `rawLink` TEXT NOT NULL,
                        `country` TEXT,
                        `countryName` TEXT,
                        `latency` INTEGER NOT NULL,
                        `isAvailable` INTEGER NOT NULL,
                        `lastTestedAt` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `downloadMbps` REAL NOT NULL,
                        `uploadMbps` REAL NOT NULL,
                        `autoTestStatus` TEXT NOT NULL,
                        `autoTestedAt` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `favoriteSourceNodeId` TEXT,
                        `favoriteCreatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `nodes_new` (
                        `id`, `name`, `type`, `server`, `port`, `rawLink`, `country`, `countryName`,
                        `latency`, `isAvailable`, `lastTestedAt`, `sortOrder`, `downloadMbps`, `uploadMbps`,
                        `autoTestStatus`, `autoTestedAt`, `source`, `favoriteSourceNodeId`, `favoriteCreatedAt`
                    )
                    SELECT
                        `id`, `name`, `type`, `server`, `port`, `rawLink`, `country`, `countryName`,
                        `latency`, `isAvailable`, `lastTestedAt`, `sortOrder`, `downloadMbps`, `uploadMbps`,
                        `autoTestStatus`, `autoTestedAt`, `source`, `favoriteSourceNodeId`, `favoriteCreatedAt`
                    FROM `nodes`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `nodes`")
                db.execSQL("ALTER TABLE `nodes_new` RENAME TO `nodes`")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `nodes` ADD COLUMN `tcpingLatency` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `nodes` ADD COLUMN `urlTestLatency` INTEGER NOT NULL DEFAULT -1")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `nodes` ADD COLUMN `subscriptionSourceId` TEXT")
                db.execSQL("ALTER TABLE `nodes` ADD COLUMN `subscriptionSourceName` TEXT")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `node_groups` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_node_groups_type` " +
                        "ON `node_groups` (`type`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `node_group_members` (
                        `groupId` TEXT NOT NULL,
                        `nodeId` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `nodeId`),
                        FOREIGN KEY(`groupId`) REFERENCES `node_groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`nodeId`) REFERENCES `nodes`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_node_group_members_nodeId` " +
                        "ON `node_group_members` (`nodeId`)"
                )
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `node_groups` ADD COLUMN `subscriptionUrl` TEXT")
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
                deleteLegacyPlaintextDatabaseIfNeeded(appContext)
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
                .addMigrations(
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8
                )
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

        private fun deleteLegacyPlaintextDatabaseIfNeeded(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            val header = readHeader(dbFile, SQLITE_PLAIN_HEADER.size) ?: return
            if (!header.contentEquals(SQLITE_PLAIN_HEADER)) {
                return
            }

            Log.w(TAG, "Detected legacy plaintext database. Deleting and recreating encrypted database.")
            deleteDatabaseFiles(context)
        }

        private fun readHeader(file: File, length: Int): ByteArray? {
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val buf = ByteArray(length)
                    if (raf.read(buf) == length) buf else null
                }
            }.getOrNull()
        }

        private fun deleteIfExists(file: File) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete database sidecar: ${file.absolutePath}")
            }
        }

        private fun deleteDatabaseFiles(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            context.deleteDatabase(DB_NAME)
            val parent = dbFile.parentFile ?: return
            deleteIfExists(File(parent, "$DB_NAME-wal"))
            deleteIfExists(File(parent, "$DB_NAME-shm"))
            deleteIfExists(File(parent, "$DB_NAME-journal"))
        }
    }
}
