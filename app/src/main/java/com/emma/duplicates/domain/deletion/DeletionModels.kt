package com.emma.duplicates.domain.deletion

data class DeletionFile(
    val id: String,
    val memberId: String = id,
    val canonicalPath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val contentUri: String? = null,
    val requiresMediaAuthorization: Boolean = false,
    val selectedForDeletion: Boolean,
)

data class PendingDeletionFile(
    val groupId: String,
    val memberId: String,
    val file: DeletionFile,
)

data class PendingDeletionOperation(
    val id: String,
    val files: List<PendingDeletionFile>,
)

data class DeletionGroup(
    val id: String,
    val expectedFullHash: String,
    val files: List<DeletionFile>,
)

data class CurrentFileSnapshot(
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val isReadable: Boolean,
    val isWritable: Boolean,
)

enum class DirectDeletionResult {
    DELETED,
    FAILED,
}

enum class MediaAuthorization {
    APPROVED,
    CANCELED,
    FAILED,
}

interface DeletionStorage {
    /** Returns null only when the file is confirmed absent; inaccessible files use false access flags. */
    suspend fun snapshot(file: DeletionFile): CurrentFileSnapshot?

    suspend fun fullHash(file: DeletionFile): String?

    suspend fun contentsEqual(first: DeletionFile, second: DeletionFile): Boolean

    suspend fun deleteDirect(file: DeletionFile): DirectDeletionResult
}

/** Returns only after the system deletion request has completed or been canceled. */
interface MediaDeletionAuthorizer {
    suspend fun requestDeletion(files: List<DeletionFile>): MediaAuthorization
}

/** Applies member removals and affected-group/session totals in one database transaction. */
interface DeletionResultStore {
    suspend fun beginDeletion(groups: List<DeletionGroup>): String = ""

    suspend fun checkpointDeletion(
        operationId: String,
        update: DeletionDatabaseUpdate,
    ) {
        applyDeletion(update)
    }

    suspend fun completeDeletion(
        operationId: String,
        update: DeletionDatabaseUpdate,
    ) {
        applyDeletion(update)
    }

    suspend fun pendingDeletions(): List<PendingDeletionOperation> = emptyList()

    suspend fun reconcileDeletion(
        operationId: String,
        missingFileIds: Set<String>,
    ) = Unit

    suspend fun applyDeletion(update: DeletionDatabaseUpdate)
}

enum class DeletionStatus {
    DELETED,
    VANISHED,
    CHANGED,
    REVALIDATION_FAILED,
    NOT_ACCESSIBLE,
    AUTHORIZATION_CANCELED,
    DELETE_FAILED,
    BLOCKED_NO_VALID_COPY,
}

data class FileDeletionOutcome(
    val groupId: String,
    val fileId: String,
    val status: DeletionStatus,
    val reclaimedBytes: Long = 0L,
)

data class GroupDeletionUpdate(
    val groupId: String,
    val remainingMemberIds: Set<String>,
    val remainingCopyCount: Int,
    val remainingSelectedReclaimableBytes: Long,
    val removeGroup: Boolean,
)

data class DeletionDatabaseUpdate(
    val removedIndexedFileIds: Set<String>,
    val removedMemberIds: Set<String>,
    val changedIndexedFileIds: Set<String>,
    val groups: List<GroupDeletionUpdate>,
)

data class DeletionResult(
    val outcomes: List<FileDeletionOutcome>,
    val databaseUpdate: DeletionDatabaseUpdate,
) {
    val deletedFileCount: Int
        get() = outcomes.count { it.status == DeletionStatus.DELETED }

    val failedFileCount: Int
        get() = outcomes.count { it.status != DeletionStatus.DELETED }

    val reclaimedBytes: Long
        get() = outcomes.filter { it.status == DeletionStatus.DELETED }.sumOf { it.reclaimedBytes }
}
