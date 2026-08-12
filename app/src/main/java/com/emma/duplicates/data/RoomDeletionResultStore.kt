package com.emma.duplicates.data

import com.emma.duplicates.core.database.PendingDeletionItemEntity
import com.emma.duplicates.core.database.PendingDeletionOperationEntity
import com.emma.duplicates.domain.deletion.DeletionDatabaseUpdate
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DeletionGroup
import com.emma.duplicates.domain.deletion.DeletionResultStore
import com.emma.duplicates.domain.deletion.PendingDeletionFile
import com.emma.duplicates.domain.deletion.PendingDeletionOperation
import java.util.UUID

class RoomDeletionResultStore(
    private val scanStore: ScanStore,
) : DeletionResultStore {
    override suspend fun beginDeletion(groups: List<DeletionGroup>): String {
        val operationId = UUID.randomUUID().toString()
        val selectedFiles = groups.flatMap { group ->
            group.files.filter { it.selectedForDeletion }.map { file -> group.id to file }
        }
        scanStore.beginDeletion(
            operation = PendingDeletionOperationEntity(operationId, System.currentTimeMillis()),
            items = selectedFiles.map { (groupId, file) ->
                PendingDeletionItemEntity(
                    operationId = operationId,
                    groupId = groupId,
                    memberId = file.memberId,
                    indexedFileId = file.id,
                    canonicalPath = file.canonicalPath,
                    sizeBytes = file.sizeBytes,
                    lastModifiedMillis = file.lastModifiedMillis,
                    contentUri = file.contentUri,
                )
            },
        )
        return operationId
    }

    override suspend fun pendingDeletions(): List<PendingDeletionOperation> =
        scanStore.pendingDeletions().map { pending ->
            PendingDeletionOperation(
                id = pending.operation.id,
                files = pending.items.map { item ->
                    PendingDeletionFile(
                        groupId = item.groupId,
                        memberId = item.memberId,
                        file = DeletionFile(
                            id = item.indexedFileId,
                            memberId = item.memberId,
                            canonicalPath = item.canonicalPath,
                            sizeBytes = item.sizeBytes,
                            lastModifiedMillis = item.lastModifiedMillis,
                            contentUri = item.contentUri,
                            selectedForDeletion = true,
                        ),
                    )
                },
            )
        }

    override suspend fun reconcileDeletion(
        operationId: String,
        missingFileIds: Set<String>,
    ) {
        scanStore.reconcileDeletion(operationId, missingFileIds)
    }

    override suspend fun checkpointDeletion(
        operationId: String,
        update: DeletionDatabaseUpdate,
    ) {
        scanStore.applyDeletion(update)
    }

    override suspend fun completeDeletion(
        operationId: String,
        update: DeletionDatabaseUpdate,
    ) {
        scanStore.completeDeletion(operationId, update)
    }

    override suspend fun applyDeletion(update: DeletionDatabaseUpdate) {
        scanStore.applyDeletion(update)
    }
}
