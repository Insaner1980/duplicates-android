package com.emma.duplicates.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val VERSION_SCAN_FAILURE_REASON = 3
private const val VERSION_BYTE_PARTITIONS = 4
private const val DATABASE_VERSION = 5

@Database(
    entities = [
        ScanSessionEntity::class,
        IndexedFileEntity::class,
        DuplicateGroupEntity::class,
        DuplicateMemberEntity::class,
        PendingDeletionOperationEntity::class,
        PendingDeletionItemEntity::class,
        FingerprintCacheEntity::class,
        ExclusionEntity::class,
        RecoveryFile::class,
        ToolScanSession::class,
        ToolItemEntity::class,
        CleanupOperation::class,
        CleanupOperationItem::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
abstract class DuplicatesDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    abstract fun fingerprintDao(): FingerprintDao

    abstract fun deletionOperationDao(): DeletionOperationDao

    abstract fun exclusionDao(): ExclusionDao

    abstract fun recoveryDao(): RecoveryDao

    abstract fun toolDao(): ToolDao

    companion object {
        private const val DATABASE_NAME = "duplicates.db"

        fun create(context: Context): DuplicatesDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    DuplicatesDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        val MIGRATION_4_5 = TOOL_MIGRATION_4_5

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `pending_deletion_operations` (
                            `id` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `pending_deletion_items` (
                            `operationId` TEXT NOT NULL,
                            `groupId` TEXT NOT NULL,
                            `memberId` TEXT NOT NULL,
                            `indexedFileId` TEXT NOT NULL,
                            `canonicalPath` TEXT NOT NULL,
                            `sizeBytes` INTEGER NOT NULL,
                            `lastModifiedMillis` INTEGER NOT NULL,
                            `contentUri` TEXT,
                            PRIMARY KEY(`operationId`, `memberId`),
                            FOREIGN KEY(`operationId`) REFERENCES `pending_deletion_operations`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS `index_pending_deletion_items_operationId`
                        ON `pending_deletion_items` (`operationId`)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS `index_pending_deletion_items_groupId`
                        ON `pending_deletion_items` (`groupId`)
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, VERSION_SCAN_FAILURE_REASON) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `failureReason` TEXT")
                }
            }

        val MIGRATION_3_4 =
            object : Migration(VERSION_SCAN_FAILURE_REASON, VERSION_BYTE_PARTITIONS) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP INDEX IF EXISTS `index_duplicate_groups_sessionId_contentHash_fileSize`")
                    db.execSQL(
                        "CREATE INDEX `index_duplicate_groups_sessionId_contentHash_fileSize` " +
                            "ON `duplicate_groups` (`sessionId`, `contentHash`, `fileSize`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `recovery_files` (" +
                            "`id` TEXT NOT NULL, `originalPath` TEXT NOT NULL, `storedPath` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, `expiresAt` INTEGER, PRIMARY KEY(`id`))",
                    )
                }
            }
    }
}
