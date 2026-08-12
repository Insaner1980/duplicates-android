package com.emma.duplicates.domain.deletion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionCoordinatorTest {
    @Test
    fun `operation is persisted before deletion and checkpointed after every irreversible chunk`() = runTest {
        val events = mutableListOf<String>()
        val group = group(
            file("keep", selected = false),
            file("direct", selected = true),
            file("media-1", selected = true, contentUri = "content://media/1"),
            file("media-2", selected = true, contentUri = "content://media/2"),
            file("media-3", selected = true, contentUri = "content://media/3"),
        )
        val storage = FakeDeletionStorage(group.files, events)
        val media = FakeMediaDeletionAuthorizer(storage, MediaAuthorization.APPROVED, events)
        val store = RecordingDeletionResultStore(events)

        coordinator(storage, media, store, mediaChunkSize = 2).delete(listOf(group))

        assertEquals(
            listOf(
                "begin",
                "delete:direct",
                "checkpoint",
                "authorize:media-1,media-2",
                "checkpoint",
                "authorize:media-3",
                "checkpoint",
                "complete",
            ),
            events,
        )
    }

    @Test
    fun `all selected direct files are deleted and persisted`() = runTest {
        val group = group(
            file("keep", selected = false),
            file("first", selected = true, memberId = "member-first"),
            file("second", selected = true),
        )
        val storage = FakeDeletionStorage(group.files)
        val store = RecordingDeletionResultStore()
        val coordinator = coordinator(storage, store = store)

        val result = coordinator.delete(listOf(group))

        assertEquals(setOf("first", "second"), storage.directDeletionCalls.toSet())
        assertEquals(2, result.deletedFileCount)
        assertEquals(200, result.reclaimedBytes)
        assertEquals(setOf("first", "second"), result.databaseUpdate.removedIndexedFileIds)
        assertEquals(setOf("member-first", "second"), result.databaseUpdate.removedMemberIds)
        assertTrue(result.databaseUpdate.groups.single().removeGroup)
        assertSame(result.databaseUpdate, store.appliedUpdates.last())
    }

    @Test
    fun `partial direct deletion failure reports exact outcomes and remaining total`() = runTest {
        val group = group(
            file("keep", selected = false),
            file("deleted", selected = true),
            file("failed", selected = true),
        )
        val storage = FakeDeletionStorage(group.files).apply { directFailures += "failed" }

        val result = coordinator(storage).delete(listOf(group))

        assertEquals(DeletionStatus.DELETED, result.outcome("deleted").status)
        assertEquals(DeletionStatus.DELETE_FAILED, result.outcome("failed").status)
        assertEquals(1, result.deletedFileCount)
        assertEquals(1, result.failedFileCount)
        assertEquals(100, result.reclaimedBytes)
        assertEquals(2, result.databaseUpdate.groups.single().remainingCopyCount)
        assertEquals(100, result.databaseUpdate.groups.single().remainingSelectedReclaimableBytes)
    }

    @Test
    fun `summary counts every status other than deleted as not deleted`() {
        val result = DeletionResult(
            outcomes = listOf(
                outcome(DeletionStatus.DELETED),
                outcome(DeletionStatus.VANISHED),
                outcome(DeletionStatus.CHANGED),
                outcome(DeletionStatus.REVALIDATION_FAILED),
                outcome(DeletionStatus.NOT_ACCESSIBLE),
                outcome(DeletionStatus.AUTHORIZATION_CANCELED),
                outcome(DeletionStatus.DELETE_FAILED),
                outcome(DeletionStatus.BLOCKED_NO_VALID_COPY),
            ),
            databaseUpdate = emptyDatabaseUpdate(),
        )

        assertEquals(1, result.deletedFileCount)
        assertEquals(7, result.failedFileCount)
    }

    @Test
    fun `vanished outcome never contributes reclaimed bytes`() {
        val result = DeletionResult(
            outcomes = listOf(
                outcome(DeletionStatus.DELETED, reclaimedBytes = 100),
                outcome(DeletionStatus.VANISHED, reclaimedBytes = 500),
            ),
            databaseUpdate = emptyDatabaseUpdate(),
        )

        assertEquals(100, result.reclaimedBytes)
    }

    @Test
    fun `changed file is rehashed and rejected before deletion`() = runTest {
        val group = group(
            file("keep", selected = false),
            file("also-keep", selected = false),
            file("changed", selected = true),
        )
        val storage = FakeDeletionStorage(group.files).apply {
            snapshots["changed"] = snapshot(lastModifiedMillis = 2)
            hashes["changed"] = "different-hash"
        }

        val result = coordinator(storage).delete(listOf(group))

        assertEquals(listOf("changed"), storage.fullHashCalls)
        assertTrue(storage.directDeletionCalls.isEmpty())
        assertEquals(DeletionStatus.CHANGED, result.outcome("changed").status)
        assertEquals(setOf("changed"), result.databaseUpdate.changedIndexedFileIds)
        assertEquals(setOf("changed"), result.databaseUpdate.removedMemberIds)
    }

    @Test
    fun `metadata mismatch with matching hash still requires byte membership validation`() = runTest {
        val group = group(file("keep", selected = false), file("changed", selected = true))
        val storage = FakeDeletionStorage(group.files).apply {
            snapshots["changed"] = snapshot(lastModifiedMillis = 2)
            hashes["changed"] = EXPECTED_HASH
            unequalPairs += setOf("changed", "keep")
        }

        val result = coordinator(storage).delete(listOf(group))

        assertEquals(listOf("changed"), storage.fullHashCalls)
        assertEquals(listOf(setOf("changed", "keep")), storage.comparisonCalls)
        assertEquals(DeletionStatus.CHANGED, result.outcome("changed").status)
        assertTrue(storage.directDeletionCalls.isEmpty())
    }

    @Test
    fun `metadata mismatch may be deleted after full hash and byte validation both pass`() = runTest {
        val group = group(file("keep", selected = false), file("changed", selected = true))
        val storage = FakeDeletionStorage(group.files).apply {
            snapshots["changed"] = snapshot(lastModifiedMillis = 2)
            hashes["changed"] = EXPECTED_HASH
        }

        val result = coordinator(storage).delete(listOf(group))

        assertEquals(DeletionStatus.DELETED, result.outcome("changed").status)
        assertEquals(listOf(setOf("changed", "keep")), storage.comparisonCalls)
    }

    @Test
    fun `vanished file is not deleted again and is removed from persisted results`() = runTest {
        val group = group(file("keep", selected = false), file("vanished", selected = true))
        val storage = FakeDeletionStorage(group.files).apply { snapshots.remove("vanished") }

        val result = coordinator(storage).delete(listOf(group))

        assertEquals(DeletionStatus.VANISHED, result.outcome("vanished").status)
        assertTrue(storage.directDeletionCalls.isEmpty())
        assertEquals(setOf("vanished"), result.databaseUpdate.removedIndexedFileIds)
        assertTrue(result.databaseUpdate.groups.single().removeGroup)
    }

    @Test
    fun `file that vanishes during direct deletion is removed from persisted results without reclaimed bytes`() = runTest {
        val group = group(file("keep", selected = false), file("vanished", selected = true))
        val storage = FakeDeletionStorage(group.files).apply { directVanishes += "vanished" }

        val result = coordinator(storage).delete(listOf(group))

        assertEquals(listOf("vanished"), storage.directDeletionCalls)
        assertEquals(DeletionStatus.VANISHED, result.outcome("vanished").status)
        assertEquals(0, result.reclaimedBytes)
        assertEquals(setOf("vanished"), result.databaseUpdate.removedIndexedFileIds)
        assertEquals(setOf("vanished"), result.databaseUpdate.removedMemberIds)
        assertEquals(setOf("keep"), result.databaseUpdate.groups.single().remainingMemberIds)
        assertTrue(result.databaseUpdate.groups.single().removeGroup)
    }

    @Test
    fun `canceled MediaStore authorization deletes nothing`() = runTest {
        val group = group(file("keep", selected = false), file("media", selected = true, contentUri = "content://media/1"))
        val storage = FakeDeletionStorage(group.files)
        val media = FakeMediaDeletionAuthorizer(storage, MediaAuthorization.CANCELED)

        val result = coordinator(storage, media = media).delete(listOf(group))

        assertEquals(listOf(listOf("media")), media.requests)
        assertEquals(DeletionStatus.AUTHORIZATION_CANCELED, result.outcome("media").status)
        assertEquals(0, result.deletedFileCount)
        assertTrue(result.databaseUpdate.removedIndexedFileIds.isEmpty())
        assertTrue(storage.directDeletionCalls.isEmpty())
    }

    @Test
    fun `content URI alone does not require MediaStore authorization`() = runTest {
        val document = DeletionFile(
            id = "document",
            canonicalPath = "/storage/emulated/0/Download/document.pdf",
            sizeBytes = 100,
            lastModifiedMillis = 1,
            contentUri = "content://media/external/file/1",
            selectedForDeletion = true,
        )
        val group = group(file("keep", selected = false), document)
        val storage = FakeDeletionStorage(group.files)
        val media = FakeMediaDeletionAuthorizer(storage, MediaAuthorization.CANCELED)

        val result = coordinator(storage, media = media).delete(listOf(group))

        assertEquals(listOf("document"), storage.directDeletionCalls)
        assertTrue(media.requests.isEmpty())
        assertEquals(DeletionStatus.DELETED, result.outcome("document").status)
    }

    @Test
    fun `database update removes a group when only one matching file remains and recalculates totals`() = runTest {
        val group = group(file("keep", selected = false, sizeBytes = 250), file("delete", selected = true, sizeBytes = 250))
        val store = ApplyingFakeResultStore(mapOf(group.id to group.files.mapTo(mutableSetOf()) { it.id }))

        val result = coordinator(FakeDeletionStorage(group.files), store = store).delete(listOf(group))

        assertEquals(emptySet<String>(), store.remainingGroupIds)
        assertEquals(0, store.duplicateGroupCount)
        assertEquals(0, store.duplicateFileCount)
        assertEquals(0, store.reclaimableBytes)
        assertEquals(250, result.reclaimedBytes)
    }

    @Test
    fun `unselected files are never sent to either deletion boundary`() = runTest {
        val group = group(
            file("direct-keep", selected = false),
            file("media-keep", selected = false, contentUri = "content://media/keep"),
            file("delete", selected = true),
        )
        val storage = FakeDeletionStorage(group.files)
        val media = FakeMediaDeletionAuthorizer(storage, MediaAuthorization.APPROVED)

        coordinator(storage, media = media).delete(listOf(group))

        assertEquals(listOf("delete"), storage.directDeletionCalls)
        assertTrue(media.requests.isEmpty())
        assertFalse("direct-keep" in storage.directDeletionCalls)
        assertFalse("media-keep" in storage.directDeletionCalls)
    }

    @Test
    fun `media requests are processed in bounded chunks`() = runTest {
        val selected = (1..5).map { file("media-$it", selected = true, contentUri = "content://media/$it") }
        val group = group(file("keep", selected = false), *selected.toTypedArray())
        val storage = FakeDeletionStorage(group.files)
        val media = FakeMediaDeletionAuthorizer(storage, MediaAuthorization.APPROVED)

        val result = coordinator(storage, media = media, mediaChunkSize = 2).delete(listOf(group))

        assertEquals(listOf(2, 2, 1), media.requests.map { it.size })
        assertEquals(5, result.deletedFileCount)
    }

    @Test
    fun `deletion is blocked when no valid unselected copy remains`() = runTest {
        val group = group(file("missing-keep", selected = false), file("selected", selected = true))
        val storage = FakeDeletionStorage(group.files).apply { snapshots.remove("missing-keep") }

        val result = coordinator(storage).delete(listOf(group))

        assertEquals(DeletionStatus.BLOCKED_NO_VALID_COPY, result.outcome("selected").status)
        assertTrue(storage.directDeletionCalls.isEmpty())
    }

    private fun coordinator(
        storage: FakeDeletionStorage,
        media: MediaDeletionAuthorizer = FakeMediaDeletionAuthorizer(storage, MediaAuthorization.APPROVED),
        store: DeletionResultStore = RecordingDeletionResultStore(),
        mediaChunkSize: Int = 2_000,
    ) = DeletionCoordinator(storage, media, store, mediaChunkSize)

    private fun DeletionResult.outcome(fileId: String): FileDeletionOutcome =
        outcomes.single { it.fileId == fileId }

    private fun outcome(
        status: DeletionStatus,
        reclaimedBytes: Long = 0,
    ) = FileDeletionOutcome("group", status.name, status, reclaimedBytes)

    private fun emptyDatabaseUpdate() =
        DeletionDatabaseUpdate(emptySet(), emptySet(), emptySet(), emptyList())

    private fun group(vararg files: DeletionFile) = DeletionGroup(
        id = "group",
        expectedFullHash = EXPECTED_HASH,
        files = files.toList(),
    )

    private fun file(
        id: String,
        selected: Boolean,
        memberId: String = id,
        sizeBytes: Long = 100,
        contentUri: String? = null,
    ) = DeletionFile(
        id = id,
        memberId = memberId,
        canonicalPath = "/storage/emulated/0/$id.bin",
        sizeBytes = sizeBytes,
        lastModifiedMillis = 1,
        contentUri = contentUri,
        requiresMediaAuthorization = contentUri != null,
        selectedForDeletion = selected,
    )

    private fun snapshot(
        sizeBytes: Long = 100,
        lastModifiedMillis: Long = 1,
        isReadable: Boolean = true,
        isWritable: Boolean = true,
    ) = CurrentFileSnapshot(sizeBytes, lastModifiedMillis, isReadable, isWritable)

    private class FakeDeletionStorage(
        files: List<DeletionFile>,
        private val events: MutableList<String>? = null,
    ) : DeletionStorage {
        val snapshots = files.associate { it.id to CurrentFileSnapshot(it.sizeBytes, it.lastModifiedMillis, true, true) }.toMutableMap()
        val hashes = files.associate { it.id to EXPECTED_HASH }.toMutableMap()
        val directFailures = mutableSetOf<String>()
        val directVanishes = mutableSetOf<String>()
        val unequalPairs = mutableSetOf<Set<String>>()
        val directDeletionCalls = mutableListOf<String>()
        val fullHashCalls = mutableListOf<String>()
        val comparisonCalls = mutableListOf<Set<String>>()

        override suspend fun snapshot(file: DeletionFile): CurrentFileSnapshot? = snapshots[file.id]

        override suspend fun fullHash(file: DeletionFile): String? {
            fullHashCalls += file.id
            return hashes[file.id]
        }

        override suspend fun contentsEqual(first: DeletionFile, second: DeletionFile): Boolean {
            val pair = setOf(first.id, second.id)
            comparisonCalls += pair
            return pair !in unequalPairs
        }

        override suspend fun deleteDirect(file: DeletionFile): DirectDeletionResult {
            events?.add("delete:${file.id}")
            directDeletionCalls += file.id
            if (file.id in directVanishes) {
                snapshots.remove(file.id)
                return DirectDeletionResult.FAILED
            }
            if (file.id in directFailures) return DirectDeletionResult.FAILED
            snapshots.remove(file.id)
            return DirectDeletionResult.DELETED
        }

        fun remove(file: DeletionFile) {
            snapshots.remove(file.id)
        }
    }

    private class FakeMediaDeletionAuthorizer(
        private val storage: FakeDeletionStorage,
        private val authorization: MediaAuthorization,
        private val events: MutableList<String>? = null,
    ) : MediaDeletionAuthorizer {
        val requests = mutableListOf<List<String>>()

        override suspend fun requestDeletion(files: List<DeletionFile>): MediaAuthorization {
            events?.add("authorize:${files.joinToString(",") { it.id }}")
            requests += files.map { it.id }
            if (authorization == MediaAuthorization.APPROVED) files.forEach(storage::remove)
            return authorization
        }
    }

    private class RecordingDeletionResultStore(
        private val events: MutableList<String>? = null,
    ) : DeletionResultStore {
        val appliedUpdates = mutableListOf<DeletionDatabaseUpdate>()

        override suspend fun beginDeletion(groups: List<DeletionGroup>): String {
            events?.add("begin")
            return "operation"
        }

        override suspend fun checkpointDeletion(
            operationId: String,
            update: DeletionDatabaseUpdate,
        ) {
            events?.add("checkpoint")
            appliedUpdates += update
        }

        override suspend fun completeDeletion(
            operationId: String,
            update: DeletionDatabaseUpdate,
        ) {
            events?.add("complete")
            appliedUpdates += update
        }

        override suspend fun applyDeletion(update: DeletionDatabaseUpdate) {
            appliedUpdates += update
        }
    }

    private class ApplyingFakeResultStore(initialGroups: Map<String, Set<String>>) : DeletionResultStore {
        private val groups = initialGroups.mapValues { it.value.toMutableSet() }.toMutableMap()
        var duplicateGroupCount = groups.size
            private set
        var duplicateFileCount = groups.values.sumOf { it.size }
            private set
        var reclaimableBytes = 0L
            private set
        val remainingGroupIds: Set<String> get() = groups.keys

        override suspend fun applyDeletion(update: DeletionDatabaseUpdate) {
            update.groups.forEach { groupUpdate ->
                val members = groups[groupUpdate.groupId] ?: return@forEach
                members.removeAll(update.removedMemberIds)
                if (groupUpdate.removeGroup) groups.remove(groupUpdate.groupId)
            }
            duplicateGroupCount = groups.size
            duplicateFileCount = groups.values.sumOf { it.size }
            reclaimableBytes = update.groups.filterNot { it.removeGroup }.sumOf { it.remainingSelectedReclaimableBytes }
        }
    }

    private companion object {
        const val EXPECTED_HASH = "expected-full-sha256"
    }
}
