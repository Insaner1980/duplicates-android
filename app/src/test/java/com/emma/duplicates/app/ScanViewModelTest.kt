package com.emma.duplicates.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.R
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanSessionEntity
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.ui.scan.ScanPhase
import com.emma.duplicates.ui.scan.ScanRunStatus
import com.emma.duplicates.ui.scan.ScanStepStatus
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var database: DuplicatesDatabase
    private lateinit var scanStore: ScanStore

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
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
    fun `running session maps progress phases candidate groups and stop actions`() =
        runTest(mainDispatcherRule.dispatcher) {
            database.scanDao().insertSession(
                session(
                    id = "running",
                    status = ScanStatuses.RUNNING,
                    phase = ScanPhases.COMPARING_CANDIDATES,
                    completedWork = 25L,
                    totalWork = 100L,
                    scannedFiles = 7,
                    candidateGroups = 3,
                    path = "/storage/Documents",
                ),
            )
            val scheduler = mockk<ScanScheduler>(relaxed = true)
            val viewModel =
                ScanViewModel(
                    context,
                    scanStore,
                    scheduler,
                    StorageVolumeSource {
                        listOf(
                            AvailableStorageVolume(
                                id = "portable",
                                label = "Portable drive",
                                directory = File("/storage"),
                                mediaStoreVolumeName = "portable",
                                isPrimary = false,
                                isReadOnly = false,
                                totalBytes = 100L,
                                freeBytes = 50L,
                            ),
                        )
                    },
                )

            val running = viewModel.state.first { it.filesIndexed == 7 }
            assertEquals(ScanRunStatus.RUNNING, running.runStatus)
            assertEquals(0.25f, running.overallProgress)
            assertEquals(3, running.groupsFound)
            assertFalse(running.groupsAreConfirmed)
            assertEquals("Portable drive", running.currentOperation)
            assertEquals("/storage/Documents", running.currentPath)
            assertEquals(ScanStepStatus.COMPLETED, running.phases.single { it.phase == ScanPhase.FINDING_FILES }.status)
            assertEquals(ScanStepStatus.IN_PROGRESS, running.phases.single { it.phase == ScanPhase.COMPARING_CANDIDATES }.status)

            viewModel.requestStop()
            assertTrue(viewModel.state.first { it.stopConfirmationVisible }.stopConfirmationVisible)
            viewModel.dismissStop()
            assertFalse(viewModel.state.first { !it.stopConfirmationVisible }.stopConfirmationVisible)
            viewModel.requestStop()
            viewModel.confirmStop()
            verify(exactly = 1) { scheduler.stopScan() }
            assertFalse(viewModel.state.value.stopConfirmationVisible)
        }

    @Test
    fun `completed session confirms groups progress and every phase`() =
        runTest(mainDispatcherRule.dispatcher) {
            database.scanDao().insertSession(
                session(
                    id = "completed",
                    status = ScanStatuses.COMPLETED,
                    phase = null,
                    completedWork = 0L,
                    totalWork = null,
                    scannedFiles = 20,
                    duplicateGroups = 4,
                    reclaimable = 1_000L,
                    skipped = 2,
                ),
            )
            val viewModel = ScanViewModel(context, scanStore, mockk(relaxed = true))

            val completed = viewModel.state.first { it.runStatus == ScanRunStatus.COMPLETED }

            assertEquals(1f, completed.overallProgress)
            assertEquals(4, completed.groupsFound)
            assertTrue(completed.groupsAreConfirmed)
            assertEquals(1_000L, completed.reclaimableBytes)
            assertEquals(2, completed.skippedFileCount)
            assertTrue(completed.phases.all { it.status == ScanStepStatus.COMPLETED })
            assertNull(completed.failureMessage)
        }

    @Test
    fun `canceled latest session exposes a calm terminal state`() =
        runTest(mainDispatcherRule.dispatcher) {
            database.scanDao().insertSession(session("canceled", ScanStatuses.CANCELED, startedAt = 10L))
            val viewModel = ScanViewModel(context, scanStore, mockk(relaxed = true))

            val canceled = viewModel.state.first { it.runStatus == ScanRunStatus.CANCELED }
            assertEquals(context.getString(R.string.scan_canceled_message), canceled.failureMessage)
            assertNull(canceled.overallProgress)
        }

    @Test
    fun `persisted scan failure reasons expose their actionable messages`() =
        runTest(mainDispatcherRule.dispatcher) {
            val expectedMessages =
                listOf(
                    ScanFailureReasons.PERMISSION_REVOKED to R.string.scan_failure_permission_revoked,
                    ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE to R.string.scan_failure_volume_unavailable,
                    ScanFailureReasons.STORAGE_FULL to R.string.scan_failure_storage_full,
                    ScanFailureReasons.DATABASE to R.string.scan_failure_database,
                    ScanFailureReasons.GENERIC to R.string.scan_failure_generic,
                    ScanFailureReasons.FOREGROUND_WORKER to R.string.scan_failure_foreground_worker,
                )
            val viewModel = ScanViewModel(context, scanStore, mockk(relaxed = true))

            expectedMessages.forEachIndexed { index, (reason, messageResource) ->
                database.scanDao().insertSession(
                    session(
                        id = "failed-$index",
                        status = ScanStatuses.FAILED,
                        startedAt = 20L + index,
                        failureReason = reason,
                    ),
                )

                val expectedMessage = context.getString(messageResource)
                val failed =
                    viewModel.state.first {
                        it.runStatus == ScanRunStatus.FAILED && it.failureMessage == expectedMessage
                    }
                assertEquals(expectedMessage, failed.failureMessage)
                assertNull(failed.overallProgress)
            }
        }

    private fun session(
        id: String,
        status: String,
        startedAt: Long = 1L,
        phase: String? = null,
        completedWork: Long = 0L,
        totalWork: Long? = null,
        scannedFiles: Int = 0,
        candidateGroups: Int = 0,
        duplicateGroups: Int = 0,
        reclaimable: Long = 0L,
        skipped: Int = 0,
        path: String? = null,
        failureReason: String? = null,
    ) = ScanSessionEntity(
        id = id,
        status = status,
        startedAt = startedAt,
        completedAt = if (status == ScanStatuses.RUNNING) null else startedAt + 1,
        scannedFileCount = scannedFiles,
        duplicateGroupCount = duplicateGroups,
        reclaimableBytes = reclaimable,
        skippedFileCount = skipped,
        active = status == ScanStatuses.COMPLETED,
        canceled = status == ScanStatuses.CANCELED,
        phase = phase,
        currentPath = path,
        progressCompletedWork = completedWork,
        progressTotalWork = totalWork,
        candidateGroupCount = candidateGroups,
        failureReason = failureReason,
    )
}
