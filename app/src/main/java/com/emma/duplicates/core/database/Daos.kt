package com.emma.duplicates.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScanDao {
    @Insert
    abstract suspend fun insertSession(session: ScanSessionEntity)

    @Query(
        """
        UPDATE scan_sessions
        SET status = 'failed', completedAt = :completedAt, errorCount = errorCount + 1,
            active = 0, canceled = 0, failureReason = :failureReason, phase = NULL,
            currentPath = NULL,
            progressCompletedWork = 0, progressTotalWork = NULL
        WHERE status = 'running'
        """,
    )
    abstract suspend fun failRunningSessions(
        completedAt: Long,
        failureReason: String,
    ): Int

    @Insert
    abstract suspend fun insertIndexedFiles(files: List<IndexedFileEntity>)

    @Insert
    abstract suspend fun insertGroups(groups: List<DuplicateGroupEntity>)

    @Insert
    abstract suspend fun insertMembers(members: List<DuplicateMemberEntity>)

    @Transaction
    open suspend fun replaceStagedResults(
        sessionId: String,
        files: List<IndexedFileEntity>,
        groups: List<DuplicateGroupEntity>,
        members: List<DuplicateMemberEntity>,
    ): Boolean {
        if (!isRunningSession(sessionId)) return false
        if (files.any { it.sessionId != sessionId } || groups.any { it.sessionId != sessionId }) return false
        val fileIds = files.mapTo(mutableSetOf()) { it.id }
        val groupIds = groups.mapTo(mutableSetOf()) { it.id }
        if (members.any { it.indexedFileId !in fileIds || it.groupId !in groupIds }) return false

        deleteGroupsForSession(sessionId)
        deleteFilesForSession(sessionId)
        if (files.isNotEmpty()) insertIndexedFiles(files)
        if (groups.isNotEmpty()) insertGroups(groups)
        if (members.isNotEmpty()) insertMembers(members)
        return true
    }

    @Query("SELECT EXISTS(SELECT 1 FROM scan_sessions WHERE id = :sessionId AND status = 'running')")
    protected abstract suspend fun isRunningSession(sessionId: String): Boolean

    @Query("DELETE FROM duplicate_groups WHERE sessionId = :sessionId")
    protected abstract suspend fun deleteGroupsForSession(sessionId: String)

    @Query("DELETE FROM indexed_files WHERE sessionId = :sessionId")
    protected abstract suspend fun deleteFilesForSession(sessionId: String)

    @Query("SELECT * FROM scan_sessions WHERE id = :sessionId LIMIT 1")
    abstract suspend fun getSession(sessionId: String): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE active = 1 AND status = 'completed' LIMIT 1")
    abstract fun observeActiveCompletedSession(): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions WHERE status = 'running' ORDER BY startedAt DESC LIMIT 1")
    abstract fun observeRunningSession(): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC LIMIT 1")
    abstract fun observeLatestSession(): Flow<ScanSessionEntity?>

    @Transaction
    @Query("SELECT * FROM scan_sessions WHERE active = 1 AND status = 'completed' LIMIT 1")
    abstract fun observeActiveResults(): Flow<ScanSessionWithGroups?>

    @Transaction
    @Query("SELECT * FROM duplicate_groups WHERE id = :groupId LIMIT 1")
    abstract fun observeGroup(groupId: String): Flow<DuplicateGroupWithMembers?>

    suspend fun updateProgress(
        sessionId: String,
        update: ScanProgressUpdate,
    ): Boolean =
        updateProgressRow(
            sessionId = sessionId,
            phase = update.phase,
            currentPath = update.currentPath,
            completedWork = update.completedWork,
            totalWork = update.totalWork,
            scannedFileCount = update.scannedFileCount,
            scannedByteCount = update.scannedByteCount,
            candidateGroupCount = update.candidateGroupCount,
            duplicateGroupCount = update.duplicateGroupCount,
            reclaimableBytes = update.reclaimableBytes,
            skippedFileCount = update.skippedFileCount,
            errorCount = update.errorCount,
        ) == 1

    @Query(
        """
        UPDATE scan_sessions
        SET phase = :phase,
            currentPath = :currentPath,
            progressCompletedWork = :completedWork,
            progressTotalWork = :totalWork,
            scannedFileCount = :scannedFileCount,
            scannedByteCount = :scannedByteCount,
            candidateGroupCount = :candidateGroupCount,
            duplicateGroupCount = :duplicateGroupCount,
            reclaimableBytes = :reclaimableBytes,
            skippedFileCount = :skippedFileCount,
            errorCount = :errorCount
        WHERE id = :sessionId AND status = 'running'
        """,
    )
    protected abstract suspend fun updateProgressRow(
        sessionId: String,
        phase: String,
        currentPath: String?,
        completedWork: Long,
        totalWork: Long?,
        scannedFileCount: Int,
        scannedByteCount: Long,
        candidateGroupCount: Int,
        duplicateGroupCount: Int,
        reclaimableBytes: Long,
        skippedFileCount: Int,
        errorCount: Int,
    ): Int

    @Transaction
    open suspend fun completeStagingSession(
        sessionId: String,
        completedAt: Long,
        metrics: ScanCompletionMetrics,
    ): Boolean {
        val completed =
            markCompleted(
                sessionId = sessionId,
                completedAt = completedAt,
                scannedFileCount = metrics.scannedFileCount,
                scannedByteCount = metrics.scannedByteCount,
                duplicateFileCount = metrics.duplicateFileCount,
                duplicateGroupCount = metrics.duplicateGroupCount,
                reclaimableBytes = metrics.reclaimableBytes,
                skippedFileCount = metrics.skippedFileCount,
                errorCount = metrics.errorCount,
            ) == 1
        if (completed) {
            deleteAllExcept(sessionId)
        }
        return completed
    }

    @Query(
        """
        UPDATE scan_sessions
        SET status = 'completed',
            completedAt = :completedAt,
            scannedFileCount = :scannedFileCount,
            scannedByteCount = :scannedByteCount,
            duplicateFileCount = :duplicateFileCount,
            duplicateGroupCount = :duplicateGroupCount,
            reclaimableBytes = :reclaimableBytes,
            skippedFileCount = :skippedFileCount,
            errorCount = :errorCount,
            active = 1,
            canceled = 0,
            failureReason = NULL,
            phase = NULL,
            currentPath = NULL,
            progressCompletedWork = 0,
            progressTotalWork = NULL
        WHERE id = :sessionId AND status = 'running'
        """,
    )
    protected abstract suspend fun markCompleted(
        sessionId: String,
        completedAt: Long,
        scannedFileCount: Int,
        scannedByteCount: Long,
        duplicateFileCount: Int,
        duplicateGroupCount: Int,
        reclaimableBytes: Long,
        skippedFileCount: Int,
        errorCount: Int,
    ): Int

    @Query("DELETE FROM scan_sessions WHERE id != :activeSessionId")
    protected abstract suspend fun deleteAllExcept(activeSessionId: String)

    suspend fun failStagingSession(
        sessionId: String,
        completedAt: Long,
        errorCount: Int,
        failureReason: String,
    ): Boolean = markFailed(sessionId, completedAt, errorCount, failureReason) == 1

    @Query(
        """
        UPDATE scan_sessions
        SET status = 'failed', completedAt = :completedAt, errorCount = :errorCount,
            active = 0, canceled = 0, failureReason = :failureReason, phase = NULL,
            currentPath = NULL,
            progressCompletedWork = 0, progressTotalWork = NULL
        WHERE id = :sessionId AND status = 'running'
        """,
    )
    protected abstract suspend fun markFailed(
        sessionId: String,
        completedAt: Long,
        errorCount: Int,
        failureReason: String,
    ): Int

    suspend fun cancelStagingSession(
        sessionId: String,
        completedAt: Long,
    ): Boolean = markCanceled(sessionId, completedAt) == 1

    @Query(
        """
        UPDATE scan_sessions
        SET status = 'canceled', completedAt = :completedAt, active = 0, canceled = 1,
            failureReason = NULL, phase = NULL, currentPath = NULL,
            progressCompletedWork = 0, progressTotalWork = NULL
        WHERE id = :sessionId AND status = 'running'
        """,
    )
    protected abstract suspend fun markCanceled(
        sessionId: String,
        completedAt: Long,
    ): Int

    @Transaction
    open suspend fun setMemberSelected(
        memberId: String,
        selected: Boolean,
    ): Boolean {
        val current = getMemberSelectionState(memberId) ?: return false
        if (current.selectedForDeletion == selected) return true
        if (selected && countOtherValidUnselectedMembers(current.groupId, memberId) == 0) {
            return false
        }
        if (updateMemberSelection(memberId, selected) != 1) return false
        recalculateGroupReclaimableBytes(current.groupId)
        recalculateSessionReclaimableBytes(current.groupId)
        return true
    }

    @Query("SELECT groupId, selectedForDeletion FROM duplicate_members WHERE id = :memberId LIMIT 1")
    protected abstract suspend fun getMemberSelectionState(memberId: String): MemberSelectionState?

    @Query(
        """
        SELECT COUNT(*)
        FROM duplicate_members AS member
        INNER JOIN indexed_files AS file ON file.id = member.indexedFileId
        WHERE member.groupId = :groupId
          AND member.id != :excludedMemberId
          AND member.selectedForDeletion = 0
          AND file.readable = 1
          AND file.changedSinceScan = 0
        """,
    )
    protected abstract suspend fun countOtherValidUnselectedMembers(
        groupId: String,
        excludedMemberId: String,
    ): Int

    @Query("UPDATE duplicate_members SET selectedForDeletion = :selected WHERE id = :memberId")
    protected abstract suspend fun updateMemberSelection(
        memberId: String,
        selected: Boolean,
    ): Int

    @Query(
        """
        UPDATE duplicate_groups
        SET reclaimableBytes = COALESCE(
            (
                SELECT SUM(file.sizeBytes)
                FROM duplicate_members AS member
                INNER JOIN indexed_files AS file ON file.id = member.indexedFileId
                WHERE member.groupId = :groupId
                  AND member.selectedForDeletion = 1
                  AND file.readable = 1
                  AND file.writable = 1
                  AND file.changedSinceScan = 0
            ),
            0
        )
        WHERE id = :groupId
        """,
    )
    protected abstract suspend fun recalculateGroupReclaimableBytes(groupId: String)

    @Query(
        """
        UPDATE scan_sessions
        SET reclaimableBytes = COALESCE(
            (
                SELECT SUM(reclaimableBytes)
                FROM duplicate_groups
                WHERE sessionId = scan_sessions.id
            ),
            0
        )
        WHERE id = (SELECT sessionId FROM duplicate_groups WHERE id = :groupId)
        """,
    )
    protected abstract suspend fun recalculateSessionReclaimableBytes(groupId: String)

    @Query("DELETE FROM scan_sessions")
    abstract suspend fun clearScanMetadata()

    @Query("SELECT DISTINCT sessionId FROM duplicate_groups WHERE id IN (:groupIds)")
    abstract suspend fun getSessionIdsForGroups(groupIds: Set<String>): List<String>

    @Query("DELETE FROM duplicate_members WHERE id IN (:memberIds)")
    abstract suspend fun deleteMembers(memberIds: Set<String>)

    @Query("DELETE FROM indexed_files WHERE id IN (:fileIds)")
    abstract suspend fun deleteIndexedFiles(fileIds: Set<String>)

    @Query("UPDATE indexed_files SET changedSinceScan = 1 WHERE id IN (:fileIds)")
    abstract suspend fun markFilesChanged(fileIds: Set<String>)

    @Query("DELETE FROM duplicate_groups WHERE id = :groupId")
    abstract suspend fun deleteGroup(groupId: String)

    @Query(
        """
        UPDATE duplicate_groups
        SET copyCount = :copyCount, reclaimableBytes = :reclaimableBytes
        WHERE id = :groupId
        """,
    )
    abstract suspend fun updateGroupAfterDeletion(
        groupId: String,
        copyCount: Int,
        reclaimableBytes: Long,
    )

    @Query("SELECT COUNT(*) FROM duplicate_members WHERE groupId = :groupId")
    abstract suspend fun countMembers(groupId: String): Int

    @Query(
        """
        SELECT COALESCE(SUM(file.sizeBytes), 0)
        FROM duplicate_members AS member
        INNER JOIN indexed_files AS file ON file.id = member.indexedFileId
        WHERE member.groupId = :groupId
          AND member.selectedForDeletion = 1
          AND file.readable = 1
          AND file.writable = 1
          AND file.changedSinceScan = 0
        """,
    )
    abstract suspend fun getSelectedReclaimableBytes(groupId: String): Long

    @Query(
        """
        UPDATE scan_sessions
        SET duplicateFileCount = COALESCE(
                (SELECT SUM(copyCount) FROM duplicate_groups WHERE sessionId = :sessionId),
                0
            ),
            duplicateGroupCount = (
                SELECT COUNT(*) FROM duplicate_groups WHERE sessionId = :sessionId
            ),
            reclaimableBytes = COALESCE(
                (SELECT SUM(reclaimableBytes) FROM duplicate_groups WHERE sessionId = :sessionId),
                0
            )
        WHERE id = :sessionId
        """,
    )
    abstract suspend fun recalculateSessionResults(sessionId: String)
}

