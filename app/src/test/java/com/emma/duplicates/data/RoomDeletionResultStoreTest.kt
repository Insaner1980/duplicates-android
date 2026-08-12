package com.emma.duplicates.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.domain.deletion.DeletionDatabaseUpdate
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DeletionGroup
import com.emma.duplicates.domain.deletion.GroupDeletionUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomDeletionResultStoreTest {
    private lateinit var database: DuplicatesDatabase
    private lateinit var scanStore: ScanStore

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scanStore = ScanStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `begin deletion persists selected group member and file identity`() = runTest {
        val store = RoomDeletionResultStore(scanStore)

        val operationId = store.beginDeletion(
            listOf(
                DeletionGroup(
                    id = "group-a",
                    expectedFullHash = "hash-group-a",
                    files = listOf(
                        deletionFile("keep", memberId = "member-keep", selected = false),
                        deletionFile("delete", memberId = "member-delete", selected = true),
                    ),
                ),
            ),
        )

        val pending = store.pendingDeletions().single()
        val pendingFile = pending.files.single()
        assertEquals(operationId, pending.id)
        assertEquals("group-a", pendingFile.groupId)
        assertEquals("member-delete", pendingFile.memberId)
        assertEquals("delete", pendingFile.file.id)
        assertEquals("/storage/delete.txt", pendingFile.file.canonicalPath)
        assertTrue(pendingFile.file.selectedForDeletion)
    }

    @Test
    fun `reconciliation atomically removes confirmed missing files and refreshes group and session totals`() = runTest {
        val files = listOf("keep", "missing", "present").map(::file)
        scanStore.startSession(SESSION_ID, 10L)
        assertTrue(
            scanStore.replaceStagedResults(
                SESSION_ID,
                files,
                listOf(group("group-a", copies = 3, reclaimable = 200L)),
                listOf(
                    member("keep", "group-a"),
                    member("missing", "group-a", selected = true),
                    member("present", "group-a", selected = true),
                ),
            ),
        )
        assertTrue(
            scanStore.completeSession(
                SESSION_ID,
                20L,
                ScanCompletionMetrics(3, 300L, 3, 1, 200L, 0, 0),
            ),
        )
        val store = RoomDeletionResultStore(scanStore)
        val operationId = store.beginDeletion(
            listOf(
                DeletionGroup(
                    id = "group-a",
                    expectedFullHash = "hash-group-a",
                    files = listOf(
                        deletionFile("keep", memberId = "keep", selected = false),
                        deletionFile("missing", memberId = "missing", selected = true),
                        deletionFile("present", memberId = "present", selected = true),
                    ),
                ),
            ),
        )

        store.reconcileDeletion(operationId, setOf("missing"))

        val active = scanStore.activeResults.first()!!
        assertEquals(setOf("keep", "present"), active.groups.single().members.mapTo(mutableSetOf()) { it.file.id })
        assertEquals(2, active.groups.single().group.copyCount)
        assertEquals(100L, active.groups.single().group.reclaimableBytes)
        assertEquals(2, active.session.duplicateFileCount)
        assertEquals(1, active.session.duplicateGroupCount)
        assertEquals(100L, active.session.reclaimableBytes)
        assertFalse(rowExists("indexed_files", "missing"))
        assertTrue(rowExists("indexed_files", "present"))
        assertTrue(store.pendingDeletions().isEmpty())
    }

    @Test
    fun `checkpoint applies database progress while completion also clears the operation`() = runTest {
        val files = listOf("keep", "deleted", "present").map(::file)
        scanStore.startSession(SESSION_ID, 10L)
        assertTrue(
            scanStore.replaceStagedResults(
                SESSION_ID,
                files,
                listOf(group("group-a", copies = 3, reclaimable = 200L)),
                listOf(
                    member("keep", "group-a"),
                    member("deleted", "group-a", selected = true),
                    member("present", "group-a", selected = true),
                ),
            ),
        )
        assertTrue(
            scanStore.completeSession(
                SESSION_ID,
                20L,
                ScanCompletionMetrics(3, 300L, 3, 1, 200L, 0, 0),
            ),
        )
        val store = RoomDeletionResultStore(scanStore)
        val deletionGroup = DeletionGroup(
            id = "group-a",
            expectedFullHash = "hash-group-a",
            files = listOf(
                deletionFile("keep", memberId = "keep", selected = false),
                deletionFile("deleted", memberId = "deleted", selected = true),
                deletionFile("present", memberId = "present", selected = true),
            ),
        )
        val operationId = store.beginDeletion(listOf(deletionGroup))
        val update = DeletionDatabaseUpdate(
            removedIndexedFileIds = setOf("deleted"),
            removedMemberIds = setOf("deleted"),
            changedIndexedFileIds = emptySet(),
            groups = listOf(
                GroupDeletionUpdate(
                    groupId = "group-a",
                    remainingMemberIds = setOf("keep", "present"),
                    remainingCopyCount = 2,
                    remainingSelectedReclaimableBytes = 100L,
                    removeGroup = false,
                ),
            ),
        )

        store.checkpointDeletion(operationId, update)

        assertFalse(rowExists("indexed_files", "deleted"))
        assertEquals(listOf(operationId), store.pendingDeletions().map { it.id })

        store.completeDeletion(operationId, update)

        assertTrue(store.pendingDeletions().isEmpty())
        val active = scanStore.activeResults.first()!!
        assertEquals(2, active.session.duplicateFileCount)
        assertEquals(100L, active.session.reclaimableBytes)
    }

    @Test
    fun `deletion update removes files and members marks changed prunes singleton and recalculates session`() = runTest {
        val files = listOf("a-keep", "a-deleted", "b-keep", "b-selected", "b-vanished", "b-changed")
            .map(::file)
        val groups = listOf(group("group-a", 2, 100L), group("group-b", 4, 300L))
        val members = listOf(
            member("a-keep", "group-a"),
            member("a-deleted", "group-a", selected = true),
            member("b-keep", "group-b"),
            member("b-selected", "group-b", selected = true),
            member("b-vanished", "group-b", selected = true),
            member("b-changed", "group-b", selected = true),
        )
        scanStore.startSession(SESSION_ID, 10L)
        assertTrue(scanStore.replaceStagedResults(SESSION_ID, files, groups, members))
        assertTrue(
            scanStore.completeSession(
                SESSION_ID,
                20L,
                ScanCompletionMetrics(6, 600L, 6, 2, 400L, 0, 0),
            ),
        )

        RoomDeletionResultStore(scanStore).applyDeletion(
            DeletionDatabaseUpdate(
                removedIndexedFileIds = setOf("a-deleted", "b-vanished"),
                removedMemberIds = setOf("a-deleted", "b-vanished", "b-changed"),
                changedIndexedFileIds = setOf("b-changed"),
                groups = listOf(
                    GroupDeletionUpdate("group-a", setOf("a-keep"), 1, 0L, removeGroup = true),
                    GroupDeletionUpdate(
                        "group-b",
                        setOf("b-keep", "b-selected"),
                        2,
                        100L,
                        removeGroup = false,
                    ),
                ),
            ),
        )

        val active = scanStore.activeResults.first()!!
        assertEquals(1, active.groups.size)
        assertEquals("group-b", active.groups.single().group.id)
        assertEquals(setOf("b-keep", "b-selected"), active.groups.single().members.mapTo(mutableSetOf()) { it.file.id })
        assertEquals(2, active.session.duplicateFileCount)
        assertEquals(1, active.session.duplicateGroupCount)
        assertEquals(100L, active.session.reclaimableBytes)
        assertFalse(rowExists("indexed_files", "a-deleted"))
        assertFalse(rowExists("indexed_files", "b-vanished"))
        assertTrue(rowExists("indexed_files", "b-changed"))
        assertEquals(1L, longValue("SELECT changedSinceScan FROM indexed_files WHERE id = ?", "b-changed"))
        assertFalse(rowExists("duplicate_members", "b-changed"))
    }

    private fun rowExists(table: String, id: String): Boolean =
        longValue("SELECT COUNT(*) FROM $table WHERE id = ?", id) == 1L

    private fun longValue(query: String, argument: String): Long =
        database.openHelper.readableDatabase.query(query, arrayOf(argument)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun file(id: String) = IndexedFileEntity(
        id = id,
        sessionId = SESSION_ID,
        canonicalPath = "/storage/$id.txt",
        displayName = "$id.txt",
        extension = "txt",
        mimeType = "text/plain",
        category = "DOCUMENTS",
        sizeBytes = 100L,
        lastModified = 10L,
        volume = "primary",
        parentPath = "/storage",
        readable = true,
        writable = true,
    )

    private fun group(id: String, copies: Int, reclaimable: Long) = DuplicateGroupEntity(
        id = id,
        sessionId = SESSION_ID,
        category = "DOCUMENTS",
        contentHash = "hash-$id",
        fileSize = 100L,
        copyCount = copies,
        reclaimableBytes = reclaimable,
        displayTitle = id,
    )

    private fun member(id: String, groupId: String, selected: Boolean = false) = DuplicateMemberEntity(
        id = id,
        groupId = groupId,
        indexedFileId = id,
        recommendedKeep = id.endsWith("keep"),
        selectedForDeletion = selected,
        protectedFromAutoSelection = false,
    )

    private fun deletionFile(
        id: String,
        memberId: String,
        selected: Boolean,
    ) = DeletionFile(
        id = id,
        memberId = memberId,
        canonicalPath = "/storage/$id.txt",
        sizeBytes = 100L,
        lastModifiedMillis = 10L,
        contentUri = "content://media/$id",
        selectedForDeletion = selected,
    )

    private companion object {
        const val SESSION_ID = "session"
    }
}
