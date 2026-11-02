package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.data.RecoveryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecoveryViewModel(
    private val repository: RecoveryRepository,
) : ViewModel() {
    val files = repository.files.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableResult = MutableStateFlow<String?>(null)
    val result = mutableResult.asStateFlow()

    init {
        viewModelScope.launch { repository.removeExpired() }
    }

    fun restore(id: String) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                mutableResult.value = repository.restore(id) ?: ""
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun dismissResult() {
        mutableResult.value = null
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
