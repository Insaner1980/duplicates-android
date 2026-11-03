package com.emma.duplicates.app

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.data.ToolResultStore
import com.google.common.util.concurrent.Futures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToolSchedulingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = mockk<WorkManager>(relaxed = true)
    private val store = mockk<ToolResultStore>(relaxed = true)
    private val work = MutableStateFlow<List<WorkInfo>>(emptyList())
    private lateinit var scheduler: ScanScheduler

    @Before
    fun configure() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns manager
        every { manager.getWorkInfosForUniqueWorkFlow(ScanScheduler.UNIQUE_SCAN_WORK) } returns work
        coEvery { store.dao.running() } returns emptyList()
        scheduler = ScanScheduler(context, DuplicateScanWorker::class.java)
    }

    @After
    fun restore() = unmockkObject(WorkManager.Companion)

    @Test
    fun newToolRequestContainsToolVolumeAndSharedUniqueWorkPolicy() =
        runTest {
            val request = slot<OneTimeWorkRequest>()
            val operation = mockk<Operation>()
            every { operation.result } returns Futures.immediateFuture(Operation.SUCCESS)
            every {
                manager.enqueueUniqueWork(
                    ScanScheduler.UNIQUE_SCAN_WORK,
                    ExistingWorkPolicy.KEEP,
                    capture(request),
                )
            } answers
                {
                    work.value = listOf(info(request.captured.id, WorkInfo.State.ENQUEUED, CleanupTool.STORAGE_USAGE))
                    operation
                }
            val route = scheduler.startTool(CleanupTool.STORAGE_USAGE, "sd", store)
            assertEquals(ToolRoute(CleanupTool.STORAGE_USAGE.name, request.captured.id.toString()), route)
            assertEquals(
                "sd",
                request.captured.workSpec.input
                    .getString(ToolScanWorker.VOLUME_KEY),
            )
            assertEquals(
                CleanupTool.STORAGE_USAGE.name,
                request.captured.workSpec.input
                    .getString(ToolScanWorker.TOOL_KEY),
            )
        }

    @Test
    fun activeToolOrDuplicateWorkPreventsAnotherEnqueue() =
        runTest {
            val id = UUID.randomUUID()
            work.value = listOf(info(id, WorkInfo.State.RUNNING, CleanupTool.LARGE_FILES))
            assertEquals(
                ToolRoute(CleanupTool.LARGE_FILES.name, id.toString()),
                scheduler.startTool(CleanupTool.EMPTY_FOLDERS, null, store),
            )
            work.value = listOf(info(id, WorkInfo.State.RUNNING, null))
            assertNull(scheduler.startTool(CleanupTool.EMPTY_FOLDERS, null, store))
            verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
            scheduler.stopScan()
            verify { manager.cancelUniqueWork(ScanScheduler.UNIQUE_SCAN_WORK) }
        }

    @Test
    fun reconciliationDistinguishesCanceledInterruptedAndLiveRuns() =
        runTest {
            val canceled = UUID.randomUUID()
            val running = UUID.randomUUID()
            val interrupted = UUID.randomUUID()
            coEvery { store.dao.running() } returns
                listOf(canceled, running, interrupted).map {
                    ToolScanSession(it.toString(), CleanupTool.LARGE_FILES.name, 1, "{}")
                }
            work.value =
                listOf(
                    info(canceled, WorkInfo.State.CANCELLED, CleanupTool.LARGE_FILES),
                    info(running, WorkInfo.State.RUNNING, CleanupTool.LARGE_FILES),
                )
            scheduler.reconcileTools(store)
            coVerify { store.finish(canceled.toString(), ScanStatuses.CANCELED, null) }
            coVerify { store.finish(interrupted.toString(), ScanStatuses.FAILED, "interrupted") }
            coVerify(exactly = 0) { store.finish(running.toString(), any(), any()) }
        }

    @Test
    fun notificationsShowBoundedProgressAndOpenTheExactToolRun() {
        val factory = ScanNotificationFactory(context)
        val id = UUID.randomUUID()
        val determinate =
            factory
                .create(
                    id,
                    ScanPhases.COMPARING_CANDIDATES,
                    250,
                    100,
                    CleanupTool.SIMILAR_PHOTOS,
                ).notification
        assertEquals(100, determinate.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(determinate.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        val intent = shadowOf(determinate.contentIntent).savedIntent
        assertEquals(CleanupTool.SIMILAR_PHOTOS.name, intent.getStringExtra(ScanNotificationFactory.EXTRA_TOOL))
        assertEquals(id.toString(), intent.getStringExtra(ScanNotificationFactory.EXTRA_RUN))
        val negative = factory.create(id, ScanPhases.VERIFYING_DUPLICATES, -1, 100).notification
        assertEquals(0, negative.extras.getInt(Notification.EXTRA_PROGRESS))
        val unknown = factory.create(id, ScanPhases.FINDING_FILES, 10, null).notification
        assertTrue(unknown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    private fun info(
        id: UUID,
        state: WorkInfo.State,
        tool: CleanupTool?,
    ): WorkInfo =
        mockk<WorkInfo>().also {
            every { it.id } returns id
            every { it.state } returns state
            every { it.tags } returns tool?.let { setOf(ToolScanWorker.TOOL_TAG_PREFIX + it.name) }.orEmpty()
        }
}
