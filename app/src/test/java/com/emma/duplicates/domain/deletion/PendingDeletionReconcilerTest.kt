package com.emma.duplicates.domain.deletion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingDeletionReconcilerTest {
    @Test
    fun `reconciliation reports only files confirmed absent from storage`() =
        runTest {
            val missing = pendingFile("missing")
            val present = pendingFile("present")
            val operation = PendingDeletionOperation("operation", listOf(missing, present))
            val storage =
                RecoveryStorage(
                    mapOf(
                        "present" to snapshot(isReadable = true),
                    ),
                )
            val store = RecoveryStore(operation)

            PendingDeletionReconciler(storage, store).reconcile()

            assertEquals(listOf("operation" to setOf("missing")), store.reconciliations)
        }

    @Test
    fun `unavailable storage retains the journal until the volume returns`() =
        runTest {
            val removable = pendingFile("removable")
            val missing = pendingFile("missing")
            val snapshots = mutableMapOf("removable" to snapshot(isReadable = false))
            val store = RecoveryStore(PendingDeletionOperation("operation", listOf(removable, missing)))
            val reconciler = PendingDeletionReconciler(RecoveryStorage(snapshots), store)

            reconciler.reconcile()

            assertTrue(store.reconciliations.isEmpty())
            assertEquals(listOf("operation"), store.pendingDeletions().map { it.id })
            assertEquals(setOf("removable", "missing"), store.persistedFileIds)

            snapshots["removable"] = snapshot(isReadable = true)
            reconciler.reconcile()

            assertEquals(listOf("operation" to setOf("missing")), store.reconciliations)
            assertEquals(setOf("removable"), store.persistedFileIds)
            assertTrue(store.pendingDeletions().isEmpty())
        }

    @Test
    fun `recovery waits for live deletion and recovers an interrupted direct mutation`() =
        runTest {
            val group =
                DeletionGroup(
                    id = "group",
                    expectedFullHash = "hash",
                    files =
                        listOf(
                            pendingFile("keep").file.copy(selectedForDeletion = false),
                            pendingFile("first").file,
                            pendingFile("second").file,
                        ),
                )
            val snapshots = group.files.associate { it.id to snapshot(isReadable = true) }.toMutableMap()
            val secondDeletionEntered = CompletableDeferred<Unit>()
            val allowSecondDeletion = CompletableDeferred<Unit>()
            val directDeletionCalls = mutableListOf<String>()
            val comparisons = mutableListOf<Pair<String, String>>()
            val storage =
                object : DeletionStorage by RecoveryStorage(snapshots) {
                    override suspend fun contentsEqual(
                        first: DeletionFile,
                        second: DeletionFile,
                    ): Boolean {
                        comparisons += first.id to second.id
                        return first.id in snapshots && second.id in snapshots
                    }

                    override suspend fun deleteDirect(
                        file: DeletionFile,
                        verifyStaged: suspend (DeletionFile) -> Boolean,
                    ): DirectDeletionResult {
                        directDeletionCalls += file.id
                        if (file.id == "second") {
                            secondDeletionEntered.complete(Unit)
                            allowSecondDeletion.await()
                        }
                        snapshots.remove(file.id)
                        if (file.id == "second") throw CancellationException("Interrupted after direct deletion")
                        return DirectDeletionResult.DELETED
                    }
                }
            val store = RecoveryStore()
            val operationMutex = Mutex()
            val coordinator =
                DeletionCoordinator(
                    storage,
                    MediaDeletionAuthorizer { error("No media authorization expected") },
                    store,
                    operationMutex = operationMutex,
                )
            val reconciler = PendingDeletionReconciler(storage, store, operationMutex)
            val deletion = launch { coordinator.delete(listOf(group)) }
            secondDeletionEntered.await()
            val recovery = launch { reconciler.reconcile() }

            try {
                runCurrent()

                assertEquals(listOf("operation"), store.pendingDeletions().map { it.id })
                assertFalse(recovery.isCompleted)
                assertEquals(setOf("keep", "second"), store.persistedFileIds)

                allowSecondDeletion.complete(Unit)
                deletion.join()
                recovery.join()

                assertTrue(deletion.isCancelled)
                assertEquals(listOf(setOf("first")), store.appliedUpdates.map { it.removedIndexedFileIds })
                assertEquals(listOf("operation" to setOf("first", "second")), store.reconciliations)
                assertEquals(setOf("keep"), store.persistedFileIds)
                assertTrue(store.pendingDeletions().isEmpty())
                assertEquals(listOf("first", "second"), directDeletionCalls)
                assertEquals(listOf("first" to "keep", "second" to "keep"), comparisons)
            } finally {
                allowSecondDeletion.complete(Unit)
                deletion.cancelAndJoin()
                recovery.cancelAndJoin()
            }
        }

    private fun pendingFile(id: String) =
        PendingDeletionFile(
            groupId = "group",
            memberId = "member-$id",
            file =
                DeletionFile(
                    id = id,
                    memberId = "member-$id",
                    canonicalPath = "/storage/$id.txt",
                    sizeBytes = 100L,
                    lastModifiedMillis = 10L,
                    selectedForDeletion = true,
                ),
        )

    private fun snapshot(isReadable: Boolean) =
        CurrentFileSnapshot(
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

        override suspend fun contentsEqual(
            first: DeletionFile,
            second: DeletionFile,
        ): Boolean = error("Not used")

        override suspend fun deleteDirect(
            file: DeletionFile,
            verifyStaged: suspend (DeletionFile) -> Boolean,
        ): DirectDeletionResult = error("Not used")
    }

    private class RecoveryStore(
        private var operation: PendingDeletionOperation? = null,
    ) : DeletionResultStore {
        val reconciliations = mutableListOf<Pair<String, Set<String>>>()
        val appliedUpdates = mutableListOf<DeletionDatabaseUpdate>()
        val persistedFileIds = operation?.files.orEmpty().mapTo(linkedSetOf()) { it.file.id }

        override suspend fun beginDeletion(groups: List<DeletionGroup>): String {
            persistedFileIds += groups.flatMap { group -> group.files.map { it.id } }
            operation =
                PendingDeletionOperation(
                    id = "operation",
                    files =
                        groups.flatMap { group ->
                            group.files.filter { it.selectedForDeletion }.map { file ->
                                PendingDeletionFile(group.id, file.memberId, file)
                            }
                        },
                )
            return "operation"
        }

        override suspend fun pendingDeletions(): List<PendingDeletionOperation> = listOfNotNull(operation)

        override suspend fun reconcileDeletion(
            operationId: String,
            missingFileIds: Set<String>,
        ) {
            reconciliations += operationId to missingFileIds
            persistedFileIds.removeAll(missingFileIds)
            operation = null
        }

        override suspend fun completeDeletion(
            operationId: String,
            update: DeletionDatabaseUpdate,
        ) {
            applyDeletion(update)
            operation = null
        }

        override suspend fun applyDeletion(update: DeletionDatabaseUpdate) {
            appliedUpdates += update
            persistedFileIds.removeAll(update.removedIndexedFileIds)
        }
    }
}
