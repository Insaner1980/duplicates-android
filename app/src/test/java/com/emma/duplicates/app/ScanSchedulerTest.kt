package com.emma.duplicates.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.ScanSessionEntity
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ScanStoreDatabaseTest
import com.emma.duplicates.core.database.indexedPhoto
import com.emma.duplicates.ui.scan.ScanRunStatus
import com.google.common.util.concurrent.Futures
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanSchedulerTest : ScanStoreDatabaseTest() {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val workManager = mockk<WorkManager>()

    @After
    fun restoreWorkManager() {
        unmockkObject(WorkManager.Companion)
    }

    @Test
    fun `orphaned running scan becomes failed and permits a new scan without losing completed results`() =
        runTest(mainDispatcherRule.dispatcher) {
            database.scanDao().insertSession(
                ScanSessionEntity("completed", ScanStatuses.COMPLETED, 1L, active = true),
            )
            database.scanDao().insertIndexedFiles(
                listOf(
                    indexedPhoto("a", "completed", "/photos/a.jpg"),
                    indexedPhoto("b", "completed", "/photos/b.jpg"),
                ),
            )
            database.scanDao().insertGroups(
                listOf(DuplicateGroupEntity("group", "completed", "PHOTOS", "hash", 100L, 2, 100L, "Photos")),
            )
            database.scanDao().insertMembers(
                listOf(
                    DuplicateMemberEntity("a", "group", "a", true, false, false),
                    DuplicateMemberEntity("b", "group", "b", false, true, false),
                ),
            )
            scanStore.startSession("orphan", 2L)
            val scheduler = scheduler(flowOf(emptyList()))
            val operation = mockk<Operation>()
            every { operation.result } returns Futures.immediateFuture(Operation.SUCCESS)
            every {
                workManager.enqueueUniqueWork(
                    ScanScheduler.UNIQUE_SCAN_WORK,
                    ExistingWorkPolicy.KEEP,
                    any<OneTimeWorkRequest>(),
                )
            } returns operation

            val request = scheduler.startScan(scanStore)

            assertEquals("orphan", request.baselineSessionId)
            assertTrue(request.awaitNewSession)
            assertEquals(ScanStatuses.FAILED, database.scanDao().getSession("orphan")?.status)
            assertNull(scanStore.runningSession.first())
            assertEquals("completed", scanStore.activeCompletedSession.first()?.id)
            val previousGroup =
                scanStore.activeResults
                    .first()
                    ?.groups
                    ?.single()
            assertEquals("group", previousGroup?.group?.id)
            assertEquals(
                setOf("/photos/a.jpg", "/photos/b.jpg"),
                previousGroup
                    ?.members
                    ?.map { it.file.canonicalPath }
                    ?.toSet(),
            )
            val model = ScanViewModel(context, scanStore, scheduler, savedStateHandle = SavedStateHandle())
            assertEquals(ScanRunStatus.FAILED, model.state.first { it.sessionId == "orphan" }.runStatus)
            verify(exactly = 1) {
                workManager.enqueueUniqueWork(
                    ScanScheduler.UNIQUE_SCAN_WORK,
                    ExistingWorkPolicy.KEEP,
                    any<OneTimeWorkRequest>(),
                )
            }
        }

    @Test
    fun `live enqueued work preserves running session and opens it without another enqueue`() =
        runTest(mainDispatcherRule.dispatcher) {
            scanStore.startSession("running", 2L)
            val active = mockk<WorkInfo>()
            every { active.state } returns WorkInfo.State.ENQUEUED
            val scheduler = scheduler(flowOf(listOf(active)))

            assertTrue(scheduler.reconcileSession(scanStore))
            val request = scheduler.startScan(scanStore)

            assertFalse(request.awaitNewSession)
            assertEquals("running", scanStore.runningSession.first()?.id)
            verify(exactly = 0) {
                workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
            }
        }

    @Test
    fun `cancelled work remains canceled when worker session cleanup is delayed`() =
        runTest(mainDispatcherRule.dispatcher) {
            scanStore.startSession("canceling", 2L)
            val canceled = mockk<WorkInfo>()
            every { canceled.state } returns WorkInfo.State.CANCELLED
            val scheduler = scheduler(flowOf(listOf(canceled)))

            assertFalse(scheduler.reconcileSession(scanStore))
            scanStore.cancelSession("canceling", 3L)

            val session = database.scanDao().getSession("canceling")
            assertEquals(ScanStatuses.CANCELED, session?.status)
            assertEquals(0, session?.errorCount)
            assertEquals(true, session?.canceled)
            assertNull(session?.failureReason)
        }

    @Test
    fun `old canceled work cannot turn a failed work snapshot into a canceled session`() =
        runTest(mainDispatcherRule.dispatcher) {
            val canceled = mockk<WorkInfo>()
            val failed = mockk<WorkInfo>()
            every { canceled.state } returns WorkInfo.State.CANCELLED
            every { failed.state } returns WorkInfo.State.FAILED
            for ((index, work) in listOf(listOf(canceled, failed), listOf(failed, canceled)).withIndex()) {
                val sessionId = "failed-$index"
                scanStore.startSession(sessionId, index + 2L)

                assertFalse(scheduler(flowOf(work)).reconcileSession(scanStore))

                val session = database.scanDao().getSession(sessionId)
                assertEquals(ScanStatuses.FAILED, session?.status)
                assertEquals(1, session?.errorCount)
                assertFalse(session?.canceled == true)
            }
        }

    @Test
    fun `reconciliation only fails the captured session when a newer session starts during the work query`() =
        runTest(mainDispatcherRule.dispatcher) {
            scanStore.startSession("orphan", 2L)
            val scheduler =
                scheduler(
                    flow {
                        scanStore.startSession("newer", 3L)
                        emit(emptyList())
                    },
                )

            assertFalse(scheduler.reconcileSession(scanStore))

            assertEquals(ScanStatuses.FAILED, database.scanDao().getSession("orphan")?.status)
            assertEquals("newer", scanStore.runningSession.first()?.id)
        }

    private fun scheduler(work: Flow<List<WorkInfo>>): ScanScheduler {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns workManager
        every { workManager.getWorkInfosForUniqueWorkFlow(ScanScheduler.UNIQUE_SCAN_WORK) } returns work
        return ScanScheduler(context, DuplicateScanWorker::class.java)
    }
}
