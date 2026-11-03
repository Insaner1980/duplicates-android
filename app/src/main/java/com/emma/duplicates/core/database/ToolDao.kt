package com.emma.duplicates.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
// Room keeps session, selection, and durable-operation queries in their transaction owner.
@Suppress("TooManyFunctions")
interface ToolDao {
    @Transaction
    @Query("SELECT * FROM tool_sessions WHERE tool = :tool AND active = 1 AND status = 'completed' LIMIT 1")
    fun observeActive(tool: String): Flow<ToolResults?>

    @Transaction
    @Query("SELECT * FROM tool_sessions WHERE id = :id")
    fun observeResults(id: String): Flow<ToolResults?>

    @Query("SELECT * FROM tool_sessions WHERE tool = :tool ORDER BY startedAt DESC, rowid DESC LIMIT 1")
    fun observeLatest(tool: String): Flow<ToolScanSession?>

    @Query("SELECT * FROM tool_sessions WHERE id = :id")
    suspend fun session(id: String): ToolScanSession?

    @Query("SELECT * FROM tool_sessions WHERE status = 'running'")
    suspend fun running(): List<ToolScanSession>

    @Query("SELECT * FROM tool_items WHERE sessionId = :sessionId ORDER BY canonicalPath, volume")
    suspend fun items(sessionId: String): List<ToolItemEntity>

    @Insert
    suspend fun insertSession(session: ToolScanSession)

    @Update
    suspend fun updateSession(session: ToolScanSession)

    @Query("UPDATE tool_sessions SET phase = :phase, visitedCount = :count WHERE id = :id AND status = 'running'")
    suspend fun progress(
        id: String,
        phase: String,
        count: Int,
    )

    @Insert
    suspend fun insertItems(items: List<ToolItemEntity>)

    @Update
    suspend fun updateItems(items: List<ToolItemEntity>)

    @Query("UPDATE tool_sessions SET active = 0 WHERE tool = :tool")
    suspend fun deactivate(tool: String)

    @Query(
        "DELETE FROM tool_sessions WHERE tool = :tool AND id != :keepId AND active = 0 AND status != 'running' " +
            "AND id NOT IN (SELECT sessionId FROM cleanup_operations)",
    )
    suspend fun prune(
        tool: String,
        keepId: String,
    )

    @Query("UPDATE tool_items SET valid = 0, selected = 0 WHERE volume = :volume AND canonicalPath = :path")
    suspend fun invalidate(
        volume: String,
        path: String,
    )

    @Transaction
    @Query("SELECT * FROM cleanup_operations ORDER BY createdAt, id")
    suspend fun pending(): List<CleanupOperationWithItems>

    @Insert
    suspend fun insertOperation(operation: CleanupOperation)

    @Insert
    suspend fun insertOperationItems(items: List<CleanupOperationItem>)

    @Update
    suspend fun updateOperationItem(item: CleanupOperationItem)

    @Query("DELETE FROM cleanup_operations WHERE id = :id")
    suspend fun deleteOperation(id: String)
}
