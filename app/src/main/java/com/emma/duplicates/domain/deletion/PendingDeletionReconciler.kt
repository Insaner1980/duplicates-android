package com.emma.duplicates.domain.deletion

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PendingDeletionReconciler(
    private val storage: DeletionStorage,
    private val resultStore: DeletionResultStore,
    private val operationMutex: Mutex = Mutex(),
) {
    suspend fun reconcile() =
        operationMutex.withLock {
            resultStore.pendingDeletions().forEach { operation ->
                val snapshots = operation.files.associateWith { storage.snapshot(it.file) }
                if (snapshots.values.any { it?.isReadable == false }) return@forEach
                val missingFileIds =
                    snapshots
                        .filterValues { it == null }
                        .keys
                        .mapTo(linkedSetOf()) { pending -> pending.file.id }
                resultStore.reconcileDeletion(operation.id, missingFileIds)
            }
        }
}
