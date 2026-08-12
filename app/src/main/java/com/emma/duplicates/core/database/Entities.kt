package com.emma.duplicates.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

object ScanStatuses {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELED = "canceled"
}

object ScanFailureReasons {
    const val PERMISSION_REVOKED = "permission_revoked"
    const val STORAGE_VOLUME_UNAVAILABLE = "storage_volume_unavailable"
    const val STORAGE_FULL = "storage_full"
    const val DATABASE = "database"
    const val GENERIC = "generic"
    const val FOREGROUND_WORKER = "foreground_worker"
}

object ScanPhases {
    const val FINDING_FILES = "finding_files"
    const val COMPARING_CANDIDATES = "comparing_candidates"
    const val VERIFYING_DUPLICATES = "verifying_duplicates"
}

object ExclusionTypes {
    const val FILE = "file"
    const val FOLDER = "folder"
}

@Entity(
    tableName = "scan_sessions",
    indices = [
        Index(value = ["active", "status"]),
        Index(value = ["status", "startedAt"]),
    ],
)
data class ScanSessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val status: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val scannedFileCount: Int = 0,
    val scannedByteCount: Long = 0L,
    val duplicateFileCount: Int = 0,
    val duplicateGroupCount: Int = 0,
    val reclaimableBytes: Long = 0L,
    val skippedFileCount: Int = 0,
    val errorCount: Int = 0,
    val active: Boolean = false,
    val canceled: Boolean = false,
    val phase: String? = null,
    val currentPath: String? = null,
    val progressCompletedWork: Long = 0L,
    val progressTotalWork: Long? = null,
    val candidateGroupCount: Int = 0,
    val failureReason: String? = null,
)

@Entity(
    tableName = "indexed_files",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "canonicalPath"], unique = true),
        Index(value = ["sessionId", "category"]),
        Index(value = ["sessionId", "sizeBytes"]),
        Index(value = ["sessionId", "fullHash"]),
    ],
)
data class IndexedFileEntity(
    @androidx.room.PrimaryKey val id: String,
    val sessionId: String,
    val canonicalPath: String,
    val displayName: String,
    val extension: String,
    val mimeType: String?,
    val category: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val volume: String,
    val parentPath: String,
    val contentUri: String? = null,
    val quickHash: String? = null,
    val fullHash: String? = null,
    val readable: Boolean,
    val writable: Boolean,
    val favorite: Boolean? = null,
    val trashed: Boolean? = null,
    val changedSinceScan: Boolean = false,
)

@Entity(
    tableName = "duplicate_groups",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "contentHash", "fileSize"], unique = true),
        Index(value = ["sessionId", "category"]),
    ],
)
data class DuplicateGroupEntity(
    @androidx.room.PrimaryKey val id: String,
    val sessionId: String,
    val category: String,
    val contentHash: String,
    val fileSize: Long,
    val copyCount: Int,
    val reclaimableBytes: Long,
    val displayTitle: String,
)

@Entity(
    tableName = "duplicate_members",
    foreignKeys = [
        ForeignKey(
            entity = DuplicateGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = IndexedFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["indexedFileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "indexedFileId"], unique = true),
        Index(value = ["indexedFileId"]),
        Index(value = ["groupId", "selectedForDeletion"]),
    ],
)
data class DuplicateMemberEntity(
    @androidx.room.PrimaryKey val id: String,
    val groupId: String,
    val indexedFileId: String,
    val recommendedKeep: Boolean,
    val selectedForDeletion: Boolean,
    val protectedFromAutoSelection: Boolean,
)

@Entity(tableName = "pending_deletion_operations")
data class PendingDeletionOperationEntity(
    @androidx.room.PrimaryKey val id: String,
    val createdAt: Long,
)

@Entity(
    tableName = "pending_deletion_items",
    primaryKeys = ["operationId", "memberId"],
    foreignKeys = [
        ForeignKey(
            entity = PendingDeletionOperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationId"]),
        Index(value = ["groupId"]),
    ],
)
data class PendingDeletionItemEntity(
    val operationId: String,
    val groupId: String,
    val memberId: String,
    val indexedFileId: String,
    val canonicalPath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val contentUri: String?,
)

@Entity(
    tableName = "fingerprint_cache",
    primaryKeys = ["canonicalPath", "volume"],
    indices = [Index(value = ["sizeBytes", "lastModified"])],
)
data class FingerprintCacheEntity(
    val canonicalPath: String,
    val volume: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val quickHash: String,
    val fullHash: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "exclusions",
    indices = [
        Index(value = ["canonicalPath"], unique = true),
        Index(value = ["type", "createdAt"]),
    ],
)
data class ExclusionEntity(
    @androidx.room.PrimaryKey val id: String,
    val type: String,
    val canonicalPath: String,
    val displayName: String,
    val createdAt: Long,
)

data class ScanCompletionMetrics(
    val scannedFileCount: Int,
    val scannedByteCount: Long,
    val duplicateFileCount: Int,
    val duplicateGroupCount: Int,
    val reclaimableBytes: Long,
    val skippedFileCount: Int,
    val errorCount: Int,
)

data class ScanProgressUpdate(
    val phase: String,
    val currentPath: String? = null,
    val completedWork: Long,
    val totalWork: Long?,
    val scannedFileCount: Int,
    val scannedByteCount: Long,
    val candidateGroupCount: Int = 0,
    val duplicateGroupCount: Int = 0,
    val reclaimableBytes: Long = 0L,
    val skippedFileCount: Int,
    val errorCount: Int,
)
