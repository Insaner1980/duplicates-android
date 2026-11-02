package com.emma.duplicates.data

import android.app.Application
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.PendingDeletionItemEntity
import com.emma.duplicates.core.database.PendingDeletionOperationEntity
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.core.database.ScanStoreDatabaseTest
import com.emma.duplicates.core.database.indexedPhoto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanDeletionCoordinationTest : ScanStoreDatabaseTest() {
    @Test
    fun pendingDeletionPreventsStartingAndRetiringItsResults() =
        runTest {
            seedResults()
            scanStore.beginDeletion(operation(), listOf(item()))

            val failure = runCatching { scanStore.startSession("new", 30L) }.exceptionOrNull()

            assertTrue("Pending deletion must block a new scan", failure is IllegalStateException)
            assertNull(database.scanDao().getSession("new"))
            assertEquals(
                "old",
                scanStore.activeResults
                    .first()
                    ?.session
                    ?.id,
            )
            assertEquals(
                "operation",
                scanStore
                    .pendingDeletions()
                    .single()
                    .operation.id,
            )
        }

    @Test
    fun runningScanPreventsDeletionJournalAdmission() =
        runTest {
            seedResults()
            scanStore.startSession("new", 30L)

            val failure = runCatching { scanStore.beginDeletion(operation(), listOf(item())) }.exceptionOrNull()

            assertTrue("Running scan must block deletion", failure is IllegalStateException)
            assertTrue(scanStore.pendingDeletions().isEmpty())
            assertEquals("new", scanStore.runningSession.first()?.id)
        }

    @Test
    fun completedReplacementRejectsTheRetiredDeletionBatch() =
        runTest {
            seedResults()
            scanStore.startSession("new", 30L)
            scanStore.completeSession("new", 40L, ScanCompletionMetrics(0, 0L, 0, 0, 0L, 0, 0))

            val failure = runCatching { scanStore.beginDeletion(operation(), listOf(item())) }.exceptionOrNull()

            assertTrue("Retired member IDs must not authorize deletion", failure is IllegalStateException)
            assertTrue(scanStore.pendingDeletions().isEmpty())
            assertEquals(
                "new",
                scanStore.activeResults
                    .first()
                    ?.session
                    ?.id,
            )
        }

    private suspend fun seedResults() {
        scanStore.startSession("old", 10L)
        assertTrue(
            scanStore.replaceStagedResults(
                "old",
                listOf(
                    indexedPhoto("keep", "old", "/storage/keep.jpg"),
                    indexedPhoto("delete", "old", "/storage/delete.jpg"),
                ),
                listOf(DuplicateGroupEntity("group", "old", "PHOTOS", "hash", 100L, 2, 100L, "photo")),
                listOf(
                    DuplicateMemberEntity("keep", "group", "keep", true, false, false),
                    DuplicateMemberEntity("delete", "group", "delete", false, true, false),
                ),
            ),
        )
        assertTrue(scanStore.completeSession("old", 20L, ScanCompletionMetrics(2, 200L, 2, 1, 100L, 0, 0)))
    }

    private fun operation() = PendingDeletionOperationEntity("operation", 25L)

    private fun item() =
        PendingDeletionItemEntity("operation", "group", "delete", "delete", "/storage/delete.jpg", 100L, 10L, null)
}
