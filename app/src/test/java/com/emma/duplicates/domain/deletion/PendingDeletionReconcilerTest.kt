package com.emma.duplicates.domain.deletion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingDeletionReconcilerTest {
    @Test
    fun `reconciliation reports only files confirmed absent from storage`() = runTest {
        val missing = pendingFile("missing")
        val present = pendingFile("present")
        val inaccessible = pendingFile("inaccessible")
        val operation = PendingDeletionOperation("operation", listOf(missing, present, inaccessible))
        val storage = RecoveryStorage(
            mapOf(
                "present" to snapshot(isReadable = true),
                "inaccessible" to snapshot(isReadable = false),
            ),
        )
        val store = RecoveryStore(operation)

        PendingDeletionReconciler(storage, store).reconcile()

        assertEquals(listOf("operation" to setOf("missing")), store.reconciliations)
    }

    private fun pendingFile(id: String) = PendingDeletionFile(
        groupId = "group",
        memberId = "member-$id",
        file = DeletionFile(
            id = id,
            memberId = "member-$id",
            canonicalPath = "/storage/$id.txt",
            sizeBytes = 100L,
            lastModifiedMillis = 10L,
            selectedForDeletion = true,
        ),
    )

    private fun snapshot(isReadable: Boolean) = CurrentFileSnapshot(
        sizeBytes = 100L,
        lastModifiedMillis = 10L,
        isReadable = isReadable,
        isWritable = isReadable,
    )

    private class RecoveryStorage(
        private val snapshots: Map<String, CurrentFileSnapshot>,
    ) : DeletionStorage {
        override suspend fun snapshot(file: DeletionFile): CurrentFileSnapshot? = snapshots[file.id]

        override suspend fun fullHash(file: DeletionFile): String? = error("Not used")

        override suspend fun contentsEqual(first: DeletionFile, second: DeletionFile): Boolean =
            error("Not used")

        override suspend fun deleteDirect(file: DeletionFile): DirectDeletionResult = error("Not used")
    }

    private class RecoveryStore(
        private val operation: PendingDeletionOperation,
    ) : DeletionResultStore {
        val reconciliations = mutableListOf<Pair<String, Set<String>>>()

        override suspend fun pendingDeletions(): List<PendingDeletionOperation> = listOf(operation)

        override suspend fun reconcileDeletion(
            operationId: String,
            missingFileIds: Set<String>,
        ) {
            reconciliations += operationId to missingFileIds
        }

        override suspend fun applyDeletion(update: DeletionDatabaseUpdate) = Unit
    }
}
