package com.emma.duplicates.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DuplicatesDatabaseMigrationTest {
    @Test
    fun fullSupportedChainAndVersion4PreserveExistingRows() {
        for (version in listOf(1, 4)) {
            val name = "cleanup-migration-$version-5"
            seedLegacyDatabase(name, version)
            migrationHelper
                .runMigrationsAndValidate(
                    name,
                    5,
                    true,
                    DuplicatesDatabase.MIGRATION_1_2,
                    DuplicatesDatabase.MIGRATION_2_3,
                    DuplicatesDatabase.MIGRATION_3_4,
                    DuplicatesDatabase.MIGRATION_4_5,
                ).use { migrated ->
                    assertRows(
                        migrated,
                        listOf(
                            "scan_sessions",
                            "indexed_files",
                            "duplicate_groups",
                            "duplicate_members",
                            "exclusions",
                            "fingerprint_cache",
                        ),
                    )
                    if (version ==
                        4
                    ) {
                        assertRows(
                            migrated,
                            listOf("recovery_files", "pending_deletion_operations", "pending_deletion_items"),
                        )
                    }
                    migrated.query("SELECT COUNT(*) FROM tool_sessions").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                    }
                }
        }
    }

    private fun seedLegacyDatabase(
        name: String,
        version: Int,
    ) {
        migrationHelper.createDatabase(name, version).apply {
            execSQL(
                """
INSERT INTO scan_sessions (
    id,status,startedAt,scannedFileCount,scannedByteCount,duplicateFileCount,duplicateGroupCount,
    reclaimableBytes,skippedFileCount,errorCount,active,canceled,progressCompletedWork,candidateGroupCount
) VALUES ('saved','completed',1,2,20,1,1,10,0,0,1,0,0,0)
                """.trimIndent(),
            )
            execSQL(
                """
INSERT INTO indexed_files (
    id,sessionId,canonicalPath,displayName,extension,category,sizeBytes,lastModified,volume,parentPath,
    readable,writable,changedSinceScan
) VALUES ('file','saved','/storage/a.jpg','a.jpg','jpg','photos',10,1,'primary','/storage',1,1,0)
                """.trimIndent(),
            )
            execSQL("INSERT INTO duplicate_groups VALUES ('group','saved','photos','hash',10,2,10,'a.jpg')")
            execSQL("INSERT INTO duplicate_members VALUES ('member','group','file',1,0,0)")
            execSQL("INSERT INTO exclusions VALUES ('excluded','folder','/storage/private','private',1)")
            execSQL("INSERT INTO fingerprint_cache VALUES ('/storage/a.jpg','primary',10,1,'quick','full',1)")
            if (version == 4) {
                execSQL(
                    "INSERT INTO recovery_files VALUES ('recovery','/storage/a.jpg','/recovery/payload',1,NULL)",
                )
                execSQL("INSERT INTO pending_deletion_operations VALUES ('pending',1)")
                execSQL(
                    "INSERT INTO pending_deletion_items VALUES " +
                        "('pending','group','member','file','/storage/a.jpg',10,1,NULL)",
                )
            }
            close()
        }
    }

    private fun assertRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        tables: List<String>,
    ) {
        for (table in tables) {
            database.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }

    @get:Rule
    val migrationHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            DuplicatesDatabase::class.java,
        )

    @Test
    fun migrationFrom3To4AddsRecoveryAndAllowsIndependentBytePartitions() {
        migrationHelper
            .createDatabase("migration-3-4", 3)
            .close()
        val migrated =
            migrationHelper.runMigrationsAndValidate(
                "migration-3-4",
                4,
                true,
                DuplicatesDatabase.MIGRATION_3_4,
            )
        migrated.query("PRAGMA index_list('duplicate_groups')").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (indexName == "index_duplicate_groups_sessionId_contentHash_fileSize") {
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
                    found = true
                }
            }
            assertTrue(found)
        }
        migrated.close()
    }

    @Test
    fun migrationFrom2To3AddsNullableFailureReasonWithoutChangingExistingSessions() {
        migrationHelper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL(
                """
                INSERT INTO scan_sessions (
                    id, status, startedAt, completedAt, scannedFileCount, scannedByteCount,
                    duplicateFileCount, duplicateGroupCount, reclaimableBytes, skippedFileCount,
                    errorCount, active, canceled, phase, currentPath, progressCompletedWork,
                    progressTotalWork, candidateGroupCount
                ) VALUES (
                    'running', 'running', 10, NULL, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 'finding_files', NULL, 0,
                    NULL, 0
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated =
            migrationHelper.runMigrationsAndValidate(
                DATABASE_NAME,
                3,
                true,
                DuplicatesDatabase.MIGRATION_2_3,
            )

        migrated.query("SELECT status, failureReason FROM scan_sessions WHERE id = 'running'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(ScanStatuses.RUNNING, cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "migration-2-3"
    }
}
