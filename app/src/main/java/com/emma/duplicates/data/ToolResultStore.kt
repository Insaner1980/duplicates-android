package com.emma.duplicates.data

import androidx.room.withTransaction
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import kotlinx.coroutines.flow.first
import java.nio.file.Paths

data class ToolCompletion(
    val complete: Boolean = true,
    val errorCount: Int = 0,
    val skippedCount: Int = 0,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
    val failureReason: String? = null,
)

class ToolResultStore(
    private val database: DuplicatesDatabase,
) {
    val dao = database.toolDao()

    fun active(tool: CleanupTool) = dao.observeActive(tool.name)

    fun latest(tool: CleanupTool) = dao.observeLatest(tool.name)

    suspend fun start(session: ToolScanSession) =
        database.withTransaction {
            check(dao.running().isEmpty() && dao.pending().isEmpty()) { "A tool operation is active" }
            check(database.deletionOperationDao().getPending().isEmpty()) { "Deletion recovery is pending" }
            check(database.scanDao().observeRunningSession().first() == null) { "A duplicate scan is active" }
            dao.insertSession(session)
        }

    suspend fun complete(
        id: String,
        items: List<ToolItemEntity>,
        completedAt: Long,
        metrics: ToolCompletion = ToolCompletion(),
    ) = database.withTransaction {
        val session = requireNotNull(dao.session(id))
        check(session.status == ScanStatuses.RUNNING)
        require(items.all { it.sessionId == id })
        dao.insertItems(items.map { it.copy(selected = false) })
        dao.deactivate(session.tool)
        dao.updateSession(
            session.copy(
                status = ScanStatuses.COMPLETED,
                active = true,
                completedAt = completedAt,
                complete = metrics.complete,
                errorCount = metrics.errorCount,
                skippedCount = metrics.skippedCount,
                totalBytes = metrics.totalBytes,
                freeBytes = metrics.freeBytes,
                failureReason = metrics.failureReason,
            ),
        )
        dao.prune(session.tool, id)
    }

    suspend fun finish(
        id: String,
        status: String,
        failureReason: String?,
    ) {
        require(status == ScanStatuses.FAILED || status == ScanStatuses.CANCELED)
        database.withTransaction {
            val session = dao.session(id) ?: return@withTransaction
            if (session.status != ScanStatuses.RUNNING) return@withTransaction
            dao.updateSession(
                session.copy(
                    status = status,
                    complete = false,
                    failureReason = failureReason,
                    completedAt = System.currentTimeMillis(),
                ),
            )
            dao.prune(session.tool, id)
        }
    }

    suspend fun setSelected(
        sessionId: String,
        id: String,
        selected: Boolean,
    ) = database.withTransaction {
        val session = dao.session(sessionId)?.takeIf { it.active } ?: return@withTransaction
        if (dao.pending().isNotEmpty()) return@withTransaction
        val items = dao.items(sessionId)
        val target = items.firstOrNull { it.id == id && it.valid && !it.keeper } ?: return@withTransaction
        val targetPath = Paths.get(target.canonicalPath)
        val changes =
            if (session.tool == CleanupTool.EMPTY_FOLDERS.name) {
                items.filter { item ->
                    item.valid && item.volume == target.volume &&
                        if (selected) {
                            Paths.get(item.canonicalPath).startsWith(targetPath)
                        } else {
                            targetPath.startsWith(Paths.get(item.canonicalPath))
                        }
                }
            } else {
                listOf(target)
            }
        dao.updateItems(changes.map { it.copy(selected = selected) })
    }

    suspend fun keep(
        sessionId: String,
        id: String,
    ) = database.withTransaction {
        val session = dao.session(sessionId)?.takeIf { it.active } ?: return@withTransaction
        if (session.tool != CleanupTool.SIMILAR_PHOTOS.name || dao.pending().isNotEmpty()) return@withTransaction
        val items = dao.items(sessionId)
        val target = items.firstOrNull { it.id == id && it.valid && it.groupId != null } ?: return@withTransaction
        dao.updateItems(
            items.filter { it.groupId == target.groupId }.map {
                it.copy(keeper = it.id == id, selected = it.id != id && it.selected)
            },
        )
    }
}
