package com.emma.duplicates.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "tool_sessions", indices = [Index(value = ["tool", "active"])])
data class ToolScanSession(
    @PrimaryKey val id: String,
    val tool: String,
    val startedAt: Long,
    val scope: String,
    val status: String = ScanStatuses.RUNNING,
    val completedAt: Long? = null,
    val active: Boolean = false,
    val complete: Boolean = true,
    val errorCount: Int = 0,
    val skippedCount: Int = 0,
    val failureReason: String? = null,
    val phase: String = ScanPhases.FINDING_FILES,
    val visitedCount: Int = 0,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
)

@Entity(
    tableName = "tool_items",
    primaryKeys = ["sessionId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ToolScanSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["volume", "canonicalPath"])],
)
data class ToolItemEntity(
    val sessionId: String,
    val id: String,
    val volume: String,
    val canonicalPath: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val category: String,
    val mimeType: String? = null,
    val contentUri: String? = null,
    val directory: Boolean = false,
    val selected: Boolean = false,
    val valid: Boolean = true,
    val groupId: String? = null,
    val keeper: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val fingerprint: Long? = null,
    val fingerprintVersion: Int? = null,
    val allocatedBytes: Long? = null,
)

data class ToolResults(
    @Embedded val session: ToolScanSession,
    @Relation(parentColumn = "id", entityColumn = "sessionId") val items: List<ToolItemEntity>,
)

@Entity(
    tableName = "cleanup_operations",
    foreignKeys = [
        ForeignKey(
            entity = ToolScanSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class CleanupOperation(
    @PrimaryKey val id: String,
    val sessionId: String,
    val tool: String,
    val createdAt: Long,
)

@Entity(
    tableName = "cleanup_operation_items",
    primaryKeys = ["operationId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = CleanupOperation::class,
            parentColumns = ["id"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CleanupOperationItem(
    val operationId: String,
    val itemId: String,
    val volume: String,
    val canonicalPath: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val directory: Boolean,
    val digest: String?,
    val identityKey: String?,
    val recoveryId: String?,
    val status: String = "pending",
)

data class CleanupOperationWithItems(
    @Embedded val operation: CleanupOperation,
    @Relation(parentColumn = "id", entityColumn = "operationId") val items: List<CleanupOperationItem>,
)
