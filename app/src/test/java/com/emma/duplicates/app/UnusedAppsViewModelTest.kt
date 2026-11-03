package com.emma.duplicates.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.emma.duplicates.data.UnusedAppsRepository
import com.emma.duplicates.data.UnusedAppsResult
import com.emma.duplicates.data.UsageAccessState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnusedAppsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun selectedDaysPersistAndLaterLoadsRememberPreviouslyGrantedAccess() =
        runTest(main.dispatcher) {
            val repository = mockk<UnusedAppsRepository>()
            val saved = SavedStateHandle(mapOf("days" to 30))
            coEvery { repository.load(any(), any()) } returns UnusedAppsResult(UsageAccessState.EMPTY)
            every { repository.hasAccess() } returns true
            val model = UnusedAppsViewModel(repository, saved)
            val owner = ViewModelStore().also { it.put("model", model) }
            try {
                assertEquals(30, model.days.value)
                model.refresh()
                runCurrent()
                coVerify { repository.load(30, false) }
                model.days(180)
                runCurrent()
                assertEquals(180, saved.get<Int>("days"))
                coVerify { repository.load(180, true) }
                assertEquals(UsageAccessState.EMPTY, model.state.value.state)
            } finally {
                owner.clear()
            }
        }

    @Test
    fun newRefreshCancelsStaleResultAndUsesDefaultDays() =
        runTest(main.dispatcher) {
            val repository = mockk<UnusedAppsRepository>()
            val pending = CompletableDeferred<UnusedAppsResult>()
            coEvery { repository.load(any(), any()) } coAnswers { pending.await() }
            every { repository.hasAccess() } returns false
            val model = UnusedAppsViewModel(repository, SavedStateHandle())
            val owner = ViewModelStore().also { it.put("model", model) }
            try {
                assertEquals(90, model.days.value)
                model.refresh()
                runCurrent()
                assertEquals(UsageAccessState.LOADING, model.state.value.state)
                coEvery { repository.load(any(), any()) } returns UnusedAppsResult(UsageAccessState.NOT_GRANTED)
                model.refresh()
                runCurrent()
                pending.complete(UnusedAppsResult(UsageAccessState.RESULTS))
                runCurrent()
                assertEquals(UsageAccessState.NOT_GRANTED, model.state.value.state)
            } finally {
                owner.clear()
            }
        }
}
