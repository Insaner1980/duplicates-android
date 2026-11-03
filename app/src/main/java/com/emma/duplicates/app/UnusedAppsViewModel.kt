package com.emma.duplicates.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.data.UnusedAppsRepository
import com.emma.duplicates.data.UnusedAppsResult
import com.emma.duplicates.data.UsageAccessState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UnusedAppsViewModel(
    private val repository: UnusedAppsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutableDays = MutableStateFlow(saved["days"] ?: DEFAULT_UNUSED_DAYS)
    val days = mutableDays.asStateFlow()
    private val mutableState = MutableStateFlow(UnusedAppsResult(UsageAccessState.LOADING))
    val state = mutableState.asStateFlow()
    private var load: Job? = null
    private var granted = false

    fun refresh() {
        load?.cancel()
        load =
            viewModelScope.launch {
                mutableState.value = UnusedAppsResult(UsageAccessState.LOADING)
                mutableState.value = repository.load(days.value, granted)
                if (repository.hasAccess()) granted = true
            }
    }

    fun days(value: Int) {
        saved["days"] = value
        mutableDays.value = value
        refresh()
    }

    private companion object {
        const val DEFAULT_UNUSED_DAYS = 90
    }
}
