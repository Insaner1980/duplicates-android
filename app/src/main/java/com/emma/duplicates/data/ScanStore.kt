package com.emma.duplicates.data

import androidx.room.withTransaction
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ExclusionEntity
import com.emma.duplicates.core.database.FingerprintCacheEntity
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.database.PendingDeletionItemEntity
import com.emma.duplicates.core.database.PendingDeletionOperationEntity
import com.emma.duplicates.core.database.PendingDeletionOperationWithItems
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanProgressUpdate
import com.emma.duplicates.core.database.ScanSessionEntity
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.domain.deletion.DeletionDatabaseUpdate
import kotlinx.coroutines.flow.first

// This facade keeps scan and deletion transactions on the same database boundary.
@Suppress("TooManyFunctions")
class ScanStore(
    private val database: DuplicatesDatabase,
) {
    private val scanDao = database.scanDao()
    private val fingerprintDao = database.fingerprintDao()
    private val deletionOperationDao = database.deletionOperationDao()
    private val exclusionDao = database.exclusionDao()

    val activeCompletedSession = scanDao.observeActiveCompletedSession()
    val runningSession = scanDao.observeRunningSession()
    val latestSession = scanDao.observeLatestSession()
    val activeResults = scanDao.observeActiveResults()
    val exclusions = exclusionDao.observeAll()

    fun observeGroup(groupId: String) = scanDao.observeGroup(groupId)

    suspend fun startSession(
        sessionId: String,
        startedAt: Long,
    ) {
        database.withTransaction {
            check(deletionOperationDao.getPending().isEmpty()) { "Deletion recovery is pending" }
            check(
                database.toolDao().pending().isEmpty() && database.toolDao().running().isEmpty(),
            ) { "A tool operation is active" }
            scanDao.failRunningSessions(startedAt, ScanFailureReasons.GENERIC)
            scanDao.insertSession(
                ScanSessionEntity(
                    id = sessionId,
                    status = ScanStatuses.RUNNING,
                    startedAt = startedAt,
                    phase = ScanPhases.FINDING_FILES,
                ),
            )
        }
    }

    suspend fun replaceStagedResults(
        sessionId: String,
        files: List<IndexedFileEntity>,
        groups: List<DuplicateGroupEntity>,
        members: List<DuplicateMemberEntity>,
    ): Boolean = scanDao.replaceStagedResults(sessionId, files, groups, members)

    suspend fun updateProgress(
        sessionId: String,
        update: ScanProgressUpdate,
    ): Boolean = scanDao.updateProgress(sessionId, update)

    suspend fun completeSession(
        sessionId: String,
        completedAt: Long,
        metrics: ScanCompletionMetrics,
    ): Boolean = scanDao.completeStagingSession(sessionId, completedAt, metrics)

    suspend fun failSession(
        sessionId: String,
        completedAt: Long,
        errorCount: Int,
        failureReason: String = ScanFailureReasons.GENERIC,
    ): Boolean = scanDao.failStagingSession(sessionId, completedAt, errorCount, failureReason)

    suspend fun cancelSession(
        sessionId: String,
        completedAt: Long,
    ): Boolean = scanDao.cancelStagingSession(sessionId, completedAt)

    suspend fun setMemberSelected(
        memberId: String,
        selected: Boolean,
    ): Boolean = scanDao.setMemberSelected(memberId, selected)

    suspend fun findReusableFingerprint(
        canonicalPath: String,
        volume: String,
        sizeBytes: Long,
        lastModified: Long,
    ): FingerprintCacheEntity? = fingerprintDao.findReusable(canonicalPath, volume, sizeBytes, lastModified)

    suspend fun findFingerprint(
        canonicalPath: String,
        volume: String,
    ): FingerprintCacheEntity? = fingerprintDao.findByLocation(canonicalPath, volume)

    suspend fun putFingerprint(fingerprint: FingerprintCacheEntity) {
        fingerprintDao.upsert(fingerprint)
    }

    suspend fun invalidateFingerprint(
        canonicalPath: String,
        volume: String,
    ) {
        fingerprintDao.invalidate(canonicalPath, volume)
    }

    suspend fun addExclusion(
        exclusion: ExclusionEntity,
        coveredExclusionIds: List<String> = emptyList(),
    ): Boolean =
        database.withTransaction {
            val inserted = exclusionDao.insert(exclusion)
            if (inserted) {
                coveredExclusionIds.forEach { exclusionDao.remove(it) }
            }
            inserted
        }

    suspend fun removeExclusion(exclusionId: String) {
        exclusionDao.remove(exclusionId)
    }

    suspend fun getExclusions(): List<ExclusionEntity> = exclusionDao.getAll()

    suspend fun clearScanMetadata() {
        scanDao.clearScanMetadata()
    }

    suspend fun clearScanMetadataIfIdle(): Boolean =
        database.withTransaction {
            if (deletionOperationDao.getPending().isNotEmpty() || runningSession.first() != null) {
                false
            } else {
                scanDao.clearScanMetadata()
                true
            }
        }

    suspend fun beginDeletion(
        operation: PendingDeletionOperationEntity,
        items: List<PendingDeletionItemEntity>,
    ) {
        database.withTransaction {
            check(
                database.toolDao().pending().isEmpty() && database.toolDao().running().isEmpty(),
            ) { "A tool operation is active" }
            check(deletionOperationDao.getPending().isEmpty() && runningSession.first() == null) {
                "A scan or deletion recovery is active"
            }
            val currentMembers =
                activeResults
                    .first()
                    ?.groups
                    .orEmpty()
                    .flatMap { it.members }
                    .associateBy { it.member.id }
            check(
                items.all { item ->
                    currentMembers[item.memberId]?.let { current ->
                        current.member.groupId == item.groupId &&
                            current.member.indexedFileId == item.indexedFileId &&
                            current.member.selectedForDeletion &&
                            current.file.canonicalPath == item.canonicalPath &&
                            current.file.sizeBytes == item.sizeBytes &&
                            current.file.lastModified == item.lastModifiedMillis
                    } == true
                },
            ) { "Deletion selection has changed" }
            deletionOperationDao.insert(operation, items)
        }
    }

    suspend fun pendingDeletions(): List<PendingDeletionOperationWithItems> = deletionOperationDao.getPending()

    suspend fun reconcileDeletion(
        operationId: String,
        missingFileIds: Set<String>,
    ) {
        database.withTransaction {
            val operation = deletionOperationDao.getPending(operationId) ?: return@withTransaction
            val missingItems = operation.items.filter { it.indexedFileId in missingFileIds }
            val affectedGroupIds = missingItems.mapTo(linkedSetOf()) { it.groupId }
            val sessionIds =
                if (affectedGroupIds.isEmpty()) emptyList() else scanDao.getSessionIdsForGroups(affectedGroupIds)
            if (missingItems.isNotEmpty()) {
                scanDao.filesByIds(missingItems.mapTo(mutableSetOf()) { it.indexedFileId }).forEach {
                    database.toolDao().invalidate(it.volume, it.canonicalPath)
                }
                scanDao.deleteMembers(missingItems.mapTo(linkedSetOf()) { it.memberId })
                scanDao.deleteIndexedFiles(missingItems.mapTo(linkedSetOf()) { it.indexedFileId })
                affectedGroupIds.forEach { groupId ->
                    val remainingCount = scanDao.countMembers(groupId)
                    if (remainingCount < 2) {
                        scanDao.deleteGroup(groupId)
                    } else {
                        scanDao.updateGroupAfterDeletion(
                            groupId = groupId,
                            copyCount = remainingCount,
                            reclaimableBytes = scanDao.getSelectedReclaimableBytes(groupId),
                        )
                    }
                }
                sessionIds.forEach { scanDao.recalculateSessionResults(it) }
            }
            deletionOperationDao.delete(operationId)
        }
    }

    suspend fun applyDeletion(update: DeletionDatabaseUpdate) {
        database.withTransaction {
            applyDeletionInTransaction(update)
        }
    }

    suspend fun completeDeletion(
        operationId: String,
        update: DeletionDatabaseUpdate,
    ) {
        database.withTransaction {
            applyDeletionInTransaction(update)
            deletionOperationDao.delete(operationId)
        }
    }

    private suspend fun applyDeletionInTransaction(update: DeletionDatabaseUpdate) {
        scanDao.filesByIds(update.removedIndexedFileIds + update.changedIndexedFileIds).forEach {
            database.toolDao().invalidate(it.volume, it.canonicalPath)
        }
        val groupIds = update.groups.mapTo(linkedSetOf()) { it.groupId }
        val sessionIds =
            if (groupIds.isEmpty()) emptyList() else scanDao.getSessionIdsForGroups(groupIds)
        if (update.removedMemberIds.isNotEmpty()) {
            scanDao.deleteMembers(update.removedMemberIds)
        }
        if (update.removedIndexedFileIds.isNotEmpty()) {
            scanDao.deleteIndexedFiles(update.removedIndexedFileIds)
        }
        if (update.changedIndexedFileIds.isNotEmpty()) {
            scanDao.markFilesChanged(update.changedIndexedFileIds)
        }
        update.groups.forEach { group ->
            if (group.removeGroup) {
                scanDao.deleteGroup(group.groupId)
            } else {
                scanDao.updateGroupAfterDeletion(
                    groupId = group.groupId,
                    copyCount = group.remainingCopyCount,
                    reclaimableBytes = group.remainingSelectedReclaimableBytes,
                )
            }
        }
        sessionIds.forEach { scanDao.recalculateSessionResults(it) }
    }

    suspend fun removePath(
        volume: String,
        path: String,
    ) = database.withTransaction {
        val ids = scanDao.filesAtPath(volume, path).map { it.id }.toSet()
        if (ids.isEmpty()) return@withTransaction
        val groupIds = scanDao.membersForFiles(ids).map { it.groupId }.toSet()
        val sessions = scanDao.getSessionIdsForGroups(groupIds)
        scanDao.deleteIndexedFiles(ids)
        groupIds.forEach { groupId ->
            val count = scanDao.countMembers(groupId)
            if (count < 2) {
                scanDao.deleteGroup(groupId)
            } else {
                scanDao.updateGroupAfterDeletion(groupId, count, scanDao.getSelectedReclaimableBytes(groupId))
            }
        }
        sessions.forEach { scanDao.recalculateSessionResults(it) }
        fingerprintDao.invalidate(path, volume)
    }
}