@Dao
interface FingerprintDao {
    @Upsert
    suspend fun upsert(fingerprint: FingerprintCacheEntity)

    @Query(
        """
        SELECT * FROM fingerprint_cache
        WHERE canonicalPath = :canonicalPath
          AND volume = :volume
          AND sizeBytes = :sizeBytes
          AND lastModified = :lastModified
        LIMIT 1
        """,
    )
    suspend fun findReusable(
        canonicalPath: String,
        volume: String,
        sizeBytes: Long,
        lastModified: Long,
    ): FingerprintCacheEntity?

    @Query(
        """
        SELECT * FROM fingerprint_cache
        WHERE canonicalPath = :canonicalPath AND volume = :volume
        LIMIT 1
        """,
    )
    suspend fun findByLocation(
        canonicalPath: String,
        volume: String,
    ): FingerprintCacheEntity?

    @Query("DELETE FROM fingerprint_cache WHERE canonicalPath = :canonicalPath AND volume = :volume")
    suspend fun invalidate(
        canonicalPath: String,
        volume: String,
    )
}

@Dao
abstract class DeletionOperationDao {
    @Transaction
    open suspend fun insert(
        operation: PendingDeletionOperationEntity,
        items: List<PendingDeletionItemEntity>,
    ) {
        insertOperation(operation)
        insertItems(items)
    }

