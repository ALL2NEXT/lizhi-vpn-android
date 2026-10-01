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
    entities = [Node::class, NodeGroupEntity::class, NodeGroupMemberEntity::class],
    version = 9,
    exportSchema = false
)
@TypeConverters(NodeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    
    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "firefly_vpn.db"
        // 清理旧版收藏节点及其专属字段，升级后只保留订阅组和本地用户组数据。
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM node_group_members WHERE nodeId IN " +
                        "(SELECT id FROM nodes WHERE source = 'FAVORITE')"
                )
                db.execSQL("DELETE FROM nodes WHERE source = 'FAVORITE'")
                db.execSQL("ALTER TABLE nodes DROP COLUMN favoriteSourceNodeId")
                db.execSQL("ALTER TABLE nodes DROP COLUMN favoriteCreatedAt")
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
                .addMigrations(MIGRATION_8_9)
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
            val dbFile = context.getDatabasePath(DB_NAME)
            context.deleteDatabase(DB_NAME)
            val parent = dbFile.parentFile ?: return
            deleteIfExists(File(parent, "$DB_NAME-wal"))
            deleteIfExists(File(parent, "$DB_NAME-shm"))
            deleteIfExists(File(parent, "$DB_NAME-journal"))
        }
    }
}
