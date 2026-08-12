package com.emma.duplicates.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    ],
    version = 3,
    exportSchema = true,
)
abstract class DuplicatesDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    abstract fun fingerprintDao(): FingerprintDao

    abstract fun deletionOperationDao(): DeletionOperationDao

    abstract fun exclusionDao(): ExclusionDao

    companion object {
        private const val DATABASE_NAME = "duplicates.db"

        fun create(context: Context): DuplicatesDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                DuplicatesDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

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
                            FOREIGN KEY(`operationId`) REFERENCES `pending_deletion_operations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_pending_deletion_items_operationId` ON `pending_deletion_items` (`operationId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_pending_deletion_items_groupId` ON `pending_deletion_items` (`groupId`)",
                    )
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `failureReason` TEXT")
                }
            }
    }
}
