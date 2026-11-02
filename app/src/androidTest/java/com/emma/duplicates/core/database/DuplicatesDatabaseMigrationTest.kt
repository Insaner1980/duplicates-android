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
