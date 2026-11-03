package com.emma.duplicates.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val PRE_TOOLS_VERSION = 4
private const val TOOLS_VERSION = 5

val TOOL_MIGRATION_4_5 =
    object : Migration(PRE_TOOLS_VERSION, TOOLS_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tool_sessions (
                    id TEXT NOT NULL PRIMARY KEY, tool TEXT NOT NULL, startedAt INTEGER NOT NULL,
                    scope TEXT NOT NULL, status TEXT NOT NULL, completedAt INTEGER, active INTEGER NOT NULL,
                    complete INTEGER NOT NULL, errorCount INTEGER NOT NULL, skippedCount INTEGER NOT NULL,
                    failureReason TEXT, phase TEXT NOT NULL, visitedCount INTEGER NOT NULL,
                    totalBytes INTEGER, freeBytes INTEGER)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_sessions_tool_active ON tool_sessions(tool, active)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tool_items (
                    sessionId TEXT NOT NULL, id TEXT NOT NULL, volume TEXT NOT NULL, canonicalPath TEXT NOT NULL,
                    name TEXT NOT NULL, sizeBytes INTEGER NOT NULL, modifiedAt INTEGER NOT NULL, category TEXT NOT NULL,
                    mimeType TEXT, contentUri TEXT, directory INTEGER NOT NULL, selected INTEGER NOT NULL,
                    valid INTEGER NOT NULL, groupId TEXT, keeper INTEGER NOT NULL, width INTEGER, height INTEGER,
                    fingerprint INTEGER, fingerprintVersion INTEGER, allocatedBytes INTEGER,
                    PRIMARY KEY(sessionId, id), FOREIGN KEY(sessionId) REFERENCES tool_sessions(id) ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tool_items_volume_canonicalPath ON tool_items(volume, canonicalPath)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cleanup_operations (
                    id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, tool TEXT NOT NULL, createdAt INTEGER NOT NULL,
                    FOREIGN KEY(sessionId) REFERENCES tool_sessions(id) ON DELETE NO ACTION)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cleanup_operations_sessionId ON cleanup_operations(sessionId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cleanup_operation_items (
                    operationId TEXT NOT NULL, itemId TEXT NOT NULL, volume TEXT NOT NULL, canonicalPath TEXT NOT NULL,
                    sizeBytes INTEGER NOT NULL, modifiedAt INTEGER NOT NULL, directory INTEGER NOT NULL,
                    digest TEXT, identityKey TEXT, recoveryId TEXT, status TEXT NOT NULL,
                    PRIMARY KEY(operationId, itemId),
                    FOREIGN KEY(operationId) REFERENCES cleanup_operations(id) ON DELETE CASCADE)
                """.trimIndent(),
            )
        }
    }