    @Insert
    protected abstract suspend fun insertOperation(operation: PendingDeletionOperationEntity)

    @Insert
    protected abstract suspend fun insertItems(items: List<PendingDeletionItemEntity>)

    @Transaction
    @Query("SELECT * FROM pending_deletion_operations ORDER BY createdAt, id")
    abstract suspend fun getPending(): List<PendingDeletionOperationWithItems>

    @Transaction
    @Query("SELECT * FROM pending_deletion_operations WHERE id = :operationId LIMIT 1")
    abstract suspend fun getPending(operationId: String): PendingDeletionOperationWithItems?

    @Query("DELETE FROM pending_deletion_operations WHERE id = :operationId")
    abstract suspend fun delete(operationId: String)
}

@Dao
abstract class ExclusionDao {
    @Query("SELECT * FROM exclusions ORDER BY type DESC, displayName COLLATE NOCASE, canonicalPath")
    abstract fun observeAll(): Flow<List<ExclusionEntity>>

    @Query("SELECT * FROM exclusions ORDER BY type DESC, displayName COLLATE NOCASE, canonicalPath")
    abstract suspend fun getAll(): List<ExclusionEntity>

    suspend fun insert(exclusion: ExclusionEntity): Boolean = insertIgnoringDuplicate(exclusion) != -1L

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoringDuplicate(exclusion: ExclusionEntity): Long

    @Query("DELETE FROM exclusions WHERE id = :exclusionId")
    abstract suspend fun remove(exclusionId: String)
}
