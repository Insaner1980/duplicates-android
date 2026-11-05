package com.emma.duplicates.app

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolResults
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.domain.deletion.CleanupBatch
import com.emma.duplicates.domain.deletion.CleanupOutcome
import com.emma.duplicates.domain.deletion.CleanupResult
import com.emma.duplicates.domain.deletion.EmptyFolderBatch
import com.emma.duplicates.domain.deletion.EmptyFolderResult
import com.emma.duplicates.domain.deletion.VerifiedCleanupFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToolViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val container = mockk<AppContainer>(relaxed = true)
    private val cleanup = mockk<com.emma.duplicates.domain.deletion.CleanupCoordinator>(relaxed = true)
    private val store = mockk<com.emma.duplicates.data.ToolResultStore>(relaxed = true)
    private val scheduler = mockk<ScanScheduler>(relaxed = true)
    private val saved = SavedStateHandle()
    private val owner = ViewModelStore()
    private val session =
        ToolScanSession(
            "run",
            CleanupTool.LARGE_FILES.name,
            1,
            ToolScope(emptyList()).encode(),
            ScanStatuses.COMPLETED,
            active = true,
        )
    private val item =
        ToolItemEntity("run", "file", "primary", "/file", "file", 200_000_000, 1, "DOCUMENTS", selected = true)
    private val active = MutableStateFlow<ToolResults?>(ToolResults(session, listOf(item)))
    private val latest = MutableStateFlow<ToolScanSession?>(session)
    private val activeTool = MutableStateFlow<ToolRoute?>(null)
    private val workActive = MutableStateFlow(false)
    private val batch =
        CleanupBatch(session, listOf(item), listOf(VerifiedCleanupFile(item, "digest", null)), emptyList(), 0)

    @Before
    fun configure() {
        every { container.cleanupCoordinator(any()) } returns cleanup
        every { container.toolResultStore } returns store
        every { container.scanScheduler } returns scheduler
        every { store.active(any()) } returns active
        every { store.latest(any()) } returns latest
        every { scheduler.workActive } returns workActive
        every { scheduler.activeTool } returns activeTool
        every { container.storageAccessManager.hasAccess() } returns true
        every { container.preferencesRepository.confirmBeforeDelete } returns flowOf(true)
        coEvery { store.dao.pending() } returns emptyList()
        coEvery { container.scanStore.pendingDeletions() } returns emptyList()
        coEvery { cleanup.prepare(any(), any()) } returns batch
        coEvery { cleanup.execute(any()) } returns CleanupResult(listOf(CleanupOutcome(item.id, "moved")))
        coEvery { scheduler.startTool(any(), any(), any()) } returns ToolRoute(CleanupTool.LARGE_FILES.name, "next")
    }

    @After
    fun clearModels() = owner.clear()

    private fun TestScope.model(
        tool: CleanupTool = CleanupTool.LARGE_FILES,
        requested: String? = null,
    ): ToolViewModel {
        val model = ToolViewModel(tool, requested, container, mockk(), saved)
        owner.put("model", model)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        return model
    }

    @Test
    fun screenshotMonthSelectionIntersectsVisibleIdsAndRespectsRunningState() =
        runTest(main.dispatcher) {
            val started = 1_800_000_000_000L
            active.value =
                ToolResults(
                    session.copy(tool = CleanupTool.SCREENSHOTS.name, startedAt = started),
                    listOf(
                        item.copy(id = "old", modifiedAt = 1_600_000_000_000L),
                        item.copy(id = "recent", modifiedAt = started - 1000),
                    ),
                )
            saved["months"] = 6
            val model = model(CleanupTool.SCREENSHOTS)
            model.selectVisible(setOf("old", "recent", "foreign"), true)
            runCurrent()
            coVerify(exactly = 1) { store.setSelection("run", setOf("old"), true) }
            activeTool.value = ToolRoute(CleanupTool.SCREENSHOTS.name)
            runCurrent()
            model.selectVisible(setOf("old"), false)
            runCurrent()
            coVerify(exactly = 0) { store.setSelection(any(), any(), false) }
        }

    @Test
    fun newDateFiltersIncludeAllDatesAndSavedSixMonthFilterSurvives() =
        runTest(main.dispatcher) {
            for (tool in listOf(CleanupTool.SCREENSHOTS, CleanupTool.OLD_DOWNLOADS)) {
                saved.remove<Int>("months")
                assertEquals(
                    0,
                    model(tool)
                        .state.value.controls.months,
                )
                saved["months"] = 6
                assertEquals(
                    6,
                    model(tool)
                        .state.value.controls.months,
                )
            }
        }

    @Test
    fun batchSelectionRespectsSizeFilterAndCannotSelectAnotherGroupOrKeeper() =
        runTest(main.dispatcher) {
            active.value = ToolResults(session, listOf(item, item.copy(id = "small", sizeBytes = 10)))
            val files = model()
            files.selectVisible(setOf("file", "small", "foreign"), true)
            runCurrent()
            coVerify(exactly = 1) { store.setSelection("run", setOf("file"), true) }
            active.value =
                ToolResults(
                    session.copy(tool = CleanupTool.SIMILAR_PHOTOS.name),
                    listOf(
                        item.copy(id = "keeper", groupId = "first", keeper = true),
                        item.copy(id = "other", groupId = "first"),
                        item.copy(id = "hidden", groupId = "second"),
                    ),
                )
            val photos = model(CleanupTool.SIMILAR_PHOTOS)
            photos.selectVisible(setOf("keeper", "other", "hidden"), true)
            photos.keep("hidden")
            photos.keep("other")
            runCurrent()
            coVerify(exactly = 1) { store.setSelection("run", setOf("other"), true) }
            coVerify(exactly = 0) { store.keep("run", "hidden") }
            coVerify(exactly = 1) { store.keep("run", "other") }
            activeTool.value = ToolRoute(CleanupTool.SIMILAR_PHOTOS.name)
            runCurrent()
            photos.keep("keeper")
            photos.selectVisible(setOf("other"), false)
            runCurrent()
            coVerify(exactly = 0) { store.keep("run", "keeper") }
            coVerify(exactly = 0) { store.setSelection("run", any(), false) }
        }

    @Test
    fun controlsPersistAndCleanupRequiresConfirmation() =
        runTest(main.dispatcher) {
            val model = model()
            model.controls(
                model.state.value.controls
                    .copy(sort = "oldest", months = 3, folder = "/folder"),
            )
            runCurrent()
            assertEquals("oldest", saved.get<String>("sort"))
            assertEquals(3, saved.get<Int>("months"))
            model.requestCleanup()
            runCurrent()
            assertEquals(batch, model.state.value.fileConfirmation)
            coVerify(exactly = 0) { cleanup.execute(any()) }
            model.confirmCleanup()
            runCurrent()
            assertEquals(
                1,
                model.state.value.outcome!!
                    .completed,
            )
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.fileConfirmation)
            model.dismissOutcome()
            runCurrent()
            assertNull(model.state.value.outcome)
        }

    @Test
    fun canceledConfirmationNeverExecutesAndDisabledConfirmationExecutesOnce() =
        runTest(main.dispatcher) {
            val model = model()
            model.requestCleanup()
            runCurrent()
            model.dismissConfirmation()
            model.confirmCleanup()
            runCurrent()
            coVerify(exactly = 0) { cleanup.execute(any()) }
            every { container.preferencesRepository.confirmBeforeDelete } returns flowOf(false)
            model.requestCleanup()
            runCurrent()
            coVerify(exactly = 1) { cleanup.execute(batch) }
        }

    @Test
    fun preparationAndExecutionFailuresReleaseBusyState() =
        runTest(main.dispatcher) {
            val model = model()
            for ((failure, notice) in listOf(
                IllegalArgumentException() to R.string.tool_changed,
                SQLiteException() to R.string.deletion_recovery_pending,
            )) {
                coEvery { cleanup.prepare(any(), any()) } throws failure
                model.requestCleanup()
                runCurrent()
                assertEquals(notice, model.state.value.notice)
                assertFalse(model.state.value.busy)
            }
            coEvery { cleanup.prepare(any(), any()) } returns batch.copy(files = emptyList())
            model.requestCleanup()
            runCurrent()
            assertEquals(R.string.tool_changed, model.state.value.notice)
            assertNull(model.state.value.fileConfirmation)
            coEvery { cleanup.prepare(any(), any()) } returns batch
            coEvery { cleanup.execute(any()) } throws IllegalStateException()
            model.requestCleanup()
            runCurrent()
            model.confirmCleanup()
            runCurrent()
            assertEquals(R.string.deletion_recovery_pending, model.state.value.notice)
            assertFalse(model.state.value.busy)
        }

    @Test
    fun foldersUseTheirOwnConfirmationAndOutcome() =
        runTest(main.dispatcher) {
            val folderBatch = EmptyFolderBatch(session, listOf(item.copy(directory = true)), emptyMap())
            coEvery { container.emptyFolderDeletionCoordinator.prepare("run") } returns folderBatch
            coEvery { container.emptyFolderDeletionCoordinator.execute(folderBatch) } returns EmptyFolderResult(2, 1)
            val model = model(CleanupTool.EMPTY_FOLDERS)
            model.requestCleanup()
            runCurrent()
            assertEquals(folderBatch, model.state.value.folderConfirmation)
            model.confirmCleanup()
            runCurrent()
            assertTrue(
                model.state.value.outcome!!
                    .directories,
            )
            assertEquals(
                2,
                model.state.value.outcome!!
                    .completed,
            )
            assertEquals(
                1,
                model.state.value.outcome!!
                    .failed,
            )
        }

    @Test
    fun runningAndBusyOperationsCannotChangeSelectionOrStartCleanup() =
        runTest(main.dispatcher) {
            val model = model()
            activeTool.value = ToolRoute(CleanupTool.LARGE_FILES.name, "scan")
            runCurrent()
            model.selectVisible(setOf("file"), false)
            model.requestCleanup()
            coVerify(exactly = 0) { store.setSelection(any(), any(), any()) }
            coVerify(exactly = 0) { cleanup.prepare(any(), any()) }
            activeTool.value = null
            runCurrent()
            model.selectVisible(setOf("file"), false)
            runCurrent()
            coVerify { store.setSelection("run", setOf("file"), false) }
            val pending = CompletableDeferred<CleanupBatch>()
            coEvery { cleanup.prepare(any(), any()) } coAnswers { pending.await() }
            model.requestCleanup()
            runCurrent()
            model.requestCleanup()
            model.selectVisible(setOf("file"), true)
            model.refresh()
            coVerify(exactly = 1) { cleanup.prepare(any(), any()) }
            coVerify(exactly = 0) { scheduler.startTool(any(), any(), any()) }
            pending.complete(batch)
            runCurrent()
        }

    @Test
    fun refreshPreservesVolumeClearsFolderAndReportsBusyOrFailure() =
        runTest(main.dispatcher) {
            val model = model()
            model.controls(
                model.state.value.controls
                    .copy(folder = "/folder"),
            )
            model.refresh("sd")
            runCurrent()
            assertEquals("sd", saved.get<String>("volume"))
            assertNull(model.state.value.controls.folder)
            model.refresh()
            runCurrent()
            coVerify(exactly = 2) { scheduler.startTool(CleanupTool.LARGE_FILES, "sd", any()) }
            coEvery { scheduler.startTool(any(), any(), any()) } returns null
            model.refresh()
            runCurrent()
            assertEquals(R.string.tool_scan_busy, model.state.value.notice)
            coEvery { scheduler.startTool(any(), any(), any()) } throws IllegalStateException()
            model.refresh()
            runCurrent()
            assertEquals(R.string.scan_not_started, model.state.value.notice)
            model.stop()
            verify { scheduler.stopScan() }
        }

    @Test
    fun missingPinnedRunDoesNotExposeOtherResultsAndFirstOpenScansOnlyWithoutHistory() =
        runTest(main.dispatcher) {
            val pinned = model(requested = "old-run")
            assertNull(pinned.state.value.results)
            assertEquals(R.string.tool_run_unavailable, pinned.state.value.notice)
            pinned.onResume().join()
            coVerify(exactly = 0) { scheduler.startTool(any(), any(), any()) }
            active.value = null
            latest.value = null
            val fresh = model()
            fresh.onResume().join()
            runCurrent()
            fresh.onResume().join()
            runCurrent()
            assertTrue(fresh.state.value.hasAccess)
            coVerify(exactly = 1) { scheduler.startTool(any(), any(), any()) }
        }
}
