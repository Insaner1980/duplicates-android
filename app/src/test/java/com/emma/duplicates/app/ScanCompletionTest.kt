package com.emma.duplicates.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import com.emma.duplicates.core.database.ScanSessionEntity
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ScanStoreDatabaseTest
import com.emma.duplicates.ui.scan.ScanRunStatus
import com.emma.duplicates.ui.scan.ScanningUiState
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanCompletionTest : ScanStoreDatabaseTest() {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun oldResultsAreIgnoredAndFastCompletedNewSessionWaitsForItsOwnResults() =
        runTest(mainDispatcherRule.dispatcher) {
            database.scanDao().insertSession(ScanSessionEntity("old", ScanStatuses.COMPLETED, 1L, active = true))
            val model =
                ScanViewModel(
                    context,
                    scanStore,
                    mockk(relaxed = true),
                    savedStateHandle = SavedStateHandle(mapOf("baselineSessionId" to "old", "awaitNewSession" to true)),
                )
            assertFalse(model.state.first().completionReady)
            database.scanDao().insertSession(ScanSessionEntity("new", ScanStatuses.COMPLETED, 2L))
            val waiting = model.state.first { it.sessionId == "new" }
            assertFalse(waiting.completionReady)
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL("UPDATE scan_sessions SET active = 0 WHERE id = 'old'")
                database.openHelper.writableDatabase.execSQL("UPDATE scan_sessions SET active = 1 WHERE id = 'new'")
            }
            assertEquals("new", model.state.first { it.completionReady }.sessionId)
        }

    @Test
    fun failedAndCanceledSessionsNeverOpenPreviousCompletedResults() =
        runTest(mainDispatcherRule.dispatcher) {
            database.scanDao().insertSession(ScanSessionEntity("old", ScanStatuses.COMPLETED, 1L, active = true))
            for ((index, status) in listOf(ScanStatuses.FAILED, ScanStatuses.CANCELED).withIndex()) {
                val id = "terminal-$index"
                database.scanDao().insertSession(ScanSessionEntity(id, status, 2L + index))
                val model =
                    ScanViewModel(
                        context,
                        scanStore,
                        mockk(relaxed = true),
                        savedStateHandle =
                            SavedStateHandle(
                                mapOf(
                                    "baselineSessionId" to "old",
                                    "awaitNewSession" to true,
                                ),
                            ),
                    )
                val terminal = model.state.first { it.sessionId == id }
                assertFalse(terminal.completionReady)
                assertEquals("old", scanStore.activeCompletedSession.first()?.id)
            }
        }

    @Test
    fun workerFailureBeforeSessionCreationLeavesStartingState() =
        runTest(mainDispatcherRule.dispatcher) {
            val model =
                ScanViewModel(
                    context,
                    scanStore,
                    mockk(relaxed = true),
                    savedStateHandle = SavedStateHandle(mapOf("awaitNewSession" to true)),
                    workActive = kotlinx.coroutines.flow.flowOf(false),
                )
            val state = model.assertCanceledWithoutCompletion()
            org.junit.Assert.assertNotNull(state.failureMessage)
        }

    @Test
    fun restoredBoundSessionCannotNavigateToAnotherSession() =
        runTest(mainDispatcherRule.dispatcher) {
            database.scanDao().insertSession(ScanSessionEntity("other", ScanStatuses.COMPLETED, 1L, active = true))
            val model =
                ScanViewModel(
                    context,
                    scanStore,
                    mockk(relaxed = true),
                    savedStateHandle = SavedStateHandle(mapOf("boundSessionId" to "original")),
                    workActive = kotlinx.coroutines.flow.flowOf(false),
                )
            model.assertCanceledWithoutCompletion()
        }

    @Test
    fun notificationEntryResolvesCompletedActiveResults() =
        runTest(mainDispatcherRule.dispatcher) {
            database.scanDao().insertSession(ScanSessionEntity("done", ScanStatuses.COMPLETED, 1L, active = true))
            val model = ScanViewModel(context, scanStore, mockk(relaxed = true), savedStateHandle = SavedStateHandle())
            assertEquals("done", model.state.first { it.completionReady }.sessionId)
        }

    private suspend fun ScanViewModel.assertCanceledWithoutCompletion(): ScanningUiState {
        val canceled = state.first { it.runStatus == ScanRunStatus.CANCELED }
        assertFalse(canceled.completionReady)
        return canceled
    }
}
