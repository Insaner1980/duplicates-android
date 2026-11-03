package com.emma.duplicates.domain.deletion

import androidx.room.withTransaction
import com.emma.duplicates.core.database.CleanupOperationItem
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.RecoveryMoveState
import com.emma.duplicates.data.RecoveryRepository
import com.emma.duplicates.data.ScanStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

class PendingCleanupReconciler(
    private val database: DuplicatesDatabase,
    private val recovery: RecoveryRepository,
    volumes: StorageVolumeSource,
    hasAccess: () -> Boolean,
    private val operationMutex: Mutex,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = database.toolDao()
    private val access = CleanupAccess(database, volumes, hasAccess)

    suspend fun reconcile() =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                for (pending in dao.pending()) {
                    val session = dao.session(pending.operation.sessionId)
                    val scope = session?.scope?.let(ToolScope::decode)
                    if (scope != null && access.scopeAvailable(scope)) reconcileOperation(pending)
                }
            }
        }

    private suspend fun reconcileOperation(pending: com.emma.duplicates.core.database.CleanupOperationWithItems) {
        var deferred = false
        for (item in pending.items.filter { it.status == "pending" }) {
            val outcome = reconcileItem(item)
            if (outcome == null) {
                deferred = true
                continue
            }
            database.withTransaction {
                dao.updateOperationItem(item.copy(status = outcome))
                if (outcome == "moved" || outcome == "absent" || outcome == "changed") {
                    dao.invalidate(item.volume, item.canonicalPath)
                    if (!item.directory) ScanStore(database).removePath(item.volume, item.canonicalPath)
                }
            }
        }
        if (!deferred) dao.deleteOperation(pending.operation.id)
    }

    private suspend fun reconcileItem(item: CleanupOperationItem): String? {
        val source = Paths.get(item.canonicalPath)
        if (item.directory) {
            return when {
                Files.notExists(source, LinkOption.NOFOLLOW_LINKS) -> "absent"
                Files.exists(source, LinkOption.NOFOLLOW_LINKS) -> "skipped"
                else -> null
            }
        }
        return reconcileFile(item, source)
    }

    private suspend fun reconcileFile(
        item: CleanupOperationItem,
        source: java.nio.file.Path,
    ): String? {
        val recoveryId = item.recoveryId ?: return "skipped"
        return when (
            recovery.reconcileMove(recoveryId) { path ->
                val snapshot =
                    ToolItemEntity(
                        sessionId = "reconcile",
                        id = item.itemId,
                        volume = item.volume,
                        canonicalPath = path,
                        name = source.fileName.toString(),
                        sizeBytes = item.sizeBytes,
                        modifiedAt = item.modifiedAt,
                        category = "DOCUMENTS",
                    )
                val verified = verifyCleanupFile(snapshot)
                verified != null && verified.digest == item.digest && verified.identityKey == item.identityKey
            }
        ) {
            RecoveryMoveState.VERIFIED -> {
                "moved"
            }

            RecoveryMoveState.UNVERIFIED -> {
                "changed"
            }

            RecoveryMoveState.NOT_MOVED -> {
                when {
                    Files.notExists(source, LinkOption.NOFOLLOW_LINKS) -> "absent"
                    Files.exists(source, LinkOption.NOFOLLOW_LINKS) -> "skipped"
                    else -> null
                }
            }

            RecoveryMoveState.UNAVAILABLE -> {
                null
            }
        }
    }
}
