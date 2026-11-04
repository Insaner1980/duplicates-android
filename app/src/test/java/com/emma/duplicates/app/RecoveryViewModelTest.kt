package com.emma.duplicates.app

import androidx.lifecycle.ViewModelStore
import com.emma.duplicates.core.database.RecoveryFile
import com.emma.duplicates.data.RecoveryEntry
import com.emma.duplicates.data.RecoveryRepository
import com.emma.duplicates.ui.settings.RecoveryAction
import com.emma.duplicates.ui.settings.RecoveryOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
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

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<RecoveryRepository>()
    private val files =
        MutableStateFlow(
            listOf(
                RecoveryFile("one", "/original/one", "/recovery/one/payload", 1),
                RecoveryFile("two", "/original/two", "/recovery/two/payload", 2),
            ),
        )
    private val owner = ViewModelStore()
    private val mutex = Mutex()
    private var allowed = true
    private lateinit var model: RecoveryViewModel

    @Before fun setUp() {
        every { repository.files } returns files
        coEvery { repository.describe(any(), any()) } answers {
            firstArg<List<RecoveryFile>>().map { RecoveryEntry(it, 7, it.storedPath, true) }
        }
        coEvery { repository.removeExpired(any()) } returns Unit
        coEvery { repository.restore(any(), any()) } answers { "/restored/" + firstArg<String>() }
        coEvery { repository.deletePermanently(any(), any()) } returns true
        model = RecoveryViewModel(repository, mutex) { allowed }
        owner.put("recovery", model)
    }

    @After fun tearDown() = owner.clear()

    @Test fun restoreSelectedProcessesFrozenIdsAndDoubleTapStartsOnlyOneOperation() =
        runTest(main.dispatcher) {
            runCurrent()
            model.select("one", true)
            val gate = CompletableDeferred<String?>()
            coEvery { repository.restore("one", any()) } coAnswers { gate.await() }
            model.restoreSelected()
            model.restoreSelected()
            model.select("two", true)
            assertTrue(model.state.value.busy)
            runCurrent()
            gate.complete("/restored/one")
            runCurrent()
            coVerify(exactly = 1) { repository.restore("one", any()) }
            coVerify(exactly = 0) { repository.restore("two", any()) }
            assertEquals(emptySet<String>(), model.state.value.selectedIds)
            assertEquals(
                RecoveryOutcome(RecoveryAction.RESTORE, 1, 0, listOf("/restored/one")),
                model.state.value.outcome,
            )
            assertFalse(model.state.value.busy)
        }

    @Test fun deleteRequiresExplicitConfirmationAndUsesFrozenIds() =
        runTest(main.dispatcher) {
            runCurrent()
            model.select("one", true)
            model.confirmDelete()
            runCurrent()
            coVerify(exactly = 0) { repository.deletePermanently(any(), any()) }
            model.requestDelete()
            model.dismissDelete()
            model.confirmDelete()
            runCurrent()
            coVerify(exactly = 0) { repository.deletePermanently(any(), any()) }
            model.requestDelete()
            model.select("two", true)
            model.confirmDelete()
            model.confirmDelete()
            runCurrent()
            coVerify(exactly = 1) { repository.deletePermanently("one", any()) }
            coVerify(exactly = 0) { repository.deletePermanently("two", any()) }
            assertEquals(setOf("two"), model.state.value.selectedIds)
            assertNull(model.state.value.pendingDeleteIds)
        }

    @Test fun partialFailureKeepsFailedSelection() =
        runTest(main.dispatcher) {
            runCurrent()
            coEvery { repository.restore("two", any()) } returns null
            model.select("one", true)
            model.select("two", true)
            model.restoreSelected()
            runCurrent()
            assertEquals(setOf("two"), model.state.value.selectedIds)
            assertEquals(
                RecoveryOutcome(RecoveryAction.RESTORE, 1, 1, listOf("/restored/one")),
                model.state.value.outcome,
            )
            coEvery { repository.deletePermanently("two", any()) } returns false
            model.requestDelete()
            model.confirmDelete()
            runCurrent()
            assertEquals(setOf("two"), model.state.value.selectedIds)
            assertEquals(RecoveryOutcome(RecoveryAction.DELETE, 0, 1), model.state.value.outcome)
        }

    @Test fun restoreOutcomeKeepsActualDestinationsIncludingRenamedFiles() =
        runTest(main.dispatcher) {
            runCurrent()
            val renamed = "/original/one.restored-93e6b89a-6ab6-4fb2-928c-25218c616840"
            coEvery { repository.restore("one", any()) } returns renamed
            coEvery { repository.restore("two", any()) } returns "/original/two"
            model.select("one", true)
            model.select("two", true)
            model.restoreSelected()
            runCurrent()
            assertEquals(
                RecoveryOutcome(RecoveryAction.RESTORE, 2, 0, listOf(renamed, "/original/two")),
                model.state.value.outcome,
            )
            model.dismissResult()
            assertNull(model.state.value.outcome)
        }

    @Test fun pendingCleanupBlocksRecoveryMutation() =
        runTest(main.dispatcher) {
            runCurrent()
            model.select("one", true)
            model.requestDelete()
            allowed = false
            model.confirmDelete()
            runCurrent()
            model.restoreSelected()
            runCurrent()
            coVerify(exactly = 0) { repository.restore(any(), any()) }
            coVerify(exactly = 0) { repository.deletePermanently(any(), any()) }
            assertEquals(setOf("one"), model.state.value.selectedIds)
            assertFalse(model.state.value.busy)
        }

    @Test fun selectionDropsIdsRemovedByExpiryAndResumeRefreshesMetadata() =
        runTest(main.dispatcher) {
            runCurrent()
            model.select("one", true)
            model.select("two", true)
            files.value = files.value.filter { it.id == "two" }
            runCurrent()
            assertEquals(setOf("two"), model.state.value.selectedIds)
            coEvery { repository.describe(any(), any()) } answers {
                firstArg<List<RecoveryFile>>().map { RecoveryEntry(it, null, null, false) }
            }
            model.onResume()
            runCurrent()
            assertFalse(
                model.state.value.entries
                    .single()
                    .available,
            )
            assertEquals(setOf("two"), model.state.value.selectedIds)
        }

    @Test fun cancelledBatchReleasesBusyAndMutexAndKeepsUnfinishedSelection() =
        runTest(main.dispatcher) {
            runCurrent()
            coEvery { repository.restore("two", any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
            model.select("one", true)
            model.select("two", true)
            model.restoreSelected()
            runCurrent()
            assertEquals(setOf("two"), model.state.value.selectedIds)
            owner.clear()
            runCurrent()
            assertFalse(model.state.value.busy)
            assertFalse(mutex.isLocked)
        }
}
