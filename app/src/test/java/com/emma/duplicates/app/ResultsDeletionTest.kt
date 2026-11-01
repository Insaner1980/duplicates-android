package com.emma.duplicates.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import com.emma.duplicates.R
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.core.database.ScanStoreDatabaseTest
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.domain.deletion.DeletionCoordinator
import com.emma.duplicates.domain.deletion.DeletionDatabaseUpdate
import com.emma.duplicates.domain.deletion.DeletionGroup
import com.emma.duplicates.domain.deletion.DeletionResult
import com.emma.duplicates.domain.deletion.DeletionStatus
import com.emma.duplicates.domain.deletion.FileDeletionOutcome
import com.emma.duplicates.ui.results.ResultsFilter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ResultsDeletionTest : ScanStoreDatabaseTest() {
    @get:Rule val dispatcher = MainDispatcherRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun oneBatchContainsBothGroupsAndKeepersAndDisclosesHiddenSelections() =
        runTest(dispatcher.dispatcher) {
            seed()
            val captured = slot<List<DeletionGroup>>()
            val coordinator = mockk<DeletionCoordinator>()
            coEvery { coordinator.delete(capture(captured)) } returns outcome()
            val model = ResultsViewModel(scanStore, preferences(), coordinator, savedStateHandle = SavedStateHandle())
            model.state.first { it.hasCompletedScan }
            model.setFilter(ResultsFilter.DOCUMENTS)
            val filtered = model.state.first { it.selectedFilter == ResultsFilter.DOCUMENTS }
            assertEquals(2, filtered.selectedCount)
            assertEquals(1, filtered.hiddenSelectedCount)
            model.requestDelete()
            val confirmation = model.state.first { it.deleteConfirmationVisible && !it.busy }
            assertEquals(2, confirmation.confirmationCount)
            assertEquals(200L, confirmation.confirmationBytes)
            assertEquals(1, confirmation.confirmationHiddenCount)
            coVerify(exactly = 0) { coordinator.delete(any()) }
            model.confirmDelete()
            model.confirmDelete()
            val done = model.state.first { it.deletionResult != null && !it.busy }
            coVerify(exactly = 1) { coordinator.delete(any()) }
            assertEquals(2, captured.captured.size)
            assertTrue(captured.captured.all { it.files.size == 2 })
            assertEquals(
                setOf("member-doc-1", "member-photo-1"),
                captured.captured
                    .flatMap { it.files }
                    .filter { it.selectedForDeletion }
                    .mapTo(hashSetOf()) { it.memberId },
            )
            assertTrue(
                captured.captured
                    .single { it.id == "photo" }
                    .files
                    .all { it.requiresMediaAuthorization },
            )
            assertFalse(
                captured.captured
                    .single { it.id == "doc" }
                    .files
                    .any { it.requiresMediaAuthorization },
            )
            assertEquals(1, done.deletionResult!!.deletedFileCount)
            assertEquals(1, done.deletionResult!!.failedFileCount)
            assertEquals(100L, done.deletionResult!!.reclaimedBytes)
        }

    @Test
    fun changedSelectionRejectsCapturedBatchWithoutDeleting() =
        runTest(dispatcher.dispatcher) {
            val coordinator = mockk<DeletionCoordinator>(relaxed = true)
            val model = prepareDeletionConfirmation(coordinator)
            scanStore.setMemberSelected("member-doc-1", false)
            model.confirmDelete()
            assertEquals(R.string.deletion_selection_changed, model.state.first { it.messageRes != null }.messageRes)
            coVerify(exactly = 0) { coordinator.delete(any()) }
        }

    @Test
    fun changedSessionRejectsCapturedBatchWithoutDeleting() =
        runTest(dispatcher.dispatcher) {
            val coordinator = mockk<DeletionCoordinator>(relaxed = true)
            val model = prepareDeletionConfirmation(coordinator)
            scanStore.clearScanMetadata()
            seed()
            scanStore.startSession("replacement", 30L)
            scanStore.completeSession("replacement", 40L, ScanCompletionMetrics(0, 0, 0, 0, 0, 0, 0))
            model.confirmDelete()
            model.state.first { it.messageRes == R.string.deletion_selection_changed }
            coVerify(exactly = 0) { coordinator.delete(any()) }
        }

    @Test
    fun canceledConfirmationAndZeroSelectionNeverDelete() =
        runTest(dispatcher.dispatcher) {
            val coordinator = mockk<DeletionCoordinator>(relaxed = true)
            val model = prepareDeletionConfirmation(coordinator)
            model.dismissDelete()
            model.confirmDelete()
            scanStore.setMemberSelected("member-doc-1", false)
            scanStore.setMemberSelected("member-photo-1", false)
            model.state.first { it.hasCompletedScan && it.selectedCount == 0 }
            model.requestDelete()
            model.state.first { !it.busy && !it.deleteConfirmationVisible }
            coVerify(exactly = 0) { coordinator.delete(any()) }
        }

    @Test
    fun disabledConfirmationRunsOnceAndLocksConflictingActionsDuringAuthorization() =
        runTest(dispatcher.dispatcher) {
            seed()
            val preferences = preferences()
            preferences.setConfirmBeforeDelete(false)
            val finish = CompletableDeferred<DeletionResult>()
            val coordinator = mockk<DeletionCoordinator>()
            coEvery { coordinator.delete(any()) } coAnswers { finish.await() }
            val model = ResultsViewModel(scanStore, preferences, coordinator, savedStateHandle = SavedStateHandle())
            model.state.first { it.hasCompletedScan }
            model.requestDelete()
            model.requestDelete()
            model.state.first { it.busy }
            model.changeSelection("member-doc-1", false)
            model.requestClear()
            model.confirmClear()
            assertEquals(2, model.state.value.selectedCount)
            assertFalse(model.state.value.clearConfirmationVisible)
            finish.complete(outcome())
            model.state.first { it.deletionResult != null && !it.busy }
            coVerify(exactly = 1) { coordinator.delete(any()) }
        }

    @Test
    fun unexpectedFailureReportsUnknownProgressInsteadOfInventingZeroDeleted() =
        runTest(dispatcher.dispatcher) {
            seed()
            val preferences = preferences()
            preferences.setConfirmBeforeDelete(false)
            val coordinator = mockk<DeletionCoordinator>()
            coEvery { coordinator.delete(any()) } throws IllegalStateException("interrupted after checkpoint")
            val model = ResultsViewModel(scanStore, preferences, coordinator, savedStateHandle = SavedStateHandle())
            model.requestDelete()
            val failed = model.state.first { it.messageRes != null && !it.busy }
            assertEquals(R.string.deletion_incomplete, failed.messageRes)
            assertEquals(null, failed.deletionResult)
        }

    @Test
    fun finalGroupRemovalKeepsOutcomeVisibleAndReturnsCompletedEmptyResults() =
        runTest(dispatcher.dispatcher) {
            seed()
            val preferences = preferences()
            preferences.setConfirmBeforeDelete(false)
            val coordinator = mockk<DeletionCoordinator>()
            coEvery { coordinator.delete(any()) } coAnswers {
                database.withTransaction {
                    database.openHelper.writableDatabase.execSQL("DELETE FROM duplicate_groups")
                }
                outcome()
            }
            val model = ResultsViewModel(scanStore, preferences, coordinator, savedStateHandle = SavedStateHandle())
            model.requestDelete()
            val done = model.state.first { it.deletionResult != null && it.groups.isEmpty() && !it.busy }
            assertTrue(done.hasCompletedScan)
            assertEquals(0L, done.availableBytes)
        }

    @Test
    fun persistenceRejectsUnavailableTargetsAndTheFinalValidKeeper() =
        runTest(dispatcher.dispatcher) {
            seed()
            assertFalse(scanStore.setMemberSelected("member-doc-0", true))
            assertTrue(scanStore.setMemberSelected("member-doc-1", false))
            database.openHelper.writableDatabase.execSQL(
                "UPDATE indexed_files SET writable = 0 WHERE id = 'file-doc-1'",
            )
            assertFalse(scanStore.setMemberSelected("member-doc-1", true))
            assertTrue(scanStore.setMemberSelected("member-doc-0", true))
        }

    private suspend fun TestScope.prepareDeletionConfirmation(coordinator: DeletionCoordinator): ResultsViewModel {
        seed()
        val model = ResultsViewModel(scanStore, preferences(), coordinator, savedStateHandle = SavedStateHandle())
        model.requestDelete()
        model.state.first { it.deleteConfirmationVisible && !it.busy }
        return model
    }

    private suspend fun seed() {
        val files =
            listOf("doc", "photo").flatMap { group ->
                (0..1).map { index ->
                    IndexedFileEntity(
                        "file-$group-$index",
                        "session",
                        "/storage/$group/$index",
                        "$index.bin",
                        "bin",
                        null,
                        if (group == "doc") "DOCUMENTS" else "PHOTOS",
                        100L,
                        1L,
                        "primary",
                        "/storage/$group",
                        contentUri = "content://media/$group/$index",
                        readable = true,
                        writable = true,
                    )
                }
            }
        val groups =
            listOf("doc", "photo").map { group ->
                DuplicateGroupEntity(
                    group,
                    "session",
                    if (group == "doc") "DOCUMENTS" else "PHOTOS",
                    "hash-$group",
                    100L,
                    2,
                    100L,
                    group,
                )
            }
        val members =
            listOf("doc", "photo").flatMap { group ->
                (0..1).map { index ->
                    DuplicateMemberEntity(
                        "member-$group-$index",
                        group,
                        "file-$group-$index",
                        index == 0,
                        index == 1,
                        false,
                    )
                }
            }
        scanStore.startSession("session", 1L)
        scanStore.replaceStagedResults("session", files, groups, members)
        scanStore.completeSession("session", 2L, ScanCompletionMetrics(4, 400, 4, 2, 200, 0, 0))
    }

    private fun TestScope.preferences() =
        PreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = {
                temporary.root.resolve("settings.preferences_pb")
            }),
        )

    private fun outcome() =
        DeletionResult(
            listOf(
                FileDeletionOutcome("doc", "file-doc-1", DeletionStatus.DELETED, 100L),
                FileDeletionOutcome("photo", "file-photo-1", DeletionStatus.AUTHORIZATION_CANCELED),
            ),
            DeletionDatabaseUpdate(emptySet(), emptySet(), emptySet(), emptyList()),
        )
}
