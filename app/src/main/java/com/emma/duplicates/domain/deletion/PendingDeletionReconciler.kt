package com.emma.duplicates.domain.deletion

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PendingDeletionReconciler(
    private val storage: DeletionStorage,
    private val resultStore: DeletionResultStore,
) {
    private val mutex = Mutex()

    suspend fun reconcile() = mutex.withLock {
        resultStore.pendingDeletions().forEach { operation ->
            val missingFileIds = operation.files
                .filter { pending -> storage.snapshot(pending.file) == null }
                .mapTo(linkedSetOf()) { pending -> pending.file.id }
            resultStore.reconcileDeletion(operation.id, missingFileIds)
        }
    }
}
