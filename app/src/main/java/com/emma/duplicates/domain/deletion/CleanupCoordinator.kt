package com.emma.duplicates.domain.deletion

import androidx.room.withTransaction
import com.emma.duplicates.core.database.CleanupOperation
import com.emma.duplicates.core.database.CleanupOperationItem
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ExclusionTypes
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.core.storage.allows
import com.emma.duplicates.data.RecoveryRepository
import com.emma.duplicates.data.ScanStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.UUID

class CleanupCoordinator(
    private val database: DuplicatesDatabase,
    private val recovery: RecoveryRepository,
    private val volumes: StorageVolumeSource,
    private val hasAccess: () -> Boolean,
    private val authorizer: MediaDeletionAuthorizer,
    private val operationMutex: Mutex,
) {
    private val dao = database.toolDao()
    private val access = CleanupAccess(database, volumes, hasAccess)

    suspend fun prepare(
        sessionId: String,
        visibleIds: Set<String>,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): CleanupBatch =
        withContext(ioDispatcher) {
            val session = requireNotNull(dao.session(sessionId)?.takeIf { it.active })
            val tool = CleanupTool.valueOf(session.tool)
            require(tool in FILE_TOOLS)
            val scope = ToolScope.decode(session.scope)
            val items = dao.items(sessionId)
            val selected = items.filter { it.selected && it.valid && !it.keeper && !it.directory }
            val keepers =
                items
                    .filter {
                        it.keeper && it.valid &&
                            selected.any { selected -> selected.groupId == it.groupId }
                    }.mapNotNull { verified(it, scope) }
            val files =
                selected
                    .filter { item ->
                        tool != CleanupTool.SIMILAR_PHOTOS ||
                            keepers.any { it.item.groupId == item.groupId }
                    }.mapNotNull { verified(it, scope) }
            CleanupBatch(session, selected, files, keepers, files.count { it.item.id !in visibleIds })
        }

    suspend fun execute(
        batch: CleanupBatch,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): CleanupResult =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                if (!selectionMatches(batch) || !database.cleanupIdle()) return@withLock batch.rejected("changed")
                val scope = ToolScope.decode(batch.session.scope)
                if (!access.scopeAvailable(scope)) return@withLock batch.rejected("unavailable")
                val refused = authorize(batch.files)
                val files = batch.files.filter { it.item.id !in refused }
                if (!selectionMatches(batch) || !database.cleanupIdle() ||
                    !access.scopeAvailable(scope)
                ) {
                    return@withLock batch.rejected("changed")
                }
                performCleanup(batch, files, refused, scope)
            }
        }

    private suspend fun performCleanup(
        batch: CleanupBatch,
        files: List<VerifiedCleanupFile>,
        refused: Set<String>,
        scope: ToolScope,
    ): CleanupResult {
        val operation =
            CleanupOperation(
                UUID.randomUUID().toString(),
                batch.session.id,
                batch.session.tool,
                System.currentTimeMillis(),
            )
        val journals = files.map { file -> journal(operation.id, file) }
        if (!begin(batch, operation, journals)) return batch.rejected("changed")
        val outcomes =
            batch.selected
                .filter { selected -> files.none { it.item.id == selected.id } }
                .mapTo(mutableListOf()) {
                    CleanupOutcome(
                        it.id,
                        if (it.id in
                            refused
                        ) {
                            "authorization_canceled"
                        } else {
                            "changed"
                        },
                    )
                }
        for ((file, journal) in files.zip(journals)) {
            currentCoroutineContext().ensureActive()
            val status = processFile(batch, file, journal, scope)
            database.withTransaction {
                dao.updateOperationItem(journal.copy(status = status))
                if (status == "moved" || status == "changed") invalidate(file.item)
            }
            outcomes += CleanupOutcome(file.item.id, status)
        }
        dao.deleteOperation(operation.id)
        return CleanupResult(outcomes)
    }

    private suspend fun begin(
        batch: CleanupBatch,
        operation: CleanupOperation,
        journals: List<CleanupOperationItem>,
    ): Boolean =
        database.withTransaction {
            if (!database.cleanupIdle() || !selectionMatches(batch)) {
                false
            } else {
                dao.insertOperation(operation)
                dao.insertOperationItems(journals)
                true
            }
        }

    private suspend fun processFile(
        batch: CleanupBatch,
        file: VerifiedCleanupFile,
        journal: CleanupOperationItem,
        scope: ToolScope,
    ): String {
        val fileValid = file.matches(verified(file.item, scope))
        val keeper = batch.keepers.firstOrNull { it.item.groupId == file.item.groupId }
        val keeperValid =
            batch.session.tool != CleanupTool.SIMILAR_PHOTOS.name ||
                (keeper != null && keeper.matches(verified(keeper.item, scope)))
        return if (!keeperValid || !fileValid) "changed" else move(file, journal)
    }

    private suspend fun move(
        file: VerifiedCleanupFile,
        journal: CleanupOperationItem,
    ): String =
        try {
            val moved =
                recovery.moveTracked(file.item.deletionFile(), requireNotNull(journal.recoveryId)) { staged ->
                    file.matches(
                        verifyCleanupFile(file.item.copy(canonicalPath = staged.canonicalPath, contentUri = null)),
                    )
                }
            if (moved == DirectDeletionResult.DELETED) "moved" else "failed"
        } catch (_: IOException) {
            "failed"
        } catch (_: SecurityException) {
            "failed"
        }

    private suspend fun authorize(files: List<VerifiedCleanupFile>): Set<String> {
        val refused = mutableSetOf<String>()
        files
            .map {
                it.item.deletionFile()
            }.filter { it.requiresMediaAuthorization }
            .chunked(AUTHORIZATION_BATCH_SIZE)
            .forEach { chunk ->
                if (authorizer.requestDeletion(chunk) != MediaAuthorization.APPROVED) refused += chunk.map { it.id }
            }
        return refused
    }

    private suspend fun selectionMatches(batch: CleanupBatch): Boolean {
        val session = dao.session(batch.session.id)
        if (session?.active != true || session.scope != batch.session.scope) return false
        return dao.items(session.id).filter { it.selected && it.valid } == batch.selected
    }

    private suspend fun verified(
        item: ToolItemEntity,
        scope: ToolScope,
    ): VerifiedCleanupFile? {
        if (!access.allows(item, scope)) return null
        return verifyCleanupFile(item)
    }

    private suspend fun invalidate(item: ToolItemEntity) {
        dao.invalidate(item.volume, item.canonicalPath)
        ScanStore(database).removePath(item.volume, item.canonicalPath)
    }

    private companion object {
        const val AUTHORIZATION_BATCH_SIZE = 2_000
        val FILE_TOOLS =
            setOf(
                CleanupTool.LARGE_FILES,
                CleanupTool.OLD_DOWNLOADS,
                CleanupTool.SCREENSHOTS,
                CleanupTool.SIMILAR_PHOTOS,
            )
    }
}

private fun CleanupBatch.rejected(status: String) = CleanupResult(selected.map { CleanupOutcome(it.id, status) })

private fun journal(
    operationId: String,
    file: VerifiedCleanupFile,
) = CleanupOperationItem(
    operationId,
    file.item.id,
    file.item.volume,
    file.item.canonicalPath,
    file.item.sizeBytes,
    file.item.modifiedAt,
    false,
    file.digest,
    file.identityKey,
    UUID.randomUUID().toString(),
)
